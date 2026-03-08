package io.quarkus.deployment.pkg.steps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;

import org.jboss.jandex.DotName;
import org.jboss.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.MainClassBuildItem;
import io.quarkus.deployment.builditem.RemovedResourceBuildItem;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.UberJarPurgeBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependency;

public class PurgeProcessor {

    private static final Logger log = Logger.getLogger(PurgeProcessor.class);
    private static final String SERVICE_LOADER_INTERNAL = "java/util/ServiceLoader";

    @BuildStep
    void analyzeReachableClasses(
            PackageConfig packageConfig,
            CurateOutcomeBuildItem curateOutcome,
            MainClassBuildItem mainClass,
            List<GeneratedClassBuildItem> generatedClasses,
            List<BytecodeTransformerBuildItem> bytecodeTransformers,
            BuildProducer<UberJarPurgeBuildItem> purgeProducer,
            BuildProducer<RemovedResourceBuildItem> removedResourceProducer) {

        final PackageConfig.JarConfig.PurgeLevel purgeLevel = packageConfig.jar().purge();
        if (purgeLevel == PackageConfig.JarConfig.PurgeLevel.NONE) {
            purgeProducer.produce(new UberJarPurgeBuildItem(purgeLevel, Set.of()));
            return;
        }

        final ApplicationModel appModel = curateOutcome.getApplicationModel();

        // Build generated class bytecode map (internal name -> dot name conversion)
        final Map<DotName, byte[]> generatedBytecode = new HashMap<>();
        for (GeneratedClassBuildItem gen : generatedClasses) {
            generatedBytecode.put(DotName.createSimple(gen.getName().replace('/', '.')), gen.getClassData());
        }

        // Build class-to-dependency map and collect service provider/loader info
        // Read bytecode for all dependency classes for full method-body reference analysis
        final Map<DotName, ArtifactKey> classToDep = new HashMap<>();
        final Map<ArtifactKey, Integer> depClassCount = new HashMap<>();
        final Map<DotName, Set<DotName>> serviceProviders = new HashMap<>();
        final Map<DotName, Set<DotName>> serviceLoaderCalls = new HashMap<>();
        final Map<DotName, byte[]> depBytecode = new HashMap<>();
        for (ResolvedDependency dep : appModel.getRuntimeDependencies()) {
            final ArtifactKey key = dep.getKey();
            final int[] classCount = new int[1];
            dep.getContentTree().walk(visit -> {
                String relative = visit.getRelativePath("/");
                if (isClassEntry(relative)) {
                    DotName className = classNameOf(relative);
                    classToDep.put(className, key);
                    classCount[0]++;
                    try (InputStream is = Files.newInputStream(visit.getPath())) {
                        depBytecode.put(className, is.readAllBytes());
                    } catch (IOException e) {
                        log.debugf(e, "Failed to read bytecode: %s", visit.getPath());
                    }
                    detectServiceLoaderCalls(visit.getPath(), className, serviceLoaderCalls);
                }
                if (relative.startsWith("META-INF/services/") && !relative.endsWith("/")) {
                    parseServiceFile(visit.getPath(), relative, serviceProviders);
                }
            });
            depClassCount.put(key, classCount[0]);
        }

        // Collect service providers and ServiceLoader calls from the app artifact
        appModel.getAppArtifact().getContentTree().walk(visit -> {
            String relative = visit.getRelativePath("/");
            if (isClassEntry(relative)) {
                detectServiceLoaderCalls(visit.getPath(), classNameOf(relative), serviceLoaderCalls);
            }
            if (relative.startsWith("META-INF/services/") && !relative.endsWith("/")) {
                parseServiceFile(visit.getPath(), relative, serviceProviders);
            }
        });

        // Detect ServiceLoader calls in generated classes
        for (var entry : generatedBytecode.entrySet()) {
            detectServiceLoaderCalls(entry.getValue(), entry.getKey(), serviceLoaderCalls);
        }

        // Use main class + all generated classes as roots
        // Also add all classes from quarkus-bootstrap-runner as roots (fast-jar runtime infrastructure)
        final Set<DotName> roots = new HashSet<>();
        roots.add(DotName.createSimple(mainClass.getClassName()));
        roots.addAll(generatedBytecode.keySet());
        for (ResolvedDependency dep : appModel.getRuntimeDependencies()) {
            if ("quarkus-bootstrap-runner".equals(dep.getArtifactId())) {
                dep.getContentTree().walk(visit -> {
                    String relative = visit.getRelativePath("/");
                    if (isClassEntry(relative)) {
                        roots.add(classNameOf(relative));
                    }
                });
                break;
            }
        }

        // Add service providers for JDK service interfaces as roots, since JDK code
        // isn't analyzed and its ServiceLoader.load() calls can't be traced.
        // Non-JDK service providers are discovered through ServiceLoader.load() call
        // tracing in reachable dependency/app code.
        for (Map.Entry<DotName, Set<DotName>> entry : serviceProviders.entrySet()) {
            DotName serviceInterface = entry.getKey();
            if (!classToDep.containsKey(serviceInterface) && !generatedBytecode.containsKey(serviceInterface)) {
                // Interface not in any dependency or generated code — it's a JDK class
                roots.addAll(entry.getValue());
            }
        }

        // Classes targeted by bytecode transformations are clearly in use.
        // Add them as roots so they (and their references) are traced.
        for (BytecodeTransformerBuildItem transformer : bytecodeTransformers) {
            roots.add(DotName.createSimple(transformer.getClassToTransform()));
        }

        // Scan generated classes for LDC string constants that match known dependency class names.
        // Generated recorder bytecode often passes class names as strings to methods like
        // factory(className) that eventually call Class.forName() at runtime.
        final Set<DotName> allKnownClasses = new HashSet<>(classToDep.keySet());
        allKnownClasses.addAll(generatedBytecode.keySet());
        for (byte[] bytecode : generatedBytecode.values()) {
            Set<DotName> stringClassRefs = extractStringClassReferences(bytecode, allKnownClasses);
            for (DotName ref : stringClassRefs) {
                if (roots.add(ref)) {
                    log.debugf("Purge: string constant class reference from generated code: %s", ref);
                }
            }
        }

        // Trace all reachable classes using bytecode analysis for full method-body coverage
        final Set<DotName> reachable = traceReachableClasses(roots, generatedBytecode, depBytecode,
                serviceProviders, serviceLoaderCalls);

        // Determine which dependencies have reachable classes and build removal sets
        final Map<ArtifactKey, Integer> depReachableCount = new HashMap<>();
        for (DotName className : reachable) {
            ArtifactKey dep = classToDep.get(className);
            if (dep != null) {
                depReachableCount.merge(dep, 1, Integer::sum);
            }
        }

        final Set<ArtifactKey> usedDeps = new HashSet<>();
        final Map<ArtifactKey, int[]> usedDepsReport = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        int unusedCount = 0;
        for (ResolvedDependency dep : appModel.getRuntimeDependencies()) {
            final ArtifactKey key = dep.getKey();
            int reached = depReachableCount.getOrDefault(key, 0);
            if (reached > 0) {
                usedDeps.add(key);
                usedDepsReport.put(key, new int[] { reached, depClassCount.getOrDefault(key, 0) });
            } else {
                unusedCount++;
            }
        }

        // Report
        log.info("============================================================");
        log.info("  Quarkus Purge: Dependency Usage Analysis");
        log.info("============================================================");
        log.infof("  Total reachable classes: %d", reachable.size());
        log.infof("  Used dependencies     : %d", usedDeps.size());
        log.infof("  Unused dependencies   : %d", unusedCount);
        log.info("------------------------------------------------------------");
        log.info("  USED DEPENDENCIES:");
        for (var entry : usedDepsReport.entrySet()) {
            int[] counts = entry.getValue();
            log.infof("    - %s  (%d / %d classes reachable)",
                    entry.getKey().toGacString(), counts[0], counts[1]);
        }
        log.info("============================================================");

        // For CLASSES level, produce RemovedResourceBuildItem entries for unreachable classes.
        // This leverages existing infrastructure: ClassTransformingBuildStep converts these
        // into TransformedClass entries with null data, which jar builders already handle.
        if (purgeLevel == PackageConfig.JarConfig.PurgeLevel.CLASSES) {
            // Group unreachable classes by dependency
            final Map<ArtifactKey, Set<String>> unreachableByDep = new HashMap<>();
            for (Map.Entry<DotName, ArtifactKey> entry : classToDep.entrySet()) {
                DotName className = entry.getKey();
                if (!reachable.contains(className)) {
                    // Keep inner classes if the outer class is reachable
                    String name = className.toString();
                    int dollarIdx = name.indexOf('$');
                    if (dollarIdx >= 0 && reachable.contains(DotName.createSimple(name.substring(0, dollarIdx)))) {
                        continue;
                    }
                    // Convert dot-separated class name to resource path
                    String resourcePath = name.replace('.', '/') + ".class";
                    unreachableByDep.computeIfAbsent(entry.getValue(), k -> new HashSet<>())
                            .add(resourcePath);
                }
            }
            for (Map.Entry<ArtifactKey, Set<String>> entry : unreachableByDep.entrySet()) {
                log.debugf("Purge: removing %d unreachable classes from %s",
                        entry.getValue().size(), entry.getKey());
                removedResourceProducer.produce(new RemovedResourceBuildItem(entry.getKey(), entry.getValue()));
            }
        }

        purgeProducer.produce(new UberJarPurgeBuildItem(purgeLevel, usedDeps));
    }

