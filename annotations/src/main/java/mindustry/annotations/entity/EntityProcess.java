package mindustry.annotations.entity;

import arc.files.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.pooling.Pool.*;
import arc.util.pooling.*;
import com.squareup.javapoet.*;
import com.squareup.javapoet.TypeSpec.*;
import com.sun.source.tree.*;
import mindustry.annotations.Annotations.*;
import mindustry.annotations.*;
import mindustry.annotations.util.*;
import mindustry.annotations.util.TypeIOResolver.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import java.lang.annotation.*;
import java.util.*;

@SupportedAnnotationTypes({
"mindustry.annotations.Annotations.EntityDef",
"mindustry.annotations.Annotations.GroupDef",
"mindustry.annotations.Annotations.EntityInterface",
"mindustry.annotations.Annotations.BaseComponent",
"mindustry.annotations.Annotations.TypeIOHandler"
})
public class EntityProcess extends BaseProcessor{
    Seq<EntityDefinition> definitions = new Seq<>();
    Seq<GroupDefinition> groupDefs = new Seq<>();
    Seq<Stype> baseComponents;
    ObjectMap<String, Stype> componentNames = new ObjectMap<>();
    ObjectMap<Stype, Seq<Stype>> componentDependencies = new ObjectMap<>();
    ObjectMap<Selement, Seq<Stype>> defComponents = new ObjectMap<>();
    ObjectMap<String, String> varInitializers = new ObjectMap<>();
    ObjectMap<String, String> methodBlocks = new ObjectMap<>();
    ObjectMap<Stype, ObjectSet<Stype>> baseClassDeps = new ObjectMap<>();
    ObjectSet<String> imports = new ObjectSet<>();
    Seq<Selement> allGroups = new Seq<>();
    Seq<Selement> allDefs = new Seq<>();
    Seq<Stype> allInterfaces = new Seq<>();
    Seq<TypeSpec.Builder> baseClasses = new Seq<>();
    ObjectSet<TypeSpec.Builder> baseClassIndexers = new ObjectSet<>();
    ClassSerializer serializer;

    {
        rounds = 3;
    }

