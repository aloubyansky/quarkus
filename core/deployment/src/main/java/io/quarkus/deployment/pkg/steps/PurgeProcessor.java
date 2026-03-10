package io.quarkus.deployment.pkg.steps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.MainClassBuildItem;
import io.quarkus.deployment.builditem.TransformedClassesBuildItem;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.UberJarPurgeBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependency;

public class PurgeProcessor {

    private static final Logger log = Logger.getLogger(PurgeProcessor.class);
    private static final String SERVICE_LOADER_INTERNAL = "java/util/ServiceLoader";
    private static final String SISU_NAMED_RESOURCE = "META-INF/sisu/javax.inject.Named";

    @BuildStep
    void analyzeReachableClasses(
            PackageConfig packageConfig,
            CurateOutcomeBuildItem curateOutcome,
            MainClassBuildItem mainClass,
            List<GeneratedClassBuildItem> generatedClasses,
            TransformedClassesBuildItem transformedClasses,
            BuildProducer<UberJarPurgeBuildItem> purgeProducer) {

        final PackageConfig.JarConfig.PurgeLevel purgeLevel = packageConfig.jar().purge();
        // Disable purge for mutable jars: re-augmentation loads deployment classes that
        // reference runtime classes, so we can't safely remove any runtime classes.
        if (purgeLevel == PackageConfig.JarConfig.PurgeLevel.NONE
                || packageConfig.jar().type() == PackageConfig.JarConfig.JarType.MUTABLE_JAR) {
            purgeProducer
                    .produce(new UberJarPurgeBuildItem(PackageConfig.JarConfig.PurgeLevel.NONE, Set.of(), Set.of(), Map.of()));
            return;
        }

        final ApplicationModel appModel = curateOutcome.getApplicationModel();

        // Build generated class bytecode map (internal name -> dot name conversion)
        final Map<DotName, byte[]> generatedBytecode = new HashMap<>();
        for (GeneratedClassBuildItem gen : generatedClasses) {
            generatedBytecode.put(DotName.createSimple(gen.getName().replace('/', '.')), gen.getClassData());
        }

        // Detect the application's minimum Java version from bytecode.
        // Multi-release JAR entries for versions <= this are resolved (highest wins),
        // entries for versions > this are ignored (won't be loaded at runtime).
        final int appJavaVersion = detectAppJavaVersion(appModel);

        // Build class-to-dependency map and collect service provider/loader info
        // Read bytecode for all dependency classes for full method-body reference analysis
        final Map<DotName, ArtifactKey> classToDep = new HashMap<>();
        final Map<ArtifactKey, Integer> depClassCount = new HashMap<>();
        final Map<DotName, Set<DotName>> serviceProviders = new HashMap<>();
        final Map<DotName, Set<DotName>> serviceLoaderCalls = new HashMap<>();
        final Set<DotName> sisuNamedClasses = new HashSet<>();
        final Map<DotName, byte[]> depBytecode = new HashMap<>();
        // Track which multi-release version is currently stored per class
        // (0 = base, N = META-INF/versions/N/)
        final Map<DotName, Integer> depBytecodeVersion = new HashMap<>();
        for (ResolvedDependency dep : appModel.getRuntimeDependencies()) {
            final ArtifactKey key = dep.getKey();
            final int[] classCount = new int[1];
            // Use walkRaw to get all entries including multi-release versions.
            // For multi-release entries, we pick the highest version <= appJavaVersion,
            // matching what JarFile.runtimeVersion() resolves at runtime.
            dep.getContentTree().walkRaw(visit -> {
                String relative = visit.getRelativePath("/");
                // Handle multi-release version entries (META-INF/versions/N/...)
                if (relative.startsWith("META-INF/versions/")) {
                    String afterVersions = relative.substring("META-INF/versions/".length());
                    int slash = afterVersions.indexOf('/');
                    if (slash > 0) {
                        int version;
                        try {
                            version = Integer.parseInt(afterVersions.substring(0, slash));
                        } catch (NumberFormatException e) {
                            return;
                        }
                        // Skip versions newer than the app's target — they won't be loaded at runtime
                        if (version > appJavaVersion) {
                            return;
                        }
                        String classPath = afterVersions.substring(slash + 1);
                        if (isClassEntry(classPath)) {
                            DotName className = classNameOf(classPath);
                            // Replace existing bytecode only if this version is higher
                            // (higher version = closer to runtime resolution)
                            int currentVersion = depBytecodeVersion.getOrDefault(className, 0);
                            if (version > currentVersion) {
                                classToDep.put(className, key);
                                if (currentVersion == 0 && !depBytecode.containsKey(className)) {
                                    classCount[0]++;
                                }
                                try (InputStream is = Files.newInputStream(visit.getPath())) {
                                    depBytecode.put(className, is.readAllBytes());
                                    depBytecodeVersion.put(className, version);
                                } catch (IOException e) {
                                    log.debugf(e, "Failed to read bytecode: %s", visit.getPath());
                                }
                            }
                        }
                    }
                    return;
                }
                if (isClassEntry(relative)) {
                    DotName className = classNameOf(relative);
                    classToDep.put(className, key);
                    classCount[0]++;
                    // Base class: only store if no versioned entry was seen yet
                    if (!depBytecodeVersion.containsKey(className)) {
                        try (InputStream is = Files.newInputStream(visit.getPath())) {
                            depBytecode.put(className, is.readAllBytes());
                            depBytecodeVersion.put(className, 0);
                        } catch (IOException e) {
                            log.debugf(e, "Failed to read bytecode: %s", visit.getPath());
                        }
                    }
                    detectServiceLoaderCalls(visit.getPath(), className, serviceLoaderCalls);
                }
                if (relative.startsWith("META-INF/services/") && !relative.endsWith("/")) {
                    parseServiceFile(visit.getPath(), relative, serviceProviders);
                }
                // Collect sisu named components (META-INF/sisu/javax.inject.Named).
                // These are only included if ClassLoader.getResources() for this path is detected.
                if (SISU_NAMED_RESOURCE.equals(relative)) {
                    parseSisuNamedFile(visit.getPath(), sisuNamedClasses);
                }
            });
            depClassCount.put(key, classCount[0]);
        }

        // Override dependency bytecode with transformed bytecode where available.
        // Transformations can add interfaces, annotations, or other references
        // that aren't in the original bytecode (e.g. NettySharable marker interface).
        for (Set<TransformedClassesBuildItem.TransformedClass> transformedSet : transformedClasses
                .getTransformedClassesByJar().values()) {
            for (TransformedClassesBuildItem.TransformedClass tc : transformedSet) {
                if (tc.getData() != null) {
                    String fileName = tc.getFileName();
                    if (fileName.endsWith(".class") && !fileName.equals("module-info.class")) {
                        DotName className = DotName
                                .createSimple(fileName.substring(0, fileName.length() - 6).replace('/', '.'));
                        depBytecode.put(className, tc.getData());
                    }
                }
            }
        }

        // Collect service providers, ServiceLoader calls, and bytecode from the app artifact.
        // App bytecode is needed so the reachability trace can follow references from app classes
        // to dependency classes (e.g. app code using commons-io IOUtils).
        final Map<DotName, byte[]> appBytecode = new HashMap<>();
        appModel.getAppArtifact().getContentTree().walk(visit -> {
            String relative = visit.getRelativePath("/");
            if (isClassEntry(relative)) {
                DotName className = classNameOf(relative);
                detectServiceLoaderCalls(visit.getPath(), className, serviceLoaderCalls);
                try (InputStream is = Files.newInputStream(visit.getPath())) {
                    appBytecode.put(className, is.readAllBytes());
                } catch (IOException e) {
                    log.debugf(e, "Failed to read app bytecode: %s", visit.getPath());
                }
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
        final Set<DotName> reachable = traceReachableClasses(roots, generatedBytecode, appBytecode, depBytecode,
                serviceProviders, serviceLoaderCalls, sisuNamedClasses, allKnownClasses, classToDep);

        // Determine which dependencies have reachable classes
        final Set<String> reachableClassNames = new HashSet<>();
        for (DotName name : reachable) {
            reachableClassNames.add(name.toString());
        }

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

        // Compute removal stats and per-dependency removed class lists
        int totalDepClasses = depBytecode.size();
        int removedClassCount = 0;
        long removedBytes = 0;
        final Map<ArtifactKey, List<String>> removedClassesPerDep = new HashMap<>();
        for (var entry : depBytecode.entrySet()) {
            if (!reachable.contains(entry.getKey())) {
                removedClassCount++;
                removedBytes += entry.getValue().length;
                ArtifactKey dep = classToDep.get(entry.getKey());
                if (dep != null) {
                    removedClassesPerDep.computeIfAbsent(dep, k -> new ArrayList<>())
                            .add(entry.getKey().toString().replace('.', '/') + ".class");
                }
            }
        }
        // Sort each list for stable pedigree output
        for (List<String> list : removedClassesPerDep.values()) {
            Collections.sort(list);
        }

        // Report
        log.info("============================================================");
        log.info("  Quarkus Purge: Dependency Usage Analysis");
        log.info("============================================================");
        log.infof("  Total dependency classes: %d", totalDepClasses);
        log.infof("  Reachable classes       : %d", reachable.size());
        log.infof("  Removed classes         : %d  (%.1f%% of dependency classes, %s)",
                removedClassCount,
                totalDepClasses > 0 ? (removedClassCount * 100.0 / totalDepClasses) : 0.0,
                formatSize(removedBytes));
        log.infof("  Used dependencies       : %d", usedDeps.size());
        log.infof("  Unused dependencies     : %d", unusedCount);
        log.info("------------------------------------------------------------");
        log.info("  DEPENDENCIES WITH REMOVED CLASSES:");
        for (var entry : usedDepsReport.entrySet()) {
            int[] counts = entry.getValue();
            if (counts[0] < counts[1]) {
                log.infof("    - %s  (%d / %d classes removed)",
                        entry.getKey().toGacString(), counts[1] - counts[0], counts[1]);
            }
        }
        log.info("============================================================");

        purgeProducer.produce(new UberJarPurgeBuildItem(purgeLevel, reachableClassNames, usedDeps, removedClassesPerDep));
    }

    // ---- Reachability tracing ----

    private Set<DotName> traceReachableClasses(Set<DotName> roots,
            Map<DotName, byte[]> generatedBytecode,
            Map<DotName, byte[]> appBytecode,
            Map<DotName, byte[]> depBytecode,
            Map<DotName, Set<DotName>> serviceProviders,
            Map<DotName, Set<DotName>> serviceLoaderCalls,
            Set<DotName> sisuNamedClasses,
            Set<DotName> allKnownClasses,
            Map<DotName, ArtifactKey> classToDep) {
        final Set<DotName> visited = new HashSet<>(roots);
        final Queue<DotName> queue = new ArrayDeque<>(roots);
        boolean sisuActivated = false;

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

            // If this class is a service interface, include all its providers.
            // This handles indirect ServiceLoader.load() calls through helper methods
            // (e.g. jakarta.ws.rs FactoryFinder) that the direct call tracing can't detect.
            Set<DotName> providers = serviceProviders.get(name);
            if (providers != null) {
                for (DotName provider : providers) {
                    if (visited.add(provider)) {
                        queue.add(provider);
                    }
                }
            }

            // Look up bytecode: generated classes first, then app classes, then dependency classes
            byte[] bytecode = generatedBytecode.get(name);
            if (bytecode == null) {
                bytecode = appBytecode.get(name);
            }
            if (bytecode == null) {
                bytecode = depBytecode.get(name);
            }
            if (bytecode == null) {
                continue; // JDK class or not available
            }

            // Include sisu named classes when a reachable class loads the sisu resource
            // via ClassLoader.getResources("META-INF/sisu/javax.inject.Named")
            if (!sisuActivated && !sisuNamedClasses.isEmpty() && detectsGetResourcesForSisu(bytecode)) {
                sisuActivated = true;
                for (DotName sisuClass : sisuNamedClasses) {
                    if (visited.add(sisuClass)) {
                        queue.add(sisuClass);
                    }
                }
            }

            Set<DotName> refs = extractReferencesFromBytecode(bytecode);
            for (DotName ref : refs) {
                if (ref != null && visited.add(ref)) {
                    queue.add(ref);
                }
            }
            // Also check string constants in dependency/app bytecode for class names
            // passed as strings to methods like Class.forName wrappers
            if (!generatedBytecode.containsKey(name)) {
                Set<DotName> stringRefs = extractStringClassReferences(bytecode, allKnownClasses);
                for (DotName ref : stringRefs) {
                    if (visited.add(ref)) {
                        queue.add(ref);
                    }
                }
            }

            // If this class uses dynamic class loading (MethodHandles.Lookup.findClass),
            // include all classes from the same dependency since the loaded class names
            // are constructed at runtime and can't be statically determined.
            ArtifactKey depKey = classToDep.get(name);
            if (depKey != null && usesDynamicClassLoading(bytecode)) {
                log.debugf("Purge: dynamic class loading detected in %s, keeping all classes from %s", name, depKey);
                for (var entry : classToDep.entrySet()) {
                    if (depKey.equals(entry.getValue()) && visited.add(entry.getKey())) {
                        queue.add(entry.getKey());
                    }
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
                addSignatureTypes(signature, refs);
                // Inner classes must reference their outer class because the JVM
                // resolves it at runtime via Class.getDeclaringClass0()
                int dollar = name.lastIndexOf('$');
                if (dollar > 0) {
                    refs.add(internalToDotName(name.substring(0, dollar)));
                }
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                addDescriptorType(descriptor, refs);
                return createAnnotationRefVisitor(refs);
            }

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                    String descriptor, String signature, Object value) {
                addDescriptorType(descriptor, refs);
                addSignatureTypes(signature, refs);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                addMethodDescriptorTypes(descriptor, refs);
                addSignatureTypes(signature, refs);
                if (exceptions != null) {
                    for (String ex : exceptions) {
                        refs.add(internalToDotName(ex));
                    }
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private String lastStringConstant;

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitAnnotationDefault() {
                        // Captures default values of annotation methods (e.g. @Reflective(processors = SimpleReflectiveProcessor.class))
                        return createAnnotationRefVisitor(refs);
                    }

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
                        return createAnnotationRefVisitor(refs);
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitParameterAnnotation(int parameter,
                            String descriptor, boolean visible) {
                        lastStringConstant = null;
                        addDescriptorType(descriptor, refs);
                        return createAnnotationRefVisitor(refs);
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
     * Also handles delimited class name lists (comma, colon) used by frameworks
     * like RESTEasy for provider and resource builder lists.
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
                            matchClassNames(str, knownClasses, refs);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return refs;
    }

    private static void matchClassNames(String str, Set<DotName> knownClasses, Set<DotName> refs) {
        if (str.indexOf(',') >= 0 || str.indexOf(':') >= 0) {
            for (String part : str.split("[,:]")) {
                DotName dotName = DotName.createSimple(part);
                if (knownClasses.contains(dotName)) {
                    refs.add(dotName);
                }
            }
        } else {
            DotName dotName = DotName.createSimple(str);
            if (knownClasses.contains(dotName)) {
                refs.add(dotName);
            }
        }
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

    /**
     * Creates an AnnotationVisitor that extracts class references from annotation values.
     * Handles class literals (e.g. {@code @Command(subcommands = {Foo.class})}),
     * enum constants, nested annotations, and arrays of these.
     */
    private static org.objectweb.asm.AnnotationVisitor createAnnotationRefVisitor(Set<DotName> refs) {
        return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof org.objectweb.asm.Type) {
                    addAsmType((org.objectweb.asm.Type) value, refs);
                }
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                addDescriptorType(descriptor, refs);
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String name, String descriptor) {
                addDescriptorType(descriptor, refs);
                return this;
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
                return this;
            }
        };
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

    /**
     * Parses a generic signature and extracts all class type references.
     * This captures type arguments (e.g. {@code Map<String, ContainerConfig>})
     * that are not present in raw descriptors but can be resolved at runtime
     * via reflection on generic type metadata.
     */
    private static void addSignatureTypes(String signature, Set<DotName> refs) {
        if (signature == null) {
            return;
        }
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) {
                refs.add(internalToDotName(name));
            }
        });
    }

    private static void addAsmType(org.objectweb.asm.Type type, Set<DotName> refs) {
        if (type.getSort() == org.objectweb.asm.Type.OBJECT) {
            refs.add(internalToDotName(type.getInternalName()));
        } else if (type.getSort() == org.objectweb.asm.Type.ARRAY) {
            addAsmType(type.getElementType(), refs);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
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

    /**
     * Detects whether bytecode uses dynamic class loading patterns where class names
     * are constructed at runtime (e.g. MethodHandles.Lookup.findClass). When detected,
     * all classes from the same dependency must be preserved since the loaded class names
     * can't be statically determined.
     */
    private static boolean usesDynamicClassLoading(byte[] bytecode) {
        boolean[] found = new boolean[1];
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (found[0]) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                            String mdescriptor, boolean isInterface) {
                        if ("findClass".equals(mname)
                                && "java/lang/invoke/MethodHandles$Lookup".equals(owner)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return found[0];
    }

    private void parseSisuNamedFile(Path file, Set<DotName> sisuNamedClasses) {
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
                    sisuNamedClasses.add(DotName.createSimple(line));
                }
            }
        } catch (IOException e) {
            log.debugf(e, "Failed to read sisu named file: %s", file);
        }
    }

    /**
     * Checks whether bytecode contains both an LDC string constant for the sisu named resource path
     * and a ClassLoader.getResources()/getResource() call. These may be in different methods
     * (e.g. the string is passed to a helper that calls getResources in a lambda), so we check
     * for both independently within the same class.
     */
    private static boolean detectsGetResourcesForSisu(byte[] bytecode) {
        boolean[] hasSisuString = new boolean[1];
        boolean[] hasGetResources = new boolean[1];
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (hasSisuString[0] && hasGetResources[0]) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (SISU_NAMED_RESOURCE.equals(value)) {
                            hasSisuString[0] = true;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                            String mdescriptor, boolean isInterface) {
                        if ("getResources".equals(mname) || "getResource".equals(mname)) {
                            hasGetResources[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return hasSisuString[0] && hasGetResources[0];
    }

    /**
     * Detects the Java feature version from the application's class bytecode.
     * The class file major version maps to Java versions: 52=8, 55=11, 61=17, 65=21.
     * Falls back to the current JVM's feature version if no app classes are found.
     */
    private static int detectAppJavaVersion(ApplicationModel appModel) {
        int[] majorVersion = new int[1];
        appModel.getAppArtifact().getContentTree().walk(visit -> {
            if (majorVersion[0] > 0) {
                return;
            }
            String relative = visit.getRelativePath("/");
            if (isClassEntry(relative)) {
                try (InputStream is = Files.newInputStream(visit.getPath())) {
                    byte[] header = new byte[8];
                    if (is.read(header) == 8) {
                        // Class file: magic (4 bytes) + minor (2 bytes) + major (2 bytes)
                        majorVersion[0] = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                    }
                } catch (IOException e) {
                    // ignore, will try next class
                }
            }
        });
        // Convert class file major version to Java feature version (major - 44)
        // e.g., 61 -> Java 17, 65 -> Java 21
        int javaVersion = majorVersion[0] > 0 ? majorVersion[0] - 44 : Runtime.version().feature();
        log.debugf("Purge: detected app Java version: %d (class file major: %d)", javaVersion, majorVersion[0]);
        return javaVersion;
    }
}
