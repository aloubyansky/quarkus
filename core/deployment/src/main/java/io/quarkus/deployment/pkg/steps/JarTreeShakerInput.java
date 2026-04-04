package io.quarkus.deployment.pkg.steps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.TransformedClassesBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassConditionBuildItem;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeRootClassBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.OpenPathTree;

/**
 * Assembles all data needed by {@link JarTreeShaker} for reachability analysis.
 * Separates input collection (dependency walking, build item extraction, root identification)
 * from the BFS analysis itself.
 *
 * <p>
 * Implements {@link AutoCloseable} because it owns {@link OpenPathTree} instances
 * whose lifecycle must extend until the analysis completes (bytecode suppliers hold
 * {@link Path} references into the open trees).
 *
 * <p>
 * Use the {@link #collect} factory method to build an instance from the application model
 * and build items, then pass it to {@link JarTreeShaker}.
 */
class JarTreeShakerInput implements AutoCloseable {

    private static final Logger log = Logger.getLogger(JarTreeShakerInput.class.getName());

    private static final String SISU_NAMED_RESOURCE = "META-INF/sisu/javax.inject.Named";
    private static final String META_INF_VERSIONS = "META-INF/versions";
    private static final String META_INF_SERVICES = "META-INF/services/";

    final PackageConfig.JarConfig.TreeShakeLevel treeShakeLevel;
    final Set<String> roots;
    final Map<String, Set<String>> conditionalRoots;
    final Set<String> sisuNamedClasses;
    final Map<String, Supplier<byte[]>> depBytecode;
    final Map<String, Supplier<byte[]>> appBytecode;
    final Map<String, Supplier<byte[]>> generatedBytecode;
    final Map<String, ArtifactKey> classToDep;
    final Map<String, Long> depBytecodeSize;
    final Map<String, Set<String>> serviceProviders;
    final Map<String, Set<String>> serviceLoaderCalls;
    final Set<String> allKnownClasses;
    final int appJavaVersion;
    final Map<ArtifactKey, OpenPathTree> depOpenTrees;
    final List<Path> depJarPaths;
    final List<Path> appPaths;
    final Set<String> transformedClassNames;

    private final List<OpenPathTree> openTrees;

    JarTreeShakerInput(
            PackageConfig.JarConfig.TreeShakeLevel treeShakeLevel,
            Set<String> roots,
            Map<String, Set<String>> conditionalRoots,
            Set<String> sisuNamedClasses,
            Map<String, Supplier<byte[]>> depBytecode,
            Map<String, Supplier<byte[]>> appBytecode,
            Map<String, Supplier<byte[]>> generatedBytecode,
            Map<String, ArtifactKey> classToDep,
            Map<String, Long> depBytecodeSize,
            Map<String, Set<String>> serviceProviders,
            Map<String, Set<String>> serviceLoaderCalls,
            Set<String> allKnownClasses,
            int appJavaVersion,
            Map<ArtifactKey, OpenPathTree> depOpenTrees,
            List<Path> depJarPaths,
            List<Path> appPaths,
            Set<String> transformedClassNames,
            List<OpenPathTree> openTrees) {
        this.treeShakeLevel = treeShakeLevel;
        this.roots = roots;
        this.conditionalRoots = conditionalRoots;
        this.sisuNamedClasses = sisuNamedClasses;
        this.depBytecode = depBytecode;
        this.appBytecode = appBytecode;
        this.generatedBytecode = generatedBytecode;
        this.classToDep = classToDep;
        this.depBytecodeSize = depBytecodeSize;
        this.serviceProviders = serviceProviders;
        this.serviceLoaderCalls = serviceLoaderCalls;
        this.allKnownClasses = allKnownClasses;
        this.appJavaVersion = appJavaVersion;
        this.depOpenTrees = depOpenTrees;
        this.depJarPaths = depJarPaths;
        this.appPaths = appPaths;
        this.transformedClassNames = transformedClassNames;
        this.openTrees = openTrees;
    }