    @Override
    public void process(RoundEnvironment env) throws Exception{
        allGroups.addAll(elements(GroupDef.class));
        allDefs.addAll(elements(EntityDef.class));
        allInterfaces.addAll(types(EntityInterface.class));

        //round 1: generate component interfaces
        if(round == 1){
            serializer = TypeIOResolver.resolve(this);
            baseComponents = types(BaseComponent.class);
            Seq<Stype> allComponents = types(Component.class);

            //store code
            for(Stype component : allComponents){
                for(Svar f : component.fields()){
                    VariableTree tree = f.tree();

                    //add initializer if it exists
                    if(tree.getInitializer() != null){
                        String init = tree.getInitializer().toString();
                        varInitializers.put(f.descString(), init);
                    }
                }

                for(Smethod elem : component.methods()){
                    if(elem.is(Modifier.ABSTRACT) || elem.is(Modifier.NATIVE)) continue;
                    //get all statements in the method, store them
                    methodBlocks.put(elem.descString(), elem.tree().getBody().toString()
                        .replaceAll("this\\.<(.*)>self\\(\\)", "this") //fix parameterized self() calls
                        .replaceAll("self\\(\\)", "this") //fix self() calls
                        .replaceAll(" yield ", "") //fix enchanced switch
                        .replaceAll("\\/\\*missing\\*\\/", "var") //fix vars
                    );
                }
            }

            //store components
            for(Stype type : allComponents){
                componentNames.put(type.name(), type);
            }

            //add component imports
            for(Stype comp : allComponents){
                imports.addAll(getImports(comp.e));
            }

            //create component interfaces
            for(Stype component : allComponents){


                TypeSpec.Builder inter = TypeSpec.interfaceBuilder(interfaceName(component))
                .addModifiers(Modifier.PUBLIC).addAnnotation(EntityInterface.class);

                inter.addJavadoc("Interface for {@link $L}", component.fullName());

                skipDeprecated(inter);

                //implement extra interfaces these components may have, e.g. position
                for(Stype extraInterface : component.interfaces().select(i -> !isCompInterface(i))){
                    //javapoet completely chokes on this if I add `addSuperInterface` or create the type name with TypeName.get
                    inter.superinterfaces.add(tname(extraInterface.fullName()));
                }

                //implement super interfaces
                Seq<Stype> depends = getDependencies(component);
                for(Stype type : depends){
                    inter.addSuperinterface(ClassName.get(packageName, interfaceName(type)));
                }

                if(component.annotation(Component.class).genInterface()){
                    ObjectSet<String> signatures = new ObjectSet<>();

                    //add utility methods to interface
                    for(Smethod method : component.methods()){
                        //skip private methods, those are for internal use.
                        if(method.isAny(Modifier.PRIVATE, Modifier.STATIC)) continue;

                        //keep track of signatures used to prevent dupes
                        signatures.add(method.e.toString());

                        inter.addMethod(MethodSpec.methodBuilder(method.name())
                        .addJavadoc(method.doc() == null ? "" : method.doc())
                        .addExceptions(method.thrownt())
                        .addTypeVariables(method.typeVariables().map(TypeVariableName::get))
                        .returns(method.ret().toString().equals("void") ? TypeName.VOID : method.retn())
                        .addParameters(method.params().map(v -> ParameterSpec.builder(v.tname(), v.name())
                        .build())).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).build());
                    }

                    //generate interface getters and setters for all "standard" fields
                    for(Svar field : component.fields().select(e -> !e.is(Modifier.STATIC) && !e.is(Modifier.PRIVATE) && !e.has(Import.class))){
                        String cname = field.name();

                        //getter
                        if(!signatures.contains(cname + "()")){
                            inter.addMethod(MethodSpec.methodBuilder(cname).addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                            .addAnnotations(Seq.with(field.annotations()).select(a -> a.toString().contains("Null") || a.toString().contains("Deprecated")).map(AnnotationSpec::get))
                            .addJavadoc(field.doc() == null ? "" : field.doc())
                            .returns(field.tname()).build());
                        }

                        //setter
                        if(!field.is(Modifier.FINAL) && !signatures.contains(cname + "(" + field.mirror().toString() + ")") &&
                        !field.annotations().contains(f -> f.toString().equals("@mindustry.annotations.Annotations.ReadOnly"))){
                            inter.addMethod(MethodSpec.methodBuilder(cname).addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                            .addJavadoc(field.doc() == null ? "" : field.doc())
                            .addParameter(ParameterSpec.builder(field.tname(), field.name())
                            .addAnnotations(Seq.with(field.annotations())
                            .select(a -> a.toString().contains("Null") || a.toString().contains("Deprecated")).map(AnnotationSpec::get)).build()).build());
                        }
                    }
                }

                write(inter);

                //generate base class if necessary
                //SPECIAL CASE: components with EntityDefs don't get a base class! the generated class becomes the base class itself
                if(component.annotation(Component.class).base()){

                    Seq<Stype> deps = depends.copy().add(component);
                    baseClassDeps.get(component, ObjectSet::new).addAll(deps);

                    //do not generate base classes when the component will generate one itself
                    if(!component.has(EntityDef.class)){
                        TypeSpec.Builder base = TypeSpec.classBuilder(baseName(component)).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);

                        //go through all the fields.
                        for(Stype type : deps){
                            //add public fields
                            for(Svar field : type.fields().select(e -> !e.is(Modifier.STATIC) && !e.is(Modifier.PRIVATE) && !e.has(Import.class) && !e.has(ReadOnly.class))){
                                FieldSpec.Builder builder = FieldSpec.builder(field.tname(),field.name(), Modifier.PUBLIC);

                                //keep transience
                                if(field.is(Modifier.TRANSIENT)) builder.addModifiers(Modifier.TRANSIENT);
                                //keep all annotations
                                builder.addAnnotations(field.annotations().map(AnnotationSpec::get));

                                //add initializer if it exists
                                if(varInitializers.containsKey(field.descString())){
                                    builder.initializer(varInitializers.get(field.descString()));
                                }

                                base.addField(builder.build());
                            }
                        }

                        //add interfaces
                        for(Stype type : deps){
                            base.addSuperinterface(tname(packageName, interfaceName(type)));
                        }

                        //add to queue to be written later
                        baseClasses.add(base);
                    }
                }

                //LOGGING

                Log.debug("&gGenerating interface for " + component.name());

                for(TypeName tn : inter.superinterfaces){
                    Log.debug("&g> &lbimplements @", simpleName(tn.toString()));
                }

                //log methods generated
                for(MethodSpec spec : inter.methodSpecs){
                    Log.debug("&g> > &c@ @(@)", simpleName(spec.returnType.toString()), spec.name, Seq.with(spec.parameters).toString(", ", p -> simpleName(p.type.toString()) + " " + p.name));
                }

                Log.debug("");
            }

        }else if(round == 2){ //round 2: get component classes and generate interfaces for them

            //parse groups
            //this needs to be done before the entity interfaces are generated, as the entity classes need to know which groups to add themselves to
            for(Selement<?> group : allGroups){
                GroupDef an = group.annotation(GroupDef.class);
                Seq<Stype> types = types(an, GroupDef::value).map(stype -> {
                    Stype result = interfaceToComp(stype);
                    if(result == null) throw new IllegalArgumentException("Interface " + stype + " does not have an associated component!");
                    return result;
                });

                //representative component type
                Stype repr = types.first();
                String groupType = repr.annotation(Component.class).base() ? baseName(repr) : interfaceName(repr);

                String name = group.name().startsWith("g") ? group.name().substring(1) : group.name();

                boolean collides = an.collide();
                groupDefs.add(new GroupDefinition(name,
                    ClassName.bestGuess(packageName + "." + groupType), types, an.spatial(), an.mapping(), collides));

                TypeSpec.Builder accessor = TypeSpec.interfaceBuilder("IndexableEntity__" + name);
                accessor.addMethod(MethodSpec.methodBuilder("setIndex__" + name).addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC).addParameter(int.class, "index").returns(void.class).build());
                write(accessor);
            }

            ObjectMap<String, Selement> usedNames = new ObjectMap<>();
            ObjectMap<Selement, ObjectSet<String>> extraNames = new ObjectMap<>();

            //look at each definition
            for(Selement<?> type : allDefs){
                EntityDef ann = type.annotation(EntityDef.class);

                //all component classes (not interfaces)
                Seq<Stype> components = allComponents(type);
                Seq<GroupDefinition> groups = groupDefs.select(g -> (!g.components.isEmpty() && !g.components.contains(s -> !components.contains(s))) || g.manualInclusions.contains(type));
                ObjectMap<String, Seq<Smethod>> methods = new ObjectMap<>();
                ObjectMap<FieldSpec, Svar> specVariables = new ObjectMap<>();
                ObjectSet<String> usedFields = new ObjectSet<>();

                //make sure there's less than 2 base classes
                Seq<Stype> baseClasses = components.select(s -> s.annotation(Component.class).base());
                if(baseClasses.size > 2){
                    err("No entity may have more than 2 base classes. Base classes: " + baseClasses, type);
                }

                //get base class type name for extension
                Stype baseClassType = baseClasses.any() ? baseClasses.first() : null;
                @Nullable TypeName baseClass = baseClasses.any() ? tname(packageName + "." + baseName(baseClassType)) : null;
                @Nullable TypeSpec.Builder baseClassBuilder = baseClassType == null ? null : this.baseClasses.find(b -> Reflect.<String>get(b, "name").equals(baseName(baseClassType)));
                boolean addIndexToBase = baseClassBuilder != null && baseClassIndexers.add(baseClassBuilder);
                //whether the main class is the base itself
                boolean typeIsBase = baseClassType != null && type.has(Component.class) && type.annotation(Component.class).base();

                if(type.isType() && (!type.name().endsWith("Def") && !type.name().endsWith("Comp"))){
                    err("All entity def names must end with 'Def'/'Comp'", type.e);
                }

                String name = type.isType() ?
                    type.name().replace("Def", "").replace("Comp", "") :
                    createName(type);

                //check for type name conflicts
                if(!typeIsBase && baseClass != null && name.equals(baseName(baseClassType))){
                    name += "Entity";
                }

                boolean legacy = ann.legacy();

                if(legacy){
                    baseClass = tname(packageName + "." + name);
                    name += "Legacy" + Strings.capitalize(type.name());
                }

                //skip double classes
                if(usedNames.containsKey(name)){
                    extraNames.get(usedNames.get(name), ObjectSet::new).add(type.name());
                    continue;
                }

                usedNames.put(name, type);
                extraNames.get(type, ObjectSet::new).add(name);
                if(!type.isType()){
                    extraNames.get(type, ObjectSet::new).add(type.name());
                }

                TypeSpec.Builder builder = TypeSpec.classBuilder(name).addModifiers(Modifier.PUBLIC);

                //add serialize() boolean
                builder.addMethod(MethodSpec.methodBuilder("serialize").addModifiers(Modifier.PUBLIC).returns(boolean.class).addStatement("return " + ann.serialize()).build());

                //all SyncField fields
                Seq<Svar> syncedFields = new Seq<>();
                Seq<Svar> allFields = new Seq<>();
                Seq<FieldSpec> allFieldSpecs = new Seq<>();

                boolean isSync = components.contains(s -> s.name().contains("Sync"));

                //add all components
                for(Stype comp : components){
                    //whether this component's fields are defined in the base class
                    boolean isShadowed = baseClass != null && !typeIsBase && baseClassDeps.get(baseClassType).contains(comp);

                    //write fields to the class; ignoring transient/imported ones
                    Seq<Svar> fields = comp.fields().select(f -> !f.has(Import.class));
                    for(Svar f : fields){
                        if(!usedFields.add(f.name())){
                            err("Field '" + f.name() + "' of component '" + comp.name() + "' redefines a field in entity '" + type.name() + "'");
                            continue;
                        }

                        FieldSpec.Builder fbuilder = FieldSpec.builder(f.tname(), f.name());
                        //keep statics/finals
                        if(f.is(Modifier.STATIC)){
                            fbuilder.addModifiers(Modifier.STATIC);
                            if(f.is(Modifier.FINAL)) fbuilder.addModifiers(Modifier.FINAL);
                        }
                        //add transient modifier for serialization
                        if(f.is(Modifier.TRANSIENT)){
                            fbuilder.addModifiers(Modifier.TRANSIENT);
                        }

                        //add initializer if it exists
                        if(varInitializers.containsKey(f.descString())){
                            fbuilder.initializer(varInitializers.get(f.descString()));
                        }

                        fbuilder.addModifiers(f.has(ReadOnly.class) || f.is(Modifier.PRIVATE) ? Modifier.PROTECTED : Modifier.PUBLIC);
                        fbuilder.addAnnotations(f.annotations().map(AnnotationSpec::get));
                        FieldSpec spec = fbuilder.build();

                        //whether this field would be added to the superclass
                        boolean isVisible = !f.is(Modifier.STATIC) && !f.is(Modifier.PRIVATE) && !f.has(ReadOnly.class);

                        //add the field only if it isn't visible or it wasn't implemented by the base class
                        //legacy classes have no extra fields
                        if((!isShadowed || !isVisible) && !legacy){
                            builder.addField(spec);
                        }

                        specVariables.put(spec, f);

                        allFieldSpecs.add(spec);
                        allFields.add(f);

                        //add extra sync fields
                        if(f.has(SyncField.class) && isSync && !legacy){
                            if(!f.tname().toString().equals("float")) err("All SyncFields must be of type float", f);

                            syncedFields.add(f);

                            //a synced field has 3 values:
                            //- target state
                            //- last state
                            //- current state (the field itself, will be written to)

                            //target
                            builder.addField(FieldSpec.builder(float.class, f.name() + EntityIO.targetSuf).addModifiers(Modifier.TRANSIENT, Modifier.PRIVATE).build());

                            //last
                            builder.addField(FieldSpec.builder(float.class, f.name() + EntityIO.lastSuf).addModifiers(Modifier.TRANSIENT, Modifier.PRIVATE).build());
                        }
                    }

                    //get all methods from components
                    for(Smethod elem : comp.methods()){
                        methods.get(elem.toString(), Seq::new).add(elem);
                    }
                }

                syncedFields.sortComparing(Selement::name);

                if(!methods.containsKey("toString()")){
                    //override toString method
                    builder.addMethod(MethodSpec.methodBuilder("toString")
                    .addAnnotation(Override.class)
                    .returns(String.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addStatement("return $S + $L", name + "#", "id").build());
                }

                EntityIO io = new EntityIO(type.name(), builder, allFieldSpecs, serializer, rootDirectory.child("annotations/src/main/resources/revisions").child(type.name()));
                //entities with no sync comp and no serialization gen no code
                boolean hasIO = ann.genio() && (components.contains(s -> s.name().contains("Sync")) || ann.serialize());

                TypeSpec.Builder indexBuilder = baseClassBuilder == null ? builder : baseClassBuilder;

                if(baseClassBuilder == null || addIndexToBase){
                    //implement indexable interfaces.
                    for(GroupDefinition def : groups){
                        indexBuilder.addSuperinterface(tname(packageName + ".IndexableEntity__" + def.name));
                        indexBuilder.addMethod(MethodSpec.methodBuilder("setIndex__" + def.name).addParameter(int.class, "index").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                        .addCode("index__$L = index;", def.name).build());
                    }
                }

                //add all methods from components
                for(ObjectMap.Entry<String, Seq<Smethod>> entry : methods){

                    //there are multiple @Replace implementations, or multiple non-void implementations.
                    if(entry.value.size > 1 && (entry.value.contains(m -> m.has(Replace.class)) || entry.value.count(m -> !m.isAny(Modifier.NATIVE, Modifier.ABSTRACT) && !m.isVoid()) > 1)){

                        //remove clutter
                        entry.value.removeAll(s -> s.is(Modifier.ABSTRACT));

                        Comparator<Smethod> comp = Structs.comps(
                            Structs.comps(
                                //highest priority first
                                Structs.comparingFloat(m -> m.has(MethodPriority.class) ? m.annotation(MethodPriority.class).value() : 0f),
                                //replacement means priority
                                Structs.comparingBool(m -> m.has(Replace.class))
                            ),

                            //otherwise, the 'highest' subclass (most dependencies)
                            Structs.comparingInt(m -> getDependencies(m.type()).size)
                        );

                        Smethod best = entry.value.max(comp);

                        if(entry.value.contains(s -> best != s && comp.compare(s, best) == 0)){
                            err("Type " + type + " has multiple components implementing method " + entry.value.first() + " in an ambiguous way. Use MethodPriority to designate which one should be used. Implementations: " +
                                entry.value.map(s -> s.descString()));
                        }

                        entry.value.clear();
                        entry.value.add(best);
                    }

                    entry.value.sort(Structs.comps(Structs.comparingFloat(m -> m.has(MethodPriority.class) ? m.annotation(MethodPriority.class).value() : 0), Structs.comparing(s -> s.up().getSimpleName().toString())));

                    //representative method
                    Smethod first = entry.value.first();

                    //skip internal impl
                    if(first.has(InternalImpl.class)){
                        continue;
                    }

                    //build method using same params/returns
                    MethodSpec.Builder mbuilder = MethodSpec.methodBuilder(first.name()).addModifiers(first.is(Modifier.PRIVATE) ? Modifier.PRIVATE : Modifier.PUBLIC);
                    //if(isFinal || entry.value.contains(s -> s.has(Final.class))) mbuilder.addModifiers(Modifier.FINAL);
                    if(entry.value.contains(s -> s.has(CallSuper.class))) mbuilder.addAnnotation(CallSuper.class); //add callSuper here if necessary
                    if(first.has(Nullable.class)) mbuilder.addAnnotation(Nullable.class);
                    if(first.is(Modifier.STATIC)) mbuilder.addModifiers(Modifier.STATIC);
                    mbuilder.addTypeVariables(first.typeVariables().map(TypeVariableName::get));
                    mbuilder.returns(first.retn());
                    mbuilder.addExceptions(first.thrownt());

                    for(Svar var : first.params()){
                        mbuilder.addParameter(var.tname(), var.name());
                    }

                    //only write the block if it's a void method with several entries
                    boolean writeBlock = first.ret().toString().equals("void") && entry.value.size > 1;

                    if((entry.value.first().is(Modifier.ABSTRACT) || entry.value.first().is(Modifier.NATIVE)) && entry.value.size == 1 && !entry.value.first().has(InternalImpl.class)){
                        err(entry.value.first().up().getSimpleName() + "#" + entry.value.first() + " is an abstract method and must be implemented in some component", type);
                    }

                    //SPECIAL CASE: inject group add/remove code
                    if(first.name().equals("add") || first.name().equals("remove")){
                        mbuilder.addStatement("if(added == $L) return", first.name().equals("add"));

                        for(GroupDefinition def : groups){
                            if(first.name().equals("add")){
                                //remove/add from each group, assume imported
                                mbuilder.addStatement("index__$L = Groups.$L.addIndex(this)", def.name, def.name);
                            }else{
                                //remove/add from each group, assume imported
                                mbuilder.addStatement("Groups.$L.removeIndex(this, index__$L);", def.name, def.name);

                                mbuilder.addStatement("index__$L = -1", def.name);
                            }
                        }
                    }

                    boolean specialIO = false;

                    if(hasIO){
                        //SPECIAL CASE: I/O code
                        //note that serialization is generated even for non-serializing entities for manual usage
                        if((first.name().equals("read") || first.name().equals("write"))){
                            io.write(mbuilder, first.name().equals("write"));
                            specialIO = true;
                        }

                        //SPECIAL CASE: sync I/O code
                        if((first.name().equals("readSync") || first.name().equals("writeSync"))){
                            io.writeSync(mbuilder, first.name().equals("writeSync"), allFields);
                        }

                        //SPECIAL CASE: sync I/O code for writing to/from a manual buffer
                        if((first.name().equals("readSyncManual") || first.name().equals("writeSyncManual"))){
                            io.writeSyncManual(mbuilder, first.name().equals("writeSyncManual"), syncedFields);
                        }

                        //SPECIAL CASE: interpolate method implementation
                        if(first.name().equals("interpolate")){
                            io.writeInterpolate(mbuilder, syncedFields);
                        }

                        //SPECIAL CASE: method to snap to target position after being read for the first time
                        if(first.name().equals("snapSync")){
                            mbuilder.addStatement("updateSpacing = 16");
                            mbuilder.addStatement("lastUpdated = $T.millis()", Time.class);
                            for(Svar field : syncedFields){
                                //reset last+current state to target position
                                mbuilder.addStatement("$L = $L", field.name() + EntityIO.lastSuf, field.name() + EntityIO.targetSuf);
                                mbuilder.addStatement("$L = $L", field.name(), field.name() + EntityIO.targetSuf);
                            }
                        }

                        //SPECIAL CASE: method to snap to current position so interpolation doesn't go wild
                        if(first.name().equals("snapInterpolation")){
                            mbuilder.addStatement("updateSpacing = 16");
                            mbuilder.addStatement("lastUpdated = $T.millis()", Time.class);
                            for(Svar field : syncedFields){
                                //reset last+current state to target position
                                mbuilder.addStatement("$L = $L", field.name() + EntityIO.lastSuf, field.name());
                                mbuilder.addStatement("$L = $L", field.name() + EntityIO.targetSuf, field.name());
                            }
                        }
                    }

                    for(Smethod elem : entry.value){
                        String descStr = elem.descString();

                        if(elem.is(Modifier.ABSTRACT) || elem.is(Modifier.NATIVE) || !methodBlocks.containsKey(descStr)) continue;

                        //get all statements in the method, copy them over
                        String str = methodBlocks.get(descStr);
                        //name for code blocks in the methods
                        String blockName = elem.up().getSimpleName().toString().toLowerCase().replace("comp", "");

                        //skip empty blocks
                        if(str.replace("{", "").replace("\n", "").replace("}", "").replace("\t", "").replace(" ", "").isEmpty()){
                            continue;
                        }

                        //wrap scope to prevent variable leakage
                        if(writeBlock){
                            //replace return; with block break
                            str = str.replace("return;", "break " + blockName + ";");
                            mbuilder.addCode(blockName + ": {\n");
                        }

                        //trim block
                        str = str.substring(2, str.length() - 1);

                        //make sure to remove braces here
                        mbuilder.addCode(str);

                        //end scope
                        if(writeBlock) mbuilder.addCode("}\n");
                    }

                    //add free code to remove methods - always at the end
                    //this only gets called next frame.
                    if(first.name().equals("remove") && ann.pooled()){
                        mbuilder.addStatement("mindustry.gen.Groups.queueFree(($T)this)", Poolable.class);
                    }

                    if(!legacy || specialIO){
                        builder.addMethod(mbuilder.build());
                    }
                }

                //add pool reset method and implement Poolable
                if(ann.pooled()){
                    builder.addSuperinterface(Poolable.class);
                    //implement reset()
                    MethodSpec.Builder resetBuilder = MethodSpec.methodBuilder("reset").addModifiers(Modifier.PUBLIC);
                    allFieldSpecs.sortComparing(s -> s.name);
                    for(FieldSpec spec : allFieldSpecs){
                        @Nullable Svar variable = specVariables.get(spec);
                        if(variable != null && variable.isAny(Modifier.STATIC, Modifier.FINAL)) continue;
                        String desc = variable.descString();

                        if(spec.type.isPrimitive()){
                            //set to primitive default
                            resetBuilder.addStatement("$L = $L", spec.name, variable != null && varInitializers.containsKey(desc) ? varInitializers.get(desc) : getDefault(spec.type.toString()));
                        }else{
                            //set to default null
                            if(!varInitializers.containsKey(desc)){
                                resetBuilder.addStatement("$L = null", spec.name);
                            } //else... TODO reset if poolable
                        }
                    }

                    builder.addMethod(resetBuilder.build());
                }

                //make constructor private
                builder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PROTECTED).build());

                //add create() method
                builder.addMethod(MethodSpec.methodBuilder("create").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(tname(packageName + "." + name))
                .addStatement(ann.pooled() ? "return Pools.obtain($L.class, " +name +"::new)" : "return new $L()", name).build());

                skipDeprecated(builder);

                if(!legacy){
                    TypeSpec.Builder fieldBuilder = baseClassBuilder != null ? baseClassBuilder : builder;
                    if(addIndexToBase || baseClassBuilder == null){
                        //add group index int variables
                        for(GroupDefinition def : groups){
                            fieldBuilder.addField(FieldSpec.builder(int.class, "index__" + def.name, Modifier.PROTECTED, Modifier.TRANSIENT).initializer("-1").build());
                        }
                    }
                }

                definitions.add(new EntityDefinition(packageName + "." + name, builder, type, typeIsBase ? null : baseClass, components, groups, allFieldSpecs, legacy));
            }

            //generate groups
            TypeSpec.Builder groupsBuilder = TypeSpec.classBuilder("Groups").addModifiers(Modifier.PUBLIC);
            MethodSpec.Builder groupInit = MethodSpec.methodBuilder("init").addModifiers(Modifier.PUBLIC, Modifier.STATIC);
            for(GroupDefinition group : groupDefs){
                //class names for interface/group
                ClassName itype =  group.baseType;
                ClassName groupc = ClassName.bestGuess("mindustry.entities.EntityGroup");

                //add field...
                groupsBuilder.addField(ParameterizedTypeName.get(
                    ClassName.bestGuess("mindustry.entities.EntityGroup"), itype), group.name, Modifier.PUBLIC, Modifier.STATIC);

                groupInit.addStatement("$L = new $T<>($L.class, $L, $L, (e, pos) -> { if(e instanceof $L.IndexableEntity__$L ix) ix.setIndex__$L(pos); })", group.name, groupc, itype, group.spatial, group.mapping, packageName, group.name, group.name);
            }

            //write the groups
            groupsBuilder.addMethod(groupInit.build());

            groupsBuilder.addField(boolean.class, "isClearing", Modifier.PUBLIC, Modifier.STATIC);

            MethodSpec.Builder groupClear = MethodSpec.methodBuilder("clear").addModifiers(Modifier.PUBLIC, Modifier.STATIC);
            groupClear.addStatement("isClearing = true");
            for(GroupDefinition group : groupDefs){
                groupClear.addStatement("$L.clear()", group.name);
            }
            groupClear.addStatement("isClearing = false");

            //write clear
            groupsBuilder.addMethod(groupClear.build());

            //add method for pool storage
            groupsBuilder.addField(FieldSpec.builder(ParameterizedTypeName.get(Seq.class, Poolable.class), "freeQueue", Modifier.PRIVATE, Modifier.STATIC).initializer("new Seq<>()").build());

            //method for freeing things
            MethodSpec.Builder groupFreeQueue = MethodSpec.methodBuilder("queueFree")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(Poolable.class, "obj")
                .addStatement("freeQueue.add(obj)");

            groupsBuilder.addMethod(groupFreeQueue.build());

            //add method for resizing all necessary groups
            MethodSpec.Builder groupResize = MethodSpec.methodBuilder("resize")
                .addParameter(TypeName.FLOAT, "x").addParameter(TypeName.FLOAT, "y").addParameter(TypeName.FLOAT, "w").addParameter(TypeName.FLOAT, "h")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC);

            MethodSpec.Builder groupUpdate = MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC);

            //free everything pooled at the start of each updaet
            groupUpdate
                .addStatement("for($T p : freeQueue) $T.free(p)", Poolable.class, Pools.class)
                .addStatement("freeQueue.clear()");

            //method resize
            for(GroupDefinition group : groupDefs){
                if(group.spatial){
                    groupResize.addStatement("$L.resize(x, y, w, h)", group.name);
                    groupUpdate.addStatement("$L.updatePhysics()", group.name);
                }
            }

            groupUpdate.addStatement("all.update()");

            for(GroupDefinition group : groupDefs){
                if(group.collides){
                    groupUpdate.addStatement("$L.collide()", group.name);
                }
            }

            groupsBuilder.addMethod(groupResize.build());
            groupsBuilder.addMethod(groupUpdate.build());

            write(groupsBuilder);

            //load map of sync IDs
            StringMap map = new StringMap();
            Fi idProps = rootDirectory.child("annotations/src/main/resources/classids.properties");
            if(!idProps.exists()) idProps.writeString("");
            PropertiesUtils.load(map, idProps.reader());
            //next ID to be used in generation
            Integer max = map.values().toSeq().map(Integer::parseInt).max(i -> i);
            int maxID = max == null ? 0 : max + 1;

            //assign IDs
            definitions.sort(Structs.comparing(t -> t.naming.toString()));
            for(EntityDefinition def : definitions){
                String name = def.naming.fullName();
                if(map.containsKey(name)){
                    def.classID = map.getInt(name);
                }else{
                    def.classID = maxID++;
                    map.put(name, def.classID + "");
                }
            }

            OrderedMap<String, String> res = new OrderedMap<>();
            res.putAll(map);
            res.orderedKeys().sort();

            //write assigned IDs
            PropertiesUtils.store(res, idProps.writer(false), "Maps entity names to IDs. Autogenerated.");

            //build mapping class for sync IDs
            TypeSpec.Builder idBuilder = TypeSpec.classBuilder("EntityMapping").addModifiers(Modifier.PUBLIC)
            .addField(FieldSpec.builder(TypeName.get(Prov[].class), "idMap", Modifier.PUBLIC, Modifier.STATIC).initializer("new Prov[256]").build())

            .addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(ObjectMap.class),
                tname(String.class), tname(Prov.class)),
                "nameMap", Modifier.PUBLIC, Modifier.STATIC).initializer("new ObjectMap<>()").build())

            .addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(IntMap.class), tname(String.class)),
                "customIdMap", Modifier.PUBLIC, Modifier.STATIC).initializer("new IntMap<>()").build())

            .addMethod(MethodSpec.methodBuilder("register").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.get(int.class))
                .addParameter(String.class, "name").addParameter(Prov.class, "constructor")
                .addStatement("int next = arc.util.Structs.indexOf(idMap, v -> v == null)")
                .addStatement("idMap[next] = constructor")
                .addStatement("nameMap.put(name, constructor)")
                .addStatement("customIdMap.put(next, name)")
                .addStatement("return next")
                .addJavadoc("Use this method for obtaining a classId for custom modded unit types. Only call this once for each type. Modded types should return this id in their overridden classId method.")
                .build())

            .addMethod(MethodSpec.methodBuilder("map").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.get(Prov.class)).addParameter(int.class, "id").addStatement("return idMap[id]").build())

            .addMethod(MethodSpec.methodBuilder("map").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.get(Prov.class)).addParameter(String.class, "name").addStatement("return nameMap.get(name)").build());

            CodeBlock.Builder idStore = CodeBlock.builder();

            //store the mappings
            for(EntityDefinition def : definitions){
                //store mapping
                idStore.addStatement("idMap[$L] = $L::new", def.classID, def.name);
                extraNames.get(def.naming).each(extra -> {
                    idStore.addStatement("nameMap.put($S, $L::new)", extra, def.name);
                    if(!Strings.camelToKebab(extra).equals(extra)){
                        idStore.addStatement("nameMap.put($S, $L::new)", Strings.camelToKebab(extra), def.name);
                    }
                });

                //return mapping
                def.builder.addMethod(MethodSpec.methodBuilder("classId").addAnnotation(Override.class)
                    .returns(int.class).addModifiers(Modifier.PUBLIC).addStatement("return " + def.classID).build());
            }


            idBuilder.addStaticBlock(idStore.build());

            write(idBuilder);
        }else{
            //round 3: generate actual classes and implement interfaces

            //implement each definition
            for(EntityDefinition def : definitions){

                ObjectSet<String> methodNames = def.components.flatMap(type -> type.methods().map(Smethod::simpleString)).<String>as().asSet();

                //add base class extension if it exists
                if(def.extend != null){
                    def.builder.superclass(def.extend);
                }

                //get interface for each component
                for(Stype comp : def.components){

                    //implement the interface
                    Stype inter = allInterfaces.find(i -> i.name().equals(interfaceName(comp)));
                    if(inter == null){
                        err("Failed to generate interface for", comp);
                        return;
                    }

                    def.builder.addSuperinterface(inter.tname());

                    if(def.legacy) continue;

                    @Nullable TypeSpec.Builder superclass = null;

                    if(def.extend != null){
                        superclass = baseClasses.find(b -> (packageName + "." + Reflect.get(b, "name")).equals(def.extend.toString()));
                    }

                    //generate getter/setter for each method
                    for(Smethod method : inter.methods()){
                        String var = method.name();
                        FieldSpec field = Seq.with(def.fieldSpecs).find(f -> f.name.equals(var));
                        //make sure it's a real variable AND that the component doesn't already implement it somewhere with custom logic
                        if(field == null || methodNames.contains(method.simpleString())) continue;

                        MethodSpec result = null;

                        //getter
                        if(!method.isVoid()){
                            result = MethodSpec.overriding(method.e).addStatement("return " + var).build();
                        }

                        //setter
                        if(method.isVoid() && !Seq.with(field.annotations).contains(f -> f.type.toString().equals("@mindustry.annotations.Annotations.ReadOnly"))){
                            result = MethodSpec.overriding(method.e).addStatement("this." + var + " = " + var).build();
                        }

                        //add getter/setter to parent class, if possible. when this happens, skip adding getters setters *here* because they are defined in the superclass.
                        if(result != null && superclass != null){
                            FieldSpec superField = Seq.with(superclass.fieldSpecs).find(f -> f.name.equals(var));

                            //found the right field, try to check for the method already existing now
                            if(superField != null){
                                MethodSpec fr = result;
                                MethodSpec targetMethod = Seq.with(superclass.methodSpecs).find(m -> m.name.equals(var) && m.returnType.equals(fr.returnType));
                                //if the method isn't added yet, add it. in any case, skip.
                                if(targetMethod == null){
                                    superclass.addMethod(result);
                                }
                                continue;
                            }
                        }

                        if(result != null){
                            def.builder.addMethod(result);
                        }
                    }
                }

                write(def.builder, imports.toSeq());
            }

            //write base classes last
            for(TypeSpec.Builder b : baseClasses){
                write(b, imports.toSeq());
            }
        }
    }

    Seq<String> getImports(Element elem){
        return Seq.with(trees.getPath(elem).getCompilationUnit().getImports()).map(Object::toString);
    }

    /** @return interface for a component type */
    String interfaceName(Stype comp){
        String suffix = "Comp";
        if(!comp.name().endsWith(suffix)) err("All components must have names that end with 'Comp'", comp.e);

        //example: BlockComp -> IBlock
        return comp.name().substring(0, comp.name().length() - suffix.length()) + "c";
    }

    /** @return base class name for a component type */
    String baseName(Stype comp){
        String suffix = "Comp";
        if(!comp.name().endsWith(suffix)) err("All components must have names that end with 'Comp'", comp.e);

        return comp.name().substring(0, comp.name().length() - suffix.length());
    }

    @Nullable Stype interfaceToComp(Stype type){
        //example: IBlock -> BlockComp
        String name = type.name().substring(0, type.name().length() - 1) + "Comp";
        return componentNames.get(name);
    }

    /** @return all components that a entity def has */
    Seq<Stype> allComponents(Selement<?> type){
        if(!defComponents.containsKey(type)){
            //get base defs
            Seq<Stype> interfaces = types(type.annotation(EntityDef.class), EntityDef::value);
            Seq<Stype> components = new Seq<>();
            for(Stype i : interfaces){
                Stype comp = interfaceToComp(i);
                if(comp != null){
                   components.add(comp);
                }else{
                    throw new IllegalArgumentException("Type '" + i + "' is not a component interface!");
                }
            }

            ObjectSet<Stype> out = new ObjectSet<>();
            for(Stype comp : components){
                //get dependencies for each def, add them
                out.add(comp);
                out.addAll(getDependencies(comp));
            }

            defComponents.put(type, out.toSeq());
        }

        return defComponents.get(type);
    }

    Seq<Stype> getDependencies(Stype component){
        if(!componentDependencies.containsKey(component)){
            ObjectSet<Stype> out = new ObjectSet<>();
            //add base component interfaces
            out.addAll(component.interfaces().select(this::isCompInterface).map(this::interfaceToComp));
            //remove self interface
            out.remove(component);

            //out now contains the base dependencies; finish constructing the tree
            ObjectSet<Stype> result = new ObjectSet<>();
            for(Stype type : out){
                result.add(type);
                result.addAll(getDependencies(type));
            }

            if(component.annotation(BaseComponent.class) == null){
                result.addAll(baseComponents);
            }

            //remove it again just in case
            out.remove(component);
            componentDependencies.put(component, result.toSeq());
        }

        return componentDependencies.get(component);
    }

    boolean isCompInterface(Stype type){
        return interfaceToComp(type) != null;
    }

    String createName(Selement<?> elem){
        Seq<Stype> comps = types(elem.annotation(EntityDef.class), EntityDef::value).map(this::interfaceToComp);
        comps.sortComparing(Selement::name);
        return comps.toString("", s -> s.name().replace("Comp", ""));
    }

    <T extends Annotation> Seq<Stype> types(T t, Cons<T> consumer){
        try{
            consumer.get(t);
        }catch(MirroredTypesException e){
            return Seq.with(e.getTypeMirrors()).map(Stype::of);
        }
        throw new IllegalArgumentException("Missing types.");
    }

    void skipDeprecated(TypeSpec.Builder builder){
        //deprecations are irrelevant in generated code
        builder.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "\"deprecation\"").build());
    }

    class GroupDefinition{
        final String name;
        final ClassName baseType;
        final Seq<Stype> components;
        final boolean spatial, mapping, collides;
        final ObjectSet<Selement> manualInclusions = new ObjectSet<>();

        public GroupDefinition(String name, ClassName bestType, Seq<Stype> components, boolean spatial, boolean mapping, boolean collides){
            this.baseType = bestType;
            this.components = components;
            this.name = name;
            this.spatial = spatial;
            this.mapping = mapping;
            this.collides = collides;
        }

        @Override
        public String toString(){
            return name;
        }
    }

    class EntityDefinition{
        final Seq<GroupDefinition> groups;
        final Seq<Stype> components;
        final Seq<FieldSpec> fieldSpecs;
        final TypeSpec.Builder builder;
        final Selement naming;
        final String name;
        final @Nullable TypeName extend;
        final boolean legacy;
        int classID;

        public EntityDefinition(String name, Builder builder, Selement naming, TypeName extend, Seq<Stype> components, Seq<GroupDefinition> groups, Seq<FieldSpec> fieldSpec, boolean legacy){
            this.builder = builder;
            this.name = name;
            this.naming = naming;
            this.groups = groups;
            this.components = components;
            this.extend = extend;
            this.fieldSpecs = fieldSpec;
            this.legacy = legacy;
        }

        @Override
        public String toString(){
            return "Definition{" +
            "groups=" + groups +
            "components=" + components +
            ", base=" + naming +
            '}';
        }
    }
}