    // ---- Reachability tracing ----

    private Set<DotName> traceReachableClasses(Set<DotName> roots,
            Map<DotName, byte[]> generatedBytecode,
            Map<DotName, byte[]> depBytecode,
            Map<DotName, Set<DotName>> serviceProviders,
            Map<DotName, Set<DotName>> serviceLoaderCalls) {
        final Set<DotName> visited = new HashSet<>(roots);
        final Queue<DotName> queue = new ArrayDeque<>(roots);

        while (!queue.isEmpty()) {
            DotName name = queue.poll();

            // If this class calls ServiceLoader.load(), include providers
            Set<DotName> loadedServices = serviceLoaderCalls.get(name);
            if (loadedServices != null) {
                for (DotName serviceInterface : loadedServices) {
                    Set<DotName> providers = serviceProviders.get(serviceInterface);
                    if (providers != null) {
                        for (DotName provider : providers) {
                            if (visited.add(provider)) {
                                queue.add(provider);
                            }
                        }
                    }
                }
            }

            // Include JBoss Logging companion classes (_$logger, _$bundle)
            // These are loaded reflectively via name concatenation in Logger.getMessageLogger()
            for (String suffix : new String[] { "_$logger", "_$bundle" }) {
                DotName companion = DotName.createSimple(name.toString() + suffix);
                if (depBytecode.containsKey(companion) && visited.add(companion)) {
                    queue.add(companion);
                }
            }

            // Look up bytecode: generated classes first, then dependency classes
            byte[] bytecode = generatedBytecode.get(name);
            if (bytecode == null) {
                bytecode = depBytecode.get(name);
            }
            if (bytecode == null) {
                continue; // JDK class or not available
            }
            Set<DotName> refs = extractReferencesFromBytecode(bytecode);
            for (DotName ref : refs) {
                if (ref != null && visited.add(ref)) {
                    queue.add(ref);
                }
            }
        }
        return visited;
    }

