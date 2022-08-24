package io.quarkus.deployment.dev;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.jboss.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;

import io.quarkus.gizmo.Gizmo;
import io.quarkus.paths.PathCollection;

public class JavaCompilationProvider implements CompilationProvider {

    private static final Logger log = Logger.getLogger(JavaCompilationProvider.class);
    static final String PROVIDER_KEY = "java";

    // -g is used to make the java compiler generate all debugging info
    // -parameters is used to generate metadata for reflection on method parameters
    // this is useful when people using debuggers against their hot-reloaded app
    static final Set<String> COMPILER_OPTIONS = Set.of("-g", "-parameters");
    private static final Set<String> IGNORE_NAMESPACES = Set.of("org.osgi");

    JavaCompiler compiler;
    StandardJavaFileManager fileManager;
    DiagnosticCollector<JavaFileObject> fileManagerDiagnostics;
    List<String> compilerFlags;

    @Override
    public String getProviderKey() {
        return PROVIDER_KEY;
    }

    @Override
    public Set<String> handledExtensions() {
        return Set.of(".java");
    }

    @Override
    public void compile(Set<File> filesToCompile, Context context) {
        JavaCompiler compiler = this.compiler;
        if (compiler == null) {
            compiler = this.compiler = ToolProvider.getSystemJavaCompiler();
        }
        if (compiler == null) {
            throw new RuntimeException("No system java compiler provided");
        }

        final long start = System.currentTimeMillis();
        try {
            StandardJavaFileManager fileManager = getJavaFileManager(context);

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromFiles(filesToCompile);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, compilerFlags, null, sources);

            if (!task.call()) {
                StringBuilder sb = new StringBuilder("\u001B[91mCompilation Failed:");
                for (Diagnostic<? extends JavaFileObject> i : diagnostics.getDiagnostics()) {
                    sb.append("\n");
                    sb.append(i.toString());
                }
                sb.append("\u001b[0m");
                throw new RuntimeException(sb.toString());
            }

            logDiagnostics(diagnostics);

            if (!fileManagerDiagnostics.getDiagnostics().isEmpty()) {
                logDiagnostics(fileManagerDiagnostics);
                fileManager.close();
                fileManager = null;
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot close file manager", e);
        } finally {
            System.out.println("COMPILATION TOOK " + (System.currentTimeMillis() - start));
        }
    }

    private StandardJavaFileManager getJavaFileManager(Context context) throws IOException {

        if (fileManager == null) {
            fileManagerDiagnostics = new DiagnosticCollector<>();
            compilerFlags = new CompilerFlags(JavaCompilationProvider.COMPILER_OPTIONS,
                    context.getCompilerOptions(JavaCompilationProvider.PROVIDER_KEY),
                    context.getReleaseJavaVersion(), context.getSourceJavaVersion(), context.getTargetJvmVersion()).toList();
            if (context.getReloadableClasspath().isEmpty()) {
                fileManager = compiler.getStandardFileManager(fileManagerDiagnostics, null, context.getSourceEncoding());
            } else {
                final ReloadableJavaFileManager reloadableFM = new ReloadableJavaFileManager(compiler, fileManagerDiagnostics,
                        context);
                reloadableFM.setStaticClassPath(context.getClasspath());
                fileManager = reloadableFM;
            }
        }

        if (context.getReloadableClasspath().isEmpty()) {
            fileManager.setLocation(StandardLocation.CLASS_PATH, context.getClasspath());
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(context.getOutputDirectory()));
        } else {
            final ReloadableJavaFileManager reloadableFM = (ReloadableJavaFileManager) fileManager;
            reloadableFM.setClassOutput(List.of(context.getOutputDirectory()));
            reloadableFM.setReloadableClassPath(context.getReloadableClasspath());
        }

        return fileManager;
    }

    @Override
    public Path getSourcePath(Path classFilePath, PathCollection sourcePaths, String classesPath) {
        Path sourceFilePath = null;
        final RuntimeUpdatesClassVisitor visitor = new RuntimeUpdatesClassVisitor(sourcePaths, classesPath);
        try (final InputStream inputStream = Files.newInputStream(classFilePath)) {
            final ClassReader reader = new ClassReader(inputStream);
            reader.accept(visitor, 0);
            sourceFilePath = visitor.getSourceFileForClass(classFilePath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sourceFilePath;
    }

    @Override
    public void close() throws IOException {
        if (fileManager != null) {
            fileManager.close();
            fileManager = null;
        }
    }

    private void logDiagnostics(final DiagnosticCollector<JavaFileObject> diagnostics) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            Logger.Level level = diagnostic.getKind() == Diagnostic.Kind.ERROR ? Logger.Level.ERROR : Logger.Level.WARN;
            String message = diagnostic.getMessage(null);
            if (level.equals(Logger.Level.WARN) && ignoreWarningForNamespace(message)) {
                continue;
            }

            log.logf(level, "%s, line %d in %s", message, diagnostic.getLineNumber(),
                    diagnostic.getSource() == null ? "[unknown source]" : diagnostic.getSource().getName());
        }
    }

    private static boolean ignoreWarningForNamespace(String message) {
        for (String ignoreNamespace : IGNORE_NAMESPACES) {
            if (message.contains(ignoreNamespace)) {
                return true;
            }
        }
        return false;
    }

    static class RuntimeUpdatesClassVisitor extends ClassVisitor {
        private final PathCollection sourcePaths;
        private final String classesPath;
        private String sourceFile;

        public RuntimeUpdatesClassVisitor(PathCollection sourcePaths, String classesPath) {
            super(Gizmo.ASM_API_VERSION);
            this.sourcePaths = sourcePaths;
            this.classesPath = classesPath;
        }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFile = source;
        }

        public Path getSourceFileForClass(final Path classFilePath) {
            for (Path sourcesDir : sourcePaths) {
                final Path classesDir = Paths.get(classesPath);
                final StringBuilder sourceRelativeDir = new StringBuilder();
                sourceRelativeDir.append(classesDir.relativize(classFilePath.getParent()));
                sourceRelativeDir.append(File.separator);
                sourceRelativeDir.append(sourceFile);
                final Path sourceFilePath = sourcesDir.resolve(Path.of(sourceRelativeDir.toString()));
                if (Files.exists(sourceFilePath)) {
                    return sourceFilePath;
                }
            }

            return null;
        }
    }

}
