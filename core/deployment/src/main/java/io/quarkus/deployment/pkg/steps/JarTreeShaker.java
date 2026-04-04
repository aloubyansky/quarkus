package io.quarkus.deployment.pkg.steps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;

import org.jboss.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import io.quarkus.deployment.pkg.builditem.JarTreeShakeBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.paths.OpenPathTree;

class JarTreeShaker {

    private static final Logger log = Logger.getLogger(JarTreeShaker.class.getName());

    private static final String SERVICE_LOADER_INTERNAL = "java/util/ServiceLoader";
    private static final String SISU_NAMED_RESOURCE = "META-INF/sisu/javax.inject.Named";

    private final JarTreeShakerInput input;

    JarTreeShaker(JarTreeShakerInput input) {
        this.input = input;
    }

    /**
     * Runs the tree-shake analysis: traces reachable classes from roots via BFS,
     * evaluates conditional roots to a fixed point, computes removal stats,
     * and returns a {@link JarTreeShakeBuildItem} with the reachable set and per-dependency removals.
     */
    JarTreeShakeBuildItem run() {
        final long start = System.currentTimeMillis();

        // Trace all reachable classes using bytecode analysis for full method-body coverage
        Set<String> visited = new HashSet<>();
        Set<String> reachable = traceReachableClasses(input.roots, visited, input.allKnownClasses);

        // Evaluate conditional roots to fixed point
        reachable = evaluateConditionalRoots(reachable, input.allKnownClasses);

        // Analyze class-loading chains (fixed-point loop)
        reachable = analyzeClassLoadingChains(reachable);

        // Release cached bytecode — no longer needed after analysis
        input.clearBytecodeCache();

        // Compute removal stats and per-dependency removed class lists
        int totalDepClasses = input.depBytecode.size();
        int removedClassCount = 0;
        long removedBytes = 0;
        final Map<ArtifactKey, List<String>> removedClassesPerDep = new HashMap<>();
        for (var entry : input.depBytecode.entrySet()) {
            if (!reachable.contains(entry.getKey())) {
                removedClassCount++;
                removedBytes += input.depBytecodeSize.getOrDefault(entry.getKey(), 0L);
                ArtifactKey dep = input.classToDep.get(entry.getKey());
                if (dep != null) {
                    removedClassesPerDep.computeIfAbsent(dep, k -> new ArrayList<>())
                            .add(entry.getKey().replace('.', '/') + ".class");
                }
            }
        }
        // Sort each list for stable pedigree output
        for (List<String> list : removedClassesPerDep.values()) {
            Collections.sort(list);
        }

        // Report
        log.infof("Tree-shaking removed %d unreachable classes from %d dependencies, saving %s (%.1f%%)",
                removedClassCount,
                removedClassesPerDep.size(),
                formatSize(removedBytes),
                totalDepClasses > 0 ? (removedClassCount * 100.0 / totalDepClasses) : 0.0);
        log.infof("  Done in %dms", System.currentTimeMillis() - start);
        if (log.isDebugEnabled()) {
            int reachableClasses = totalDepClasses - removedClassCount;
            log.debug("============================================================");
            log.debug("  Dependency Classes Usage Analysis");
            log.debug("============================================================");
            log.debugf("  Total classes    : %d", totalDepClasses);
            log.debugf("  Reachable classes: %d", reachableClasses);
            log.debug("------------------------------------------------------------");
            log.debug("  DEPENDENCIES WITH REMOVED CLASSES:");
            removedClassesPerDep.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(ArtifactKey::toGacString)))
                    .forEach(e -> log.debugf("    - %s  (%d classes removed)",
                            e.getKey().toGacString(), e.getValue().size()));
            log.debug("------------------------------------------------------------");
            log.debugf("  Done in %dms", System.currentTimeMillis() - start);
            log.debug("============================================================");
        }

        return new JarTreeShakeBuildItem(input.treeShakeLevel, reachable, removedClassesPerDep);
    }

    /**
     * Runs class-loading chain analysis in a fixed-point loop, discovering classes
     * that are loaded dynamically via ClassLoader.loadClass() or Class.forName()
     * during static initialization or construction of reachable classes.
     */
    private Set<String> analyzeClassLoadingChains(Set<String> reachable) {
        // Build combined bytecode map. Order matters: app bytecode first, then dep bytecode
        // (which includes transformed versions), then generated. This ensures transformed
        // app class bytecode takes priority over the original, matching the lookup order
        // in lookupBytecode().
        Map<String, Supplier<byte[]>> allBytecode = new HashMap<>();
        allBytecode.putAll(input.appBytecode);
        allBytecode.putAll(input.depBytecode);
        allBytecode.putAll(input.generatedBytecode);

        // Build the transformed bytecode map once (optimization B)
        Map<String, Supplier<byte[]>> transformedBytecode = new HashMap<>();
        for (String name : input.transformedClassNames) {
            Supplier<byte[]> supplier = input.depBytecode.get(name);
            if (supplier != null) {
                transformedBytecode.put(name, supplier);
            }
        }

        ClassLoadingChainAnalyzer analyzer = new ClassLoadingChainAnalyzer(allBytecode, input.classToDep.keySet());
        Set<String> executedEntryPoints = new HashSet<>();
        Set<String> allPhase3Discovered = new HashSet<>();
        Set<String> classesToScan = new HashSet<>(reachable);

        while (true) {
            // Phases 1+2: scan only new classes, merge into existing call graph (optimization C)
            Set<String> entryPoints = analyzer.findEntryPoints(classesToScan);

            // Only execute entry points we haven't already executed (optimization A)
            Set<String> newEntryPoints = new HashSet<>(entryPoints);
            newEntryPoints.removeAll(executedEntryPoints);
            if (newEntryPoints.isEmpty()) {
                break;
            }
            executedEntryPoints.addAll(newEntryPoints);

            // Skip fork if all new entry points were already discovered by prior forks —
            // their transitive class loads were already captured
            if (allPhase3Discovered.containsAll(newEntryPoints)) {
                break;
            }

            // Phase 3: execute only new entry points in a forked JVM
            Set<String> discovered = ClassLoadingChainAnalyzer.executeEntryPoints(
                    newEntryPoints, input.generatedBytecode, transformedBytecode,
                    input.allKnownClasses, input.depJarPaths, input.appPaths);

            allPhase3Discovered.addAll(discovered);

            // Filter to known, non-reachable classes
            discovered.retainAll(input.allKnownClasses);
            discovered.removeAll(reachable);
            if (discovered.isEmpty()) {
                break;
            }
            log.infof("Class-loading chain analysis discovered %d additional classes", discovered.size());
            traceReachableClasses(discovered, reachable, input.allKnownClasses);
            evaluateConditionalRoots(reachable, input.allKnownClasses);
            classesToScan = discovered; // next iteration scans only new classes
        }
        return reachable;
    }

    // ---- Reachability tracing ----

    /**
     * BFS traversal from {@code startingRoots}, following bytecode references, service provider
     * relationships, and string-constant class names. Populates and returns the {@code visited} set.
     * Can be called multiple times with the same {@code visited} set to resume from new roots.
     */
    private Set<String> traceReachableClasses(Set<String> startingRoots, Set<String> visited,
            Set<String> allKnownClasses) {
        final Queue<String> queue = new ArrayDeque<>();
        for (String root : startingRoots) {
            if (visited.add(root)) {
                queue.add(root);
            }
        }
        boolean sisuActivated = false;
        final Map<ArtifactKey, Integer> depDeserializationFlags = new HashMap<>();

        while (!queue.isEmpty()) {
            String name = queue.poll();

            includeJbossLoggingCompanions(name, visited, queue);
            includeServiceProviders(name, visited, queue);

            byte[] bytecode = lookupBytecode(name);
            if (bytecode == null) {
                continue; // JDK class or not available
            }

            detectAndProcessServiceLoaderCalls(name, bytecode, visited, queue);

            if (!sisuActivated && !input.sisuNamedClasses.isEmpty() && detectsGetResourcesForSisu(bytecode)) {
                sisuActivated = true;
                enqueueNotVisited(input.sisuNamedClasses, visited, queue);
            }

            enqueueBytecodeReferences(name, bytecode, allKnownClasses, visited, queue);
            includeDeserializedClasses(name, bytecode, depDeserializationFlags, visited, queue);
        }
        return visited;
    }

    /**
     * Iterates conditional roots (from {@link io.quarkus.deployment.builditem.nativeimage.ReflectiveClassConditionBuildItem})
     * to a fixed point: when a condition type becomes reachable, its associated class names are added as new roots
     * and traced. Repeats until no new classes are discovered.
     */
    private Set<String> evaluateConditionalRoots(Set<String> reachable, Set<String> allKnownClasses) {
        if (input.conditionalRoots.isEmpty()) {
            return reachable;
        }
        Map<String, Set<String>> remaining = new HashMap<>(input.conditionalRoots);
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> newRoots = new HashSet<>();
            var it = remaining.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (reachable.contains(entry.getKey())) {
                    newRoots.addAll(entry.getValue());
                    it.remove();
                }
            }
            newRoots.removeAll(reachable);
            if (!newRoots.isEmpty()) {
                changed = true;
                traceReachableClasses(newRoots, reachable, allKnownClasses);
            }
        }
        return reachable;
    }

    /**
     * JBoss Logging companion classes (_$logger, _$bundle, _impl) are loaded reflectively
     * via name concatenation in Logger.getMessageLogger().
     */
    private void includeJbossLoggingCompanions(String name, Set<String> visited, Queue<String> queue) {
        for (String suffix : new String[] { "_$logger", "_$bundle", "_impl" }) {
            String companion = name + suffix;
            if (input.depBytecode.containsKey(companion) && visited.add(companion)) {
                queue.add(companion);
            }
        }
    }

    /**
     * If this class is a service interface, include all its providers.
     * This handles indirect ServiceLoader.load() calls through helper methods
     * (e.g. jakarta.ws.rs FactoryFinder) that the direct call tracing can't detect.
     */
    private void includeServiceProviders(String name, Set<String> visited, Queue<String> queue) {
        Set<String> providers = input.serviceProviders.get(name);
        if (providers != null) {
            for (String provider : providers) {
                if (visited.add(provider)) {
                    queue.add(provider);
                }
            }
        }
    }

    /**
     * Looks up bytecode for a class: generated classes first, then dependency classes
     * (which include transformed versions), then app classes.
     * Dependency bytecode is checked before app bytecode because
     * {@link JarTreeShakerInput#collectTransformedClasses} places transformed app class
     * bytecode in {@code depBytecode}, and the transformed version may contain additional
     * references (e.g., converter classes added by bytecode transformers) that the original
     * app bytecode does not have.
     * Returns null if the class is a JDK class or not available.
     */
    private byte[] lookupBytecode(String name) {
        byte[] bytecode = getBytecodeOrNull(input.generatedBytecode.get(name));
        if (bytecode == null) {
            bytecode = getBytecodeOrNull(input.depBytecode.get(name));
            if (bytecode == null) {
                bytecode = getBytecodeOrNull(input.appBytecode.get(name));
            }
        }
        return bytecode;
    }

    private static byte[] getBytecodeOrNull(Supplier<byte[]> supplier) {
        return supplier == null ? null : supplier.get();
    }

    /**
     * Detects ServiceLoader calls lazily for reachable classes and includes providers
     * for any service interfaces loaded by the current class.
     * App and generated class ServiceLoader calls are detected before BFS;
     * dependency class calls are detected here as each class is visited.
     */
    private void detectAndProcessServiceLoaderCalls(String name, byte[] bytecode, Set<String> visited,
            Queue<String> queue) {
        final byte[] currentBytecode = bytecode;
        detectServiceLoaderCalls(() -> currentBytecode, name, input.serviceLoaderCalls);

        Set<String> loadedServices = input.serviceLoaderCalls.get(name);
        if (loadedServices != null) {
            for (String serviceInterface : loadedServices) {
                Set<String> providers = input.serviceProviders.get(serviceInterface);
                if (providers != null) {
                    for (String provider : providers) {
                        if (visited.add(provider)) {
                            queue.add(provider);
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds all classes from the given set to the BFS queue, skipping those already visited.
     */
    private static void enqueueNotVisited(Set<String> classes, Set<String> visited, Queue<String> queue) {
        for (String cls : classes) {
            if (visited.add(cls)) {
                queue.add(cls);
            }
        }
    }

    /**
     * Extracts bytecode references and string constant class references, adding them to the queue.
     * Generated classes are skipped for string constant scanning since they were already scanned
     * during root collection.
     */
    private void enqueueBytecodeReferences(String name, byte[] bytecode, Set<String> allKnownClasses,
            Set<String> visited, Queue<String> queue) {
        Set<String> refs = extractReferencesFromBytecode(bytecode);
        for (String ref : refs) {
            if (ref != null && visited.add(ref)) {
                queue.add(ref);
            }
        }
        // Also check string constants in dependency/app bytecode for class names
        // passed as strings to methods like Class.forName wrappers
        if (!input.generatedBytecode.containsKey(name)) {
            Set<String> stringRefs = extractStringClassReferences(bytecode, allKnownClasses);
            for (String ref : stringRefs) {
                if (visited.add(ref)) {
                    queue.add(ref);
                }
            }
        }
    }

    private static final int FLAG_RESOURCE_ACCESS = 1;
    private static final int FLAG_OBJECT_INPUT_STREAM = 2;
    private static final int FLAG_RESOURCE_DESERIALIZATION = FLAG_RESOURCE_ACCESS | FLAG_OBJECT_INPUT_STREAM;
    private static final int FLAG_SCANNED = 4;

    /**
     * Tracks per-dependency usage of classpath resource access and ObjectInputStream.
     * When both patterns are detected within reachable classes of the same dependency,
     * scans the dependency's non-class resources for Java serialization streams and
     * extracts the class names they reference. The resource access and ObjectInputStream
     * usage may be in different classes (e.g. inner classes), so tracking is per-dependency.
     */
    private void includeDeserializedClasses(String name, byte[] bytecode,
            Map<ArtifactKey, Integer> depFlags,
            Set<String> visited, Queue<String> queue) {
        ArtifactKey depKey = input.classToDep.get(name);
        if (depKey == null) {
            return;
        }
        int flags = depFlags.getOrDefault(depKey, 0);
        if ((flags & FLAG_SCANNED) != 0) {
            return;
        }
        flags |= detectResourceAndObjectInputStreamUsage(bytecode);
        depFlags.put(depKey, flags);
        if ((flags & FLAG_RESOURCE_DESERIALIZATION) != FLAG_RESOURCE_DESERIALIZATION) {
            return;
        }
        depFlags.put(depKey, flags | FLAG_SCANNED);
        OpenPathTree openTree = input.depOpenTrees.get(depKey);
        if (openTree == null) {
            return;
        }
        Set<String> classNames = extractClassNamesFromSerializedResources(openTree);
        if (!classNames.isEmpty()) {
            log.debugf("ObjectInputStream + resource access detected in %s, "
                    + "found %d serialized classes in %s",
                    name, classNames.size(), depKey);
            for (String className : classNames) {
                if (visited.add(className)) {
                    queue.add(className);
                }
            }
        }
    }

    /**
     * Scans bytecode for classpath resource access ({@code getResource}/{@code getResourceAsStream})
     * and ObjectInputStream usage, returning detected flags.
     */
    private static int detectResourceAndObjectInputStreamUsage(byte[] bytecode) {
        int[] flags = new int[1];
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if ((flags[0] & FLAG_RESOURCE_DESERIALIZATION) == FLAG_RESOURCE_DESERIALIZATION) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                            String mdescriptor, boolean isInterface) {
                        if ("getResourceAsStream".equals(mname) || "getResource".equals(mname)) {
                            flags[0] |= FLAG_RESOURCE_ACCESS;
                        }
                        if ("java/io/ObjectInputStream".equals(owner)) {
                            flags[0] |= FLAG_OBJECT_INPUT_STREAM;
                        }
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if ("java/io/ObjectInputStream".equals(type)) {
                            flags[0] |= FLAG_OBJECT_INPUT_STREAM;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return flags[0];
    }

    /**
     * Scans non-class resources in the given dependency for Java serialization streams
     * (magic bytes {@code 0xACED0005}) and extracts class names from class descriptors.
     */
    private static Set<String> extractClassNamesFromSerializedResources(OpenPathTree openTree) {
        Set<String> classNames = new HashSet<>();
        openTree.walk(visit -> {
            String entry = visit.getResourceName();
            if (entry.endsWith(".class") || entry.endsWith("/") || entry.startsWith("META-INF/")) {
                return;
            }
            try (InputStream is = Files.newInputStream(visit.getPath())) {
                byte[] header = new byte[4];
                if (is.read(header) == 4
                        && header[0] == (byte) 0xAC && header[1] == (byte) 0xED
                        && header[2] == (byte) 0x00 && header[3] == (byte) 0x05) {
                    byte[] rest = is.readAllBytes();
                    extractClassDescriptorNames(rest, classNames);
                }
            } catch (IOException e) {
                // ignore unreadable resources
            }
        });
        return classNames;
    }

    /**
     * Parses Java serialization stream data for TC_CLASSDESC markers ({@code 0x72})
     * and extracts the class names from the UTF-encoded class descriptor strings.
     */
    private static void extractClassDescriptorNames(byte[] data, Set<String> classNames) {
        for (int i = 0; i < data.length - 2; i++) {
            if (data[i] == (byte) 0x72) {
                int len = ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
                if (len > 0 && len < 500 && i + 3 + len <= data.length) {
                    String name = new String(data, i + 3, len, StandardCharsets.UTF_8);
                    if (isValidClassName(name)) {
                        classNames.add(name);
                    }
                    i += 2 + len; // skip past the class name
                }
            }
        }
    }

    /**
     * Validates that a string looks like a fully-qualified Java class name
     * (contains at least one dot, starts with a valid identifier character,
     * and contains only valid identifier characters, dots, and dollar signs).
     */
    private static boolean isValidClassName(String name) {
        if (name.isEmpty() || name.length() > 300) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        boolean hasDot = false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                hasDot = true;
            } else if (!Character.isJavaIdentifierPart(c)) {
                return false;
            }
        }
        return hasDot;
    }

    // ---- Bytecode analysis ----

    /**
     * Extracts all class references from bytecode using ASM: superclass, interfaces,
     * field/method descriptors, generic signatures, annotations, method body instructions
     * (type insns, field/method owners, LDC class constants, invokedynamic handles),
     * and {@code Class.forName()}/{@code ClassLoader.loadClass()} calls with string constants.
     */
    private Set<String> extractReferencesFromBytecode(byte[] bytecode) {
        final Set<String> refs = new HashSet<>();
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                if (superName != null) {
                    refs.add(toClassName(superName));
                }
                if (interfaces != null) {
                    for (String iface : interfaces) {
                        refs.add(toClassName(iface));
                    }
                }
                addSignatureTypes(signature, refs);
                // Inner classes must reference their outer class because the JVM
                // resolves it at runtime via Class.getDeclaringClass0()
                int dollar = name.lastIndexOf('$');
                if (dollar > 0) {
                    refs.add(toClassName(name.substring(0, dollar)));
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
                return new org.objectweb.asm.FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        addDescriptorType(descriptor, refs);
                        return createAnnotationRefVisitor(refs);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                addMethodDescriptorTypes(descriptor, refs);
                addSignatureTypes(signature, refs);
                if (exceptions != null) {
                    for (String ex : exceptions) {
                        refs.add(toClassName(ex));
                    }
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private String lastStringConstant;

                    @Override
                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        lastStringConstant = null;
                        if (type != null) {
                            refs.add(toClassName(type));
                        }
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitAnnotationDefault() {
                        // Captures default values of annotation methods (e.g. @Reflective(processors = SimpleReflectiveProcessor.class))
                        return createAnnotationRefVisitor(refs);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        lastStringConstant = null;
                        refs.add(toClassName(type));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdescriptor) {
                        lastStringConstant = null;
                        refs.add(toClassName(owner));
                        addDescriptorType(fdescriptor, refs);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                            String mdescriptor, boolean isInterface) {
                        // Track Class.forName(String) and ClassLoader.loadClass(String)
                        if (lastStringConstant != null) {
                            if (("java/lang/Class".equals(owner) && "forName".equals(mname))
                                    || "loadClass".equals(mname)) {
                                refs.add(lastStringConstant);
                            }
                        }
                        lastStringConstant = null;
                        refs.add(toClassName(owner));
                        addMethodDescriptorTypes(mdescriptor, refs);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            lastStringConstant = (String) value;
                        } else {
                            lastStringConstant = null;
                        }
                        if (value instanceof org.objectweb.asm.Type type) {
                            if (type.getSort() == org.objectweb.asm.Type.OBJECT) {
                                refs.add(toClassName(type.getInternalName()));
                            } else if (type.getSort() == org.objectweb.asm.Type.ARRAY) {
                                org.objectweb.asm.Type elem = type.getElementType();
                                if (elem.getSort() == org.objectweb.asm.Type.OBJECT) {
                                    refs.add(toClassName(elem.getInternalName()));
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
                                if (arg instanceof org.objectweb.asm.Type type) {
                                    addAsmType(type, refs);
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
    static Set<String> extractStringClassReferences(byte[] bytecode, Set<String> knownClasses) {
        final Set<String> refs = new HashSet<>();
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

    /**
     * Checks whether a string (or its comma/colon-delimited parts) matches known class names,
     * adding matches to {@code refs}.
     */
    static void matchClassNames(String str, Set<String> knownClasses, Set<String> refs) {
        if (str.indexOf(',') >= 0 || str.indexOf(':') >= 0) {
            for (String part : str.split("[,:]")) {
                if (knownClasses.contains(part)) {
                    refs.add(part);
                }
            }
        } else {
            if (knownClasses.contains(str)) {
                refs.add(str);
            }
        }
    }

    /**
     * Scans bytecode for {@code ServiceLoader.load(SomeClass.class)} call patterns.
     * When found, records the service interface class name in {@code serviceLoaderCalls}
     * keyed by the calling class name.
     */
    static void detectServiceLoaderCalls(Supplier<byte[]> bytecode, String className,
            Map<String, Set<String>> serviceLoaderCalls) {
        ClassReader reader = new ClassReader(bytecode.get());
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    private org.objectweb.asm.Type lastClassConstant;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof org.objectweb.asm.Type type) {
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
                                    .add(toClassName(lastClassConstant.getInternalName()));
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
    private static org.objectweb.asm.AnnotationVisitor createAnnotationRefVisitor(Set<String> refs) {
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

    private static String toClassName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static void addDescriptorType(String descriptor, Set<String> refs) {
        org.objectweb.asm.Type type = org.objectweb.asm.Type.getType(descriptor);
        addAsmType(type, refs);
    }

    private static void addMethodDescriptorTypes(String descriptor, Set<String> refs) {
        for (org.objectweb.asm.Type argType : org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
            addAsmType(argType, refs);
        }
        addAsmType(org.objectweb.asm.Type.getReturnType(descriptor), refs);
    }

    private static void addHandleType(Handle handle, Set<String> refs) {
        refs.add(toClassName(handle.getOwner()));
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
    private static void addSignatureTypes(String signature, Set<String> refs) {
        if (signature == null) {
            return;
        }
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) {
                refs.add(toClassName(name));
            }
        });
    }

    private static void addAsmType(org.objectweb.asm.Type type, Set<String> refs) {
        if (type.getSort() == org.objectweb.asm.Type.OBJECT) {
            refs.add(toClassName(type.getInternalName()));
        } else if (type.getSort() == org.objectweb.asm.Type.ARRAY) {
            addAsmType(type.getElementType(), refs);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
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
}