    // ---- Bytecode analysis ----

    private Set<DotName> extractReferencesFromBytecode(byte[] bytecode) {
        final Set<DotName> refs = new HashSet<>();
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                if (superName != null) {
                    refs.add(internalToDotName(superName));
                }
                if (interfaces != null) {
                    for (String iface : interfaces) {
                        refs.add(internalToDotName(iface));
                    }
                }
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                addDescriptorType(descriptor, refs);
                return null;
            }

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                    String descriptor, String signature, Object value) {
                addDescriptorType(descriptor, refs);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                addMethodDescriptorTypes(descriptor, refs);
                if (exceptions != null) {
                    for (String ex : exceptions) {
                        refs.add(internalToDotName(ex));
                    }
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private String lastStringConstant;

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        lastStringConstant = null;
                        refs.add(internalToDotName(type));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdescriptor) {
                        lastStringConstant = null;
                        refs.add(internalToDotName(owner));
                        addDescriptorType(fdescriptor, refs);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                            String mdescriptor, boolean isInterface) {
                        // Track Class.forName(String) and ClassLoader.loadClass(String)
                        if (lastStringConstant != null) {
                            if (("java/lang/Class".equals(owner) && "forName".equals(mname))
                                    || "loadClass".equals(mname)) {
                                refs.add(DotName.createSimple(lastStringConstant));
                            }
                        }
                        lastStringConstant = null;
                        refs.add(internalToDotName(owner));
                        addMethodDescriptorTypes(mdescriptor, refs);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            lastStringConstant = (String) value;
                        } else {
                            lastStringConstant = null;
                        }
                        if (value instanceof org.objectweb.asm.Type) {
                            org.objectweb.asm.Type type = (org.objectweb.asm.Type) value;
                            if (type.getSort() == org.objectweb.asm.Type.OBJECT) {
                                refs.add(internalToDotName(type.getInternalName()));
                            } else if (type.getSort() == org.objectweb.asm.Type.ARRAY) {
                                org.objectweb.asm.Type elem = type.getElementType();
                                if (elem.getSort() == org.objectweb.asm.Type.OBJECT) {
                                    refs.add(internalToDotName(elem.getInternalName()));
                                }
                            }
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        // Don't reset on ICONST_0/ICONST_1 - often boolean arg
                        // between LDC string and Class.forName(String, boolean, ClassLoader)
                        if (opcode != Opcodes.ICONST_0 && opcode != Opcodes.ICONST_1) {
                            lastStringConstant = null;
                        }
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        // Don't reset - BIPUSH/SIPUSH could be between LDC and Class.forName
                    }

                    @Override
                    public void visitVarInsn(int opcode, int varIndex) {
                        // Don't reset on ALOAD - ClassLoader arg between LDC and Class.forName
                        if (opcode != Opcodes.ALOAD) {
                            lastStringConstant = null;
                        }
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        lastStringConstant = null;
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String iname, String idescriptor,
                            Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                        lastStringConstant = null;
                        // Extract class references from bootstrap method handle and arguments
                        addHandleType(bootstrapMethodHandle, refs);
                        if (bootstrapMethodArguments != null) {
                            for (Object arg : bootstrapMethodArguments) {
                                if (arg instanceof org.objectweb.asm.Type) {
                                    addAsmType((org.objectweb.asm.Type) arg, refs);
                                } else if (arg instanceof Handle) {
                                    addHandleType((Handle) arg, refs);
                                }
                            }
                        }
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                        lastStringConstant = null;
                        addDescriptorType(descriptor, refs);
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        lastStringConstant = null;
                        addDescriptorType(descriptor, refs);
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return refs;
    }

    /**
     * Extracts LDC string constants from bytecode that match known class names.
     * This catches class names passed as string arguments to methods that eventually
     * call Class.forName() at runtime (e.g. Quarkus recorder generated code).
     */
    private Set<DotName> extractStringClassReferences(byte[] bytecode, Set<DotName> knownClasses) {
        final Set<DotName> refs = new HashSet<>();
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String str) {
                            DotName dotName = DotName.createSimple(str);
                            if (knownClasses.contains(dotName)) {
                                refs.add(dotName);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return refs;
    }

    private void detectServiceLoaderCalls(Path classFile, DotName className,
            Map<DotName, Set<DotName>> serviceLoaderCalls) {
        try (InputStream is = Files.newInputStream(classFile)) {
            detectServiceLoaderCalls(is.readAllBytes(), className, serviceLoaderCalls);
        } catch (IOException e) {
            log.debugf(e, "Failed to scan bytecode: %s", classFile);
        }
    }

    private void detectServiceLoaderCalls(byte[] bytecode, DotName className,
            Map<DotName, Set<DotName>> serviceLoaderCalls) {
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    private org.objectweb.asm.Type lastClassConstant;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof org.objectweb.asm.Type) {
                            org.objectweb.asm.Type type = (org.objectweb.asm.Type) value;
                            if (type.getSort() == org.objectweb.asm.Type.OBJECT) {
                                lastClassConstant = type;
                                return;
                            }
                        }
                        lastClassConstant = null;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                            String mdescriptor, boolean isInterface) {
                        if (SERVICE_LOADER_INTERNAL.equals(owner) && "load".equals(mname)
                                && lastClassConstant != null) {
                            serviceLoaderCalls
                                    .computeIfAbsent(className, k -> new HashSet<>())
                                    .add(internalToDotName(lastClassConstant.getInternalName()));
                        }
                        lastClassConstant = null;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        lastClassConstant = null;
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        lastClassConstant = null;
                    }

                    @Override
                    public void visitVarInsn(int opcode, int varIndex) {
                        if (opcode != Opcodes.ALOAD) {
                            lastClassConstant = null;
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdescriptor) {
                        lastClassConstant = null;
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        lastClassConstant = null;
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        lastClassConstant = null;
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String iname, String idescriptor,
                            Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                        lastClassConstant = null;
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                        lastClassConstant = null;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    }

    // ---- Helpers ----

    private static DotName internalToDotName(String internalName) {
        return DotName.createSimple(internalName.replace('/', '.'));
    }

    private static void addDescriptorType(String descriptor, Set<DotName> refs) {
        org.objectweb.asm.Type type = org.objectweb.asm.Type.getType(descriptor);
        addAsmType(type, refs);
    }

    private static void addMethodDescriptorTypes(String descriptor, Set<DotName> refs) {
        for (org.objectweb.asm.Type argType : org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
            addAsmType(argType, refs);
        }
        addAsmType(org.objectweb.asm.Type.getReturnType(descriptor), refs);
    }

    private static void addHandleType(Handle handle, Set<DotName> refs) {
        refs.add(internalToDotName(handle.getOwner()));
        int tag = handle.getTag();
        if (tag == Opcodes.H_GETFIELD || tag == Opcodes.H_GETSTATIC
                || tag == Opcodes.H_PUTFIELD || tag == Opcodes.H_PUTSTATIC) {
            addDescriptorType(handle.getDesc(), refs);
        } else {
            addMethodDescriptorTypes(handle.getDesc(), refs);
        }
    }

    private static void addAsmType(org.objectweb.asm.Type type, Set<DotName> refs) {
        if (type.getSort() == org.objectweb.asm.Type.OBJECT) {
            refs.add(internalToDotName(type.getInternalName()));
        } else if (type.getSort() == org.objectweb.asm.Type.ARRAY) {
            addAsmType(type.getElementType(), refs);
        }
    }

    private static boolean isClassEntry(String relativePath) {
        return relativePath.endsWith(".class")
                && !relativePath.equals("module-info.class")
                && !relativePath.endsWith("package-info.class");
    }

    private static DotName classNameOf(String relativePath) {
        return DotName.createSimple(
                relativePath.substring(0, relativePath.length() - 6).replace('/', '.'));
    }

    private void parseServiceFile(Path file, String relativePath,
            Map<DotName, Set<DotName>> serviceProviders) {
        String serviceInterface = relativePath.substring("META-INF/services/".length());
        if (serviceInterface.isEmpty() || serviceInterface.contains("/")) {
            return;
        }
        DotName serviceKey = DotName.createSimple(serviceInterface);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int commentIdx = line.indexOf('#');
                if (commentIdx >= 0) {
                    line = line.substring(0, commentIdx);
                }
                line = line.trim();
                if (!line.isEmpty()) {
                    serviceProviders.computeIfAbsent(serviceKey, k -> new HashSet<>())
                            .add(DotName.createSimple(line));
                }
            }
        } catch (IOException e) {
            log.debugf(e, "Failed to read service file: %s", file);
        }
    }
}
