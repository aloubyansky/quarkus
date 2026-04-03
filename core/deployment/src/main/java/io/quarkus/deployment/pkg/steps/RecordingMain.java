package io.quarkus.deployment.pkg.steps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Self-contained main class executed in a forked JVM by {@link ClassLoadingChainAnalyzer}.
 * <p>
 * Reads entry point class names and known class names from an input file,
 * loads and instantiates each entry point in a recording classloader that captures
 * all {@code loadClass()} attempts, and prints discovered class names to stdout.
 * <p>
 * Running in a separate process ensures all Metaspace consumed by {@code defineClass()}
 * is reclaimed when the process exits.
 */
public class RecordingMain {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.exit(1);
            return;
        }

        Path inputFile = Path.of(args[0]);
        List<String> lines = Files.readAllLines(inputFile, StandardCharsets.UTF_8);

        // Parse input: entry points before "---", allKnownClasses after
        List<String> entryPoints = new ArrayList<>();
        Set<String> allKnownClasses = new HashSet<>();
        boolean inKnownClasses = false;
        for (String line : lines) {
            if ("---".equals(line)) {
                inKnownClasses = true;
                continue;
            }
            if (inKnownClasses) {
                allKnownClasses.add(line);
            } else {
                entryPoints.add(line);
            }
        }

        // The temp dir (parent of input file) is the classpath root
        Path classesDir = inputFile.getParent();

        // Build bytecode map from class files on disk
        Set<String> availableClasses = new HashSet<>();
        try (var walk = Files.walk(classesDir)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String rel = classesDir.relativize(p).toString();
                        String className = rel.substring(0, rel.length() - 6).replace('/', '.').replace('\\', '.');
                        availableClasses.add(className);
                    });
        }

        // Suppress stdout/stderr during class loading
        java.io.PrintStream realOut = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // Use a URLClassLoader pointing at the temp dir with platform CL as parent
        java.net.URL[] urls;
        try {
            urls = new java.net.URL[] { classesDir.toUri().toURL() };
        } catch (java.net.MalformedURLException e) {
            System.exit(2);
            return;
        }

        Set<String> allDiscovered = new HashSet<>();
        RecordingURLClassLoader loader = new RecordingURLClassLoader(urls);

        Thread.currentThread().setContextClassLoader(loader);
        for (String entryPoint : entryPoints) {
            try {
                Class<?> clazz = Class.forName(entryPoint, true, loader);
                try {
                    Object instance = clazz.getConstructor().newInstance();
                    // Extract class names from Map values (BouncyCastle pattern)
                    if (instance instanceof Map<?, ?> map) {
                        for (Object value : map.values()) {
                            if (value instanceof String strValue && allKnownClasses.contains(strValue)) {
                                allDiscovered.add(strValue);
                            }
                        }
                    }
                } catch (Exception | LinkageError ignored) {
                }
            } catch (Exception | LinkageError ignored) {
            }
        }

        allDiscovered.addAll(loader.getLoadedClassNames());

        // Restore stdout and print results
        System.setOut(realOut);
        for (String className : allDiscovered) {
            realOut.println(className);
        }
    }

    /**
     * A URLClassLoader that records all class names passed to {@code loadClass()}.
     * Uses the platform class loader as parent so app/dep classes are loaded from
     * the URL classpath (child-first), ensuring transitive {@code Class.forName()}
     * calls go through this classloader and get recorded.
     */
    private static class RecordingURLClassLoader extends java.net.URLClassLoader {

        private final Set<String> loadedClassNames = new HashSet<>();

        RecordingURLClassLoader(java.net.URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            loadedClassNames.add(name);
            return super.loadClass(name);
        }

        Set<String> getLoadedClassNames() {
            return loadedClassNames;
        }
    }
}