    /**
     * Closes all {@link OpenPathTree} instances opened during input collection.
     */
    @Override
    public void close() {
        for (OpenPathTree openPathTree : openTrees) {
            try {
                openPathTree.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * Factory method that assembles all input for the tree-shake analysis.
     * Walks dependency and application JARs, collects bytecode, service provider metadata,
     * and extracts root classes from generated classes, native-image build items,
     * and string-constant class references.
     *
     * <p>
     * The caller must close the returned instance to release the opened dependency trees.
     */
    static JarTreeShakerInput collect(
            PackageConfig.JarConfig.TreeShakeLevel treeShakeLevel,
            ApplicationModel appModel,
            List<GeneratedClassBuildItem> generatedClasses,
            TransformedClassesBuildItem transformedClasses,
            List<ReflectiveClassConditionBuildItem> reflectiveClassConditions,
            List<JarTreeShakeRootClassBuildItem> rootClasses) {

        final Set<String> roots = new HashSet<>();

        final Map<String, Supplier<byte[]>> generatedBytecode = getGeneratedClassesMap(generatedClasses);

        final Map<String, Supplier<byte[]>> depBytecode = new HashMap<>();
        final Map<String, Long> depBytecodeSize = new HashMap<>();
        final Map<String, ArtifactKey> classToDep = new HashMap<>();
        final Map<String, Integer> depBytecodeVersion = new HashMap<>();
        final Map<String, Set<String>> serviceProviders = new HashMap<>();
        final Set<String> sisuNamedClasses = new HashSet<>();
        final Map<String, Supplier<byte[]>> appBytecode = new HashMap<>();
        final Map<String, Set<String>> serviceLoaderCalls = new HashMap<>();
        final Map<ArtifactKey, OpenPathTree> depOpenTrees = new HashMap<>();

        final int appJavaVersion = detectAppJavaVersion(appModel);

        final List<Path> depJarPaths = new ArrayList<>();
        final List<OpenPathTree> openTrees = new ArrayList<>();
        try {
            collectRuntimeClasses(appModel, appJavaVersion, roots, depBytecode, depBytecodeSize,
                    classToDep, depBytecodeVersion, serviceProviders, sisuNamedClasses, depOpenTrees,
                    depJarPaths, openTrees);

            final Set<String> transformedClassNames = new HashSet<>();
            collectTransformedClasses(transformedClasses, depBytecode, depBytecodeSize, transformedClassNames);

            collectApplicationClasses(appModel, appBytecode, serviceProviders, serviceLoaderCalls, openTrees);

            final List<Path> appPaths = new ArrayList<>();
            for (Path p : appModel.getAppArtifact().getResolvedPaths()) {
                appPaths.add(p);
            }

            detectGeneratedClassServiceLoaderCalls(generatedBytecode, serviceLoaderCalls);

            addServiceProviderRoots(serviceProviders, classToDep, generatedBytecode, roots);

            final Set<String> allKnownClasses = collectAllKnownClasses(classToDep, generatedBytecode);
            addStringClassReferenceRoots(generatedBytecode, allKnownClasses, roots);

            for (JarTreeShakeRootClassBuildItem item : rootClasses) {
                roots.add(item.getClassName());
            }

            final Map<String, Set<String>> conditionalRoots = collectConditionalRoots(reflectiveClassConditions);

            return new JarTreeShakerInput(
                    treeShakeLevel,
                    roots,
                    conditionalRoots,
                    sisuNamedClasses,
                    depBytecode,
                    appBytecode,
                    generatedBytecode,
                    classToDep,
                    depBytecodeSize,
                    serviceProviders,
                    serviceLoaderCalls,
                    allKnownClasses,
                    appJavaVersion,
                    depOpenTrees,
                    depJarPaths,
                    appPaths,
                    transformedClassNames,
                    openTrees);
        } catch (Exception e) {
            for (var openPathTree : openTrees) {
                try {
                    openPathTree.close();
                } catch (IOException ignore) {
                }
            }
            throw e;
        }
    }

    /**
     * Walks all runtime classpath dependencies to collect bytecode (with multi-release resolution),
     * class-to-dependency mappings, service provider files, and sisu named components.
     * Classes from {@code quarkus-bootstrap-runner} are added as roots.
     */
    private static void collectRuntimeClasses(
            ApplicationModel appModel,
            int appJavaVersion,
            Set<String> roots,
            Map<String, Supplier<byte[]>> depBytecode,
            Map<String, Long> depBytecodeSize,
            Map<String, ArtifactKey> classToDep,
            Map<String, Integer> depBytecodeVersion,
            Map<String, Set<String>> serviceProviders,
            Set<String> sisuNamedClasses,
            Map<ArtifactKey, OpenPathTree> depOpenTrees,
            List<Path> depJarPaths,
            List<OpenPathTree> openTrees) {

        for (ResolvedDependency dep : appModel.getDependencies(DependencyFlags.RUNTIME_CP)) {
            final boolean addClassesAsRoots = "quarkus-bootstrap-runner".equals(dep.getArtifactId());

            for (Path p : dep.getResolvedPaths()) {
                depJarPaths.add(p);
            }
            OpenPathTree openTree = openPathTree(dep, openTrees);
            depOpenTrees.put(dep.getKey(), openTree);
            openTree.walkRaw(visit -> {
                String entry = visit.getResourceName();
                if (isClassEntry(entry)) {
                    processRuntimeClassEntry(entry, visit.getPath(), dep, appJavaVersion, addClassesAsRoots,
                            roots, depBytecode, depBytecodeSize, classToDep, depBytecodeVersion);
                    return;
                }
                if (entry.startsWith(META_INF_SERVICES) && !entry.endsWith("/")) {
                    parseServiceFile(visit.getPath(), entry, serviceProviders);
                    return;
                }
                if (SISU_NAMED_RESOURCE.equals(entry)) {
                    parseSisuNamedFile(visit.getPath(), sisuNamedClasses);
                }
            });
        }
    }

    /**
     * Processes a single class entry from a runtime dependency JAR. Handles multi-release
     * resolution (keeping the highest version &le; {@code appJavaVersion}), records the
     * bytecode supplier and class-to-dependency mapping, and optionally adds the class as a root.
     */
    private static void processRuntimeClassEntry(
            String entry,
            Path path,
            ResolvedDependency dep,
            int appJavaVersion,
            boolean addClassesAsRoots,
            Set<String> roots,
            Map<String, Supplier<byte[]>> depBytecode,
            Map<String, Long> depBytecodeSize,
            Map<String, ArtifactKey> classToDep,
            Map<String, Integer> depBytecodeVersion) {

        String className;
        int classJavaVersion = 0;

        if (entry.startsWith(META_INF_VERSIONS)) {
            int resolved = resolveMultiReleaseVersion(entry, appJavaVersion);
            if (resolved < 0) {
                return;
            }
            classJavaVersion = resolved;
            int javaVersionSeparator = entry.indexOf('/', META_INF_VERSIONS.length() + 1);
            className = classNameOf(entry, javaVersionSeparator + 1);
        } else {
            className = classNameOf(entry);
        }

        int currentVersion = depBytecodeVersion.getOrDefault(className, -1);
        if (currentVersion < 0) {
            classToDep.put(className, dep.getKey());
        }
        if (classJavaVersion > currentVersion) {
            depBytecodeVersion.put(className, classJavaVersion);
            depBytecode.put(className, new BytecodeSupplier(path));
            try {
                depBytecodeSize.put(className, Files.size(path));
            } catch (IOException e) {
                // ignore, size is only used for reporting
            }
        }

        if (addClassesAsRoots) {
            roots.add(className);
        }
    }

    /**
     * Resolves a multi-release version entry, returning the Java version number
     * if it should be included, or -1 if it should be skipped.
     */
    private static int resolveMultiReleaseVersion(String entry, int appJavaVersion) {
        if (entry.length() == META_INF_VERSIONS.length() || entry.charAt(META_INF_VERSIONS.length()) != '/') {
            return -1;
        }
        final int javaVersionSeparator = entry.indexOf('/', META_INF_VERSIONS.length() + 1);
        if (javaVersionSeparator == -1) {
            return -1;
        }
        try {
            int classJavaVersion = Integer.parseInt(
                    entry.substring(META_INF_VERSIONS.length() + 1, javaVersionSeparator));
            if (classJavaVersion > appJavaVersion) {
                return -1;
            }
            return classJavaVersion;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Overrides dependency bytecode with transformed bytecode where available.
     * Transformations can add interfaces, annotations, or other references
     * that aren't in the original bytecode (e.g. NettySharable marker interface).
     */
    private static void collectTransformedClasses(
            TransformedClassesBuildItem transformedClasses,
            Map<String, Supplier<byte[]>> depBytecode,
            Map<String, Long> depBytecodeSize,
            Set<String> transformedClassNames) {
        for (Set<TransformedClassesBuildItem.TransformedClass> transformedSet : transformedClasses
                .getTransformedClassesByJar().values()) {
            for (TransformedClassesBuildItem.TransformedClass tc : transformedSet) {
                if (tc.getData() != null) {
                    String fileName = tc.getFileName();
                    if (fileName.endsWith(".class") && !fileName.equals("module-info.class")) {
                        String className = fileName.substring(0, fileName.length() - 6).replace('/', '.');
                        depBytecode.put(className, tc::getData);
                        depBytecodeSize.put(className, (long) tc.getData().length);
                        transformedClassNames.add(className);
                    }
                }
            }
        }
    }

    /**
     * Collects bytecode, service providers, and ServiceLoader calls from the application artifact.
     * App bytecode is needed so the reachability trace can follow references from app classes
     * to dependency classes (e.g. app code using a library utility class).
     */
    private static void collectApplicationClasses(
            ApplicationModel appModel,
            Map<String, Supplier<byte[]>> appBytecode,
            Map<String, Set<String>> serviceProviders,
            Map<String, Set<String>> serviceLoaderCalls,
            List<OpenPathTree> openTrees) {
        openPathTree(appModel.getAppArtifact(), openTrees).walk(visit -> {
            String entry = visit.getResourceName();
            if (isClassEntry(entry)) {
                final String className = classNameOf(entry);
                final BytecodeSupplier bytecode = new BytecodeSupplier(visit.getPath());
                JarTreeShaker.detectServiceLoaderCalls(bytecode, className, serviceLoaderCalls);
                appBytecode.put(className, bytecode);
                return;
            }
            if (entry.startsWith(META_INF_SERVICES) && !entry.endsWith("/")) {
                parseServiceFile(visit.getPath(), entry, serviceProviders);
            }
        });
    }

    /**
     * Detects ServiceLoader calls within generated classes.
     * Generated classes are added as roots via {@code JarTreeShakeRootClassBuildItem}.
     */
    private static void detectGeneratedClassServiceLoaderCalls(
            Map<String, Supplier<byte[]>> generatedBytecode,
            Map<String, Set<String>> serviceLoaderCalls) {
        for (var entry : generatedBytecode.entrySet()) {
            JarTreeShaker.detectServiceLoaderCalls(entry.getValue(), entry.getKey(), serviceLoaderCalls);
        }
    }

    /**
     * Add service providers for JDK service interfaces as roots, since JDK code
     * isn't analyzed and its ServiceLoader.load() calls can't be traced.
     * Non-JDK service providers are discovered through ServiceLoader.load() call
     * tracing in reachable dependency/app code.
     */
    private static void addServiceProviderRoots(
            Map<String, Set<String>> serviceProviders,
            Map<String, ArtifactKey> classToDep,
            Map<String, Supplier<byte[]>> generatedBytecode,
            Set<String> roots) {
        for (Map.Entry<String, Set<String>> entry : serviceProviders.entrySet()) {
            String serviceInterface = entry.getKey();
            if (!classToDep.containsKey(serviceInterface) && !generatedBytecode.containsKey(serviceInterface)) {
                roots.addAll(entry.getValue());
            }
        }
    }

    /**
     * Returns the union of dependency and generated class names, used for string-constant
     * class reference matching.
     */
    private static Set<String> collectAllKnownClasses(
            Map<String, ArtifactKey> classToDep,
            Map<String, Supplier<byte[]>> generatedBytecode) {
        final Set<String> allKnownClasses = new HashSet<>(classToDep.keySet());
        allKnownClasses.addAll(generatedBytecode.keySet());
        return allKnownClasses;
    }

    /**
     * Scan generated classes for LDC string constants that match known dependency class names.
     * Generated recorder bytecode often passes class names as strings to methods like
     * factory(className) that eventually call Class.forName() at runtime.
     */
    private static void addStringClassReferenceRoots(
            Map<String, Supplier<byte[]>> generatedBytecode,
            Set<String> allKnownClasses,
            Set<String> roots) {
        for (Supplier<byte[]> bytecode : generatedBytecode.values()) {
            Set<String> stringClassRefs = JarTreeShaker.extractStringClassReferences(bytecode.get(), allKnownClasses);
            for (String ref : stringClassRefs) {
                if (roots.add(ref)) {
                    log.debugf("String constant class reference from generated code: %s", ref);
                }
            }
        }
    }

    /**
     * Builds a map from condition type to the set of class names that should become roots
     * when that condition type is reachable. Used by {@link JarTreeShaker#evaluateConditionalRoots}
     * for fixed-point evaluation.
     */
    private static Map<String, Set<String>> collectConditionalRoots(
            List<ReflectiveClassConditionBuildItem> reflectiveClassConditions) {
        Map<String, Set<String>> conditionalRoots = new HashMap<>();
        for (ReflectiveClassConditionBuildItem item : reflectiveClassConditions) {
            conditionalRoots
                    .computeIfAbsent(item.getTypeReachable(), k -> new HashSet<>())
                    .add(item.getClassName());
        }
        return conditionalRoots;
    }

    /**
     * Converts {@link GeneratedClassBuildItem} list to a class-name-to-bytecode-supplier map,
     * translating binary names (with {@code /}) to dot-separated class names.
     */
    private static Map<String, Supplier<byte[]>> getGeneratedClassesMap(List<GeneratedClassBuildItem> generatedClasses) {
        final Map<String, Supplier<byte[]>> generatedBytecode = new HashMap<>(generatedClasses.size());
        for (GeneratedClassBuildItem gen : generatedClasses) {
            generatedBytecode.put(gen.binaryName().replace('/', '.'), gen::getClassData);
        }
        return generatedBytecode;
    }

    // ---- Supporting methods ----

    /**
     * Opens a dependency's content tree and registers it for cleanup on {@link #close()}.
     */
    private static OpenPathTree openPathTree(ResolvedDependency dep, List<OpenPathTree> openTrees) {
        var openPathTree = dep.getContentTree().open();
        openTrees.add(openPathTree);
        return openPathTree;
    }

    /**
     * Detects the Java feature version from the application's class bytecode.
     * The class file major version maps to Java versions: 52=8, 55=11, 61=17, 65=21.
     * Falls back to the current JVM's feature version if no app classes are found.
     */
    private static int detectAppJavaVersion(ApplicationModel appModel) {
        int[] majorVersion = new int[1];
        appModel.getAppArtifact().getContentTree().walk(visit -> {
            String entry = visit.getResourceName();
            if (isClassEntry(entry)) {
                try (InputStream is = Files.newInputStream(visit.getPath())) {
                    byte[] header = new byte[8];
                    if (is.read(header) == 8) {
                        majorVersion[0] = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                        if (majorVersion[0] > 0) {
                            visit.stopWalking();
                        }
                    }
                } catch (IOException e) {
                    // ignore, will try next class
                }
            }
        });
        int javaVersion = majorVersion[0] > 0 ? majorVersion[0] - 44 : Runtime.version().feature();
        log.debugf("Detected app Java version: %d (class file major: %d)", javaVersion, majorVersion[0]);
        return javaVersion;
    }

    /**
     * Returns {@code true} if the resource name represents a regular class file,
     * excluding {@code module-info.class} and {@code package-info.class}.
     */
    static boolean isClassEntry(String resourceName) {
        return resourceName.endsWith(".class")
                && !resourceName.equals("module-info.class")
                && !resourceName.endsWith("package-info.class");
    }

    /**
     * Converts a class resource path (e.g. {@code com/example/Foo.class}) to a dot-separated
     * class name (e.g. {@code com.example.Foo}).
     */
    static String classNameOf(String classResourceName) {
        return classNameOf(classResourceName, 0);
    }

    /**
     * Converts a class resource path to a dot-separated class name, starting from
     * the given index (used for multi-release entries to skip the {@code META-INF/versions/N/} prefix).
     */
    static String classNameOf(String resourceName, int classNameStartIndex) {
        return resourceName.substring(classNameStartIndex, resourceName.length() - 6).replace('/', '.');
    }

    /**
     * Parses a {@code META-INF/services/} file, extracting provider class names
     * (stripping comments and whitespace) and grouping them by service interface.
     */
    private static void parseServiceFile(Path file, String relativePath,
            Map<String, Set<String>> serviceProviders) {
        String serviceInterface = relativePath.substring(META_INF_SERVICES.length());
        if (serviceInterface.isEmpty() || serviceInterface.contains("/")) {
            return;
        }
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
                    serviceProviders.computeIfAbsent(serviceInterface, k -> new HashSet<>())
                            .add(line);
                }
            }
        } catch (IOException e) {
            log.debugf(e, "Failed to read service file: %s", file);
        }
    }

    /**
     * Parses a {@code META-INF/sisu/javax.inject.Named} file, extracting class names.
     * These classes are only included in the reachable set if a {@code ClassLoader.getResources()}
     * call for the sisu resource is detected during BFS.
     */
    private static void parseSisuNamedFile(Path file, Set<String> sisuNamedClasses) {
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
                    sisuNamedClasses.add(line);
                }
            }
        } catch (IOException e) {
            log.debugf(e, "Failed to read sisu named file: %s", file);
        }
    }

    /**
     * Lazy bytecode loader that reads from a {@link Path} on first access and caches the result.
     * The path must remain valid (i.e. the owning {@link OpenPathTree} must stay open) until
     * the bytecode is read.
     */
    /**
     * Releases cached bytecode from all suppliers across dep, app, and generated maps.
     * Called after tree-shake analysis completes to reduce memory pressure during
     * the remainder of the build.
     */
    void clearBytecodeCache() {
        clearSupplierCache(depBytecode);
        clearSupplierCache(appBytecode);
        clearSupplierCache(generatedBytecode);
    }

    private static void clearSupplierCache(Map<String, Supplier<byte[]>> map) {
        int totalBs = 0;
        int totalLoaded = 0;
        for (Supplier<byte[]> supplier : map.values()) {
            if (supplier instanceof BytecodeSupplier bs) {
                ++totalBs;
                if (bs.bytes != null) {
                    ++totalLoaded;
                }
                bs.clearCache();
            }
        }
        if (totalBs > 0) {
            System.out.println("BytecodeSupplier cache: loaded " + totalLoaded + " out of " + totalBs);
        }
    }

    static class BytecodeSupplier implements Supplier<byte[]> {
        private final Path path;
        private byte[] bytes;

        BytecodeSupplier(Path path) {
            this.path = path;
        }

        @Override
        public byte[] get() {
            if (bytes == null) {
                try (InputStream is = Files.newInputStream(path)) {
                    bytes = is.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read bytecode " + path, e);
                }
            }
            return bytes;
        }

        void clearCache() {
            bytes = null;
        }
    }
}
