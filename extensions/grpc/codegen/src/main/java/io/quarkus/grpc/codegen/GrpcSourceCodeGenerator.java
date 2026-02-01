package io.quarkus.grpc.codegen;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.Arrays.asList;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.GeneratedSourceCodeBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathFilter;
import io.quarkus.runtime.util.HashUtil;
import io.smallrye.common.cpu.CPU;
import io.smallrye.common.os.OS;
import io.smallrye.common.process.ProcessBuilder;

public class GrpcSourceCodeGenerator {

    private static final Logger log = Logger.getLogger(GrpcSourceCodeGenerator.class);

    private static final String INPUT_DIRECTORY = "proto";

    private static final Logger grpcProcessOuputLogger = Logger.getLogger("protoc");

    private static final String quarkusProtocPluginMain = "io.quarkus.grpc.protoc.plugin.MutinyGrpcGenerator";
    private static final String EXE = "exe";
    private static final String PROTO = ".proto";
    private static final String PROTOC = "protoc";
    private static final String PROTOC_GROUPID = "com.google.protobuf";

    private static final String SCAN_DEPENDENCIES_FOR_PROTO = "quarkus.generate-code.grpc.scan-for-proto";
    private static final String SCAN_DEPENDENCIES_FOR_PROTO_INCLUDE_PATTERN = "quarkus.generate-code.grpc.scan-for-proto-include.\"%s\"";
    private static final String SCAN_DEPENDENCIES_FOR_PROTO_EXCLUDE_PATTERN = "quarkus.generate-code.grpc.scan-for-proto-exclude.\"%s\"";
    private static final String SCAN_FOR_IMPORTS = "quarkus.generate-code.grpc.scan-for-imports";

    private static final String POST_PROCESS_SKIP = "quarkus.generate.code.grpc-post-processing.skip";
    private static final String GENERATE_DESCRIPTOR_SET = "quarkus.generate-code.grpc.descriptor-set.generate";
    private static final String DESCRIPTOR_SET_OUTPUT_DIR = "quarkus.generate-code.grpc.descriptor-set.output-dir";
    private static final String DESCRIPTOR_SET_FILENAME = "quarkus.generate-code.grpc.descriptor-set.name";

    public static final String USE_ARG_FILE = "quarkus.generate-code.grpc.use-arg-file";

    private static final String GENERATE_KOTLIN = "quarkus.generate-code.grpc.kotlin.generate";

    @BuildStep
    GeneratedSourceCodeBuildItem generateSourceCode(OutputTargetBuildItem outputTargetBuildItem,
            LaunchModeBuildItem launchModeBuildItem,
            CurateOutcomeBuildItem curateOutcomeBuildItem) {

        Path workDir = outputTargetBuildItem.getOutputDirectory();
        Path outputDir = workDir
                .resolve("new-" + (launchModeBuildItem.isTest() ? "generate-test-sources" : "generated-sources"))
                .resolve("grpc");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Path inputDir = Path.of("src", "main", INPUT_DIRECTORY);

        Set<String> protoDirs = new LinkedHashSet<>();

        final Config config = ConfigProvider.getConfig();
        boolean useArgFile = config.getOptionalValue(USE_ARG_FILE, Boolean.class).orElse(false);

        try {
            List<String> protoFiles = new ArrayList<>();
            if (Files.isDirectory(inputDir)) {
                try (Stream<Path> protoFilesPaths = Files.walk(inputDir)) {
                    protoFilesPaths
                            .filter(Files::isRegularFile)
                            .filter(s -> s.toString().endsWith(PROTO))
                            .map(Path::normalize)
                            .map(Path::toAbsolutePath)
                            .map(Path::toString)
                            .forEach(protoFiles::add);
                    protoDirs.add(inputDir.normalize().toAbsolutePath().toString());
                }
            }
            Path dirWithProtosFromDependencies = workDir.resolve("protoc-protos-from-dependencies");

            Collection<Path> protoFilesFromDependencies = gatherProtosFromDependencies(config, dirWithProtosFromDependencies,
                    protoDirs,
                    launchModeBuildItem, curateOutcomeBuildItem);
            if (!protoFilesFromDependencies.isEmpty()) {
                for (Path files : protoFilesFromDependencies) {
                    var pathToProtoFile = files.normalize().toAbsolutePath();
                    var pathToParentDir = files.getParent();
                    // Add the proto file to the list of proto to compile, but also add the directory containing the
                    // proto file to the list of directories to include (it's a set, so no duplicate).
                    protoFiles.add(pathToProtoFile.toString());
                    protoDirs.add(pathToParentDir.toString());

                }
            }

            if (!protoFiles.isEmpty()) {
                GrpcCodeGen.Executables executables = initExecutables(workDir, curateOutcomeBuildItem.getApplicationModel());

                Collection<String> protosToImport = gatherDirectoriesWithImports(workDir.resolve("protoc-dependencies"),
                        config, curateOutcomeBuildItem);

                List<String> command = new ArrayList<>();
                command.add(executables.protoc.toString());

                for (String protoDir : protoDirs) {
                    command.add(String.format("-I=%s", escapeWhitespace(protoDir)));
                }
                for (String protoImportDir : protosToImport) {
                    command.add(String.format("-I=%s", escapeWhitespace(protoImportDir)));
                }

                command.addAll(asList("--plugin=protoc-gen-grpc=" + executables.grpc,
                        "--plugin=protoc-gen-q-grpc=" + executables.quarkusGrpc,
                        "--q-grpc_out=" + outputDir,
                        "--grpc_out=" + outputDir,
                        "--java_out=" + outputDir));

                if (shouldGenerateKotlin(config, curateOutcomeBuildItem)) {
                    command.add("--kotlin_out=" + outputDir);
                }

                if (shouldGenerateDescriptorSet(config)) {
                    command.add(
                            String.format("--descriptor_set_out=%s", getDescriptorSetOutputFile(config, workDir, outputDir)));
                }

                command.addAll(protoFiles);

                // Estimate command length to avoid command line too long error
                int commandLength = command.stream().mapToInt(String::length).sum();
                // 8191 is the maximum command line length for Windows
                if (useArgFile || (commandLength > 8190 && OS.current() == OS.WINDOWS)) {
                    File argFile = File.createTempFile("grpc-protoc-params", ".txt");
                    argFile.deleteOnExit();

                    try (PrintWriter writer = new PrintWriter(argFile, StandardCharsets.UTF_8)) {
                        for (int i = 1; i < command.size(); i++) {
                            writer.println(command.get(i));
                        }
                    }

                    command = new ArrayList<>(List.of(command.get(0), "@" + argFile.getAbsolutePath()));
                }
                log.debugf("Executing command: %s", String.join(" ", command));
                try {
                    io.smallrye.common.process.ProcessBuilder<Void> pb = ProcessBuilder.newBuilder(command.get(0),
                            command.subList(1, command.size()));
                    // Tune the environment for compatibility with Java >24 without triggering warnings
                    pb.modifyEnvironment(GrpcSourceCodeGenerator::invocationEnvironmentTuning);
                    // Set up a custom output handler to highlight only relevant errors
                    pb.output().consumeLinesWith(100, this::outputConsumer);
                    pb.error().consumeLinesWith(100, this::outputConsumer).logOnSuccess(false);
                    pb.run();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to generate Java classes from proto files: %s to %s with command %s"
                            .formatted(protoFiles, outputDir.toAbsolutePath(), String.join(" ", command)), e);
                }
                postprocessing(config, outputDir);
                log.info("Successfully finished generating and post-processing sources from proto files");
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to generate java files from proto file in " + inputDir.toAbsolutePath(), e);
        }

        GeneratedSourceCodeBuildItem result = new GeneratedSourceCodeBuildItem("java", outputDir);
        return result;
    }

    private void outputConsumer(final String line) {
        //Protoc will by default emit a message like "NOTE: Picked up JDK_JAVA_OPTIONS: [...],
        //which will lead the ProcessBuilder to emit a warning if not consumed.
        //So let's consume them here and emit as regular "info": if there's errors, they will be caught
        //as the return code is being checked as well.
        grpcProcessOuputLogger.info(line);
    }

    private void postprocessing(Config config, Path outDir) {
        if (TRUE.toString().equalsIgnoreCase(System.getProperties().getProperty(POST_PROCESS_SKIP, "false"))
                || config.getOptionalValue(POST_PROCESS_SKIP, Boolean.class).orElse(false)) {
            log.info("Skipping gRPC Post-Processing on user's request");
            return;
        }

        new GrpcPostProcessing(config, outDir).postprocess();

    }

    private static void invocationEnvironmentTuning(final Map<String, String> environment) {
        //This specific environment variable is being picked up by the JVMs spawned by protoc:
        final String key = "JDK_JAVA_OPTIONS";
        String existingValue = environment.get(key);
        if (existingValue == null || existingValue.isBlank()) {
            existingValue = "";
        }
        StringBuilder sb = new StringBuilder();
        //Each of these require custom logic to ensure we don't override an explicit user setting

        if (!existingValue.contains("-Dsun.stdout.encoding=")) {
            //This one is always useful, especially on Java 17:
            sb.append("-Dsun.stdout.encoding=UTF-8 ");
        }

        //Do NOT set this property on Java 17, as it will fail with "unrecognized option":
        if (Runtime.version().feature() > 21 && !existingValue.contains("--sun-misc-unsafe-memory-access=")) {
            sb.append("--sun-misc-unsafe-memory-access=allow ");
        }
        sb.append(existingValue);
        environment.put(key, sb.toString().trim());
    }

    private boolean shouldGenerateDescriptorSet(Config config) {
        return config.getOptionalValue(GENERATE_DESCRIPTOR_SET, Boolean.class).orElse(FALSE);
    }

    private Path getDescriptorSetOutputFile(Config config, Path workDir, Path outputDir) throws IOException {
        var dscOutputDir = config.getOptionalValue(DESCRIPTOR_SET_OUTPUT_DIR, String.class)
                .map(workDir::resolve)
                .orElse(outputDir);

        if (Files.notExists(dscOutputDir)) {
            Files.createDirectories(dscOutputDir);
        }

        var dscFilename = config.getOptionalValue(DESCRIPTOR_SET_FILENAME, String.class)
                .orElse("descriptor_set.dsc");

        return dscOutputDir.resolve(dscFilename).normalize();
    }

    private boolean shouldGenerateKotlin(Config config, CurateOutcomeBuildItem curateOutcomeBuildItem) {
        return config.getOptionalValue(GENERATE_KOTLIN, Boolean.class).orElse(
                containsQuarkusKotlin(curateOutcomeBuildItem.getApplicationModel().getRuntimeDependencies()));
    }

    private static boolean containsQuarkusKotlin(Collection<ResolvedDependency> dependencies) {
        return dependencies.stream().anyMatch(new Predicate<ResolvedDependency>() {
            @Override
            public boolean test(ResolvedDependency rd) {
                return rd.getGroupId().equalsIgnoreCase("io.quarkus")
                        && rd.getArtifactId().equalsIgnoreCase("quarkus-kotlin");
            }
        });
    }

    private String escapeWhitespace(String path) {
        if (OS.current() == OS.LINUX) {
            return path.replace(" ", "\\ ");
        } else {
            return path;
        }
    }

    private Collection<String> gatherDirectoriesWithImports(Path workDir, Config properties,
            CurateOutcomeBuildItem curateOutcomeBuildItem) {

        String scanForImports = properties.getOptionalValue(SCAN_FOR_IMPORTS, String.class)
                .orElse("com.google.protobuf:protobuf-java");

        if ("none".equals(scanForImports.toLowerCase(Locale.getDefault()))) {
            return Collections.emptyList();
        }

        boolean scanAll = "all".equals(scanForImports.toLowerCase(Locale.getDefault()));
        List<String> dependenciesToScan = Arrays.stream(scanForImports.split(",")).map(String::trim)
                .collect(Collectors.toList());

        Set<String> importDirectories = new HashSet<>();
        ApplicationModel appModel = curateOutcomeBuildItem.getApplicationModel();
        for (ResolvedDependency artifact : appModel.getRuntimeDependencies()) {
            if (scanAll
                    || dependenciesToScan.contains(
                            String.format("%s:%s", artifact.getGroupId(), artifact.getArtifactId()))) {
                extractProtosFromArtifact(workDir, new ArrayList<>(), importDirectories, artifact, List.of(),
                        List.of(), false);
            }
        }
        return importDirectories;
    }

    private GrpcCodeGen.Executables initExecutables(Path workDir, ApplicationModel model) {
        Path protocPath;
        String protocPathProperty = System.getProperty("quarkus.grpc.protoc-path");
        String classifier = System.getProperty("quarkus.grpc.protoc-os-classifier", osClassifier());
        Path protocExe;
        if (protocPathProperty == null) {
            protocPath = findArtifactPath(model, PROTOC_GROUPID, PROTOC, classifier, EXE);
            protocExe = makeExecutableFromPath(workDir, PROTOC_GROUPID, PROTOC, classifier, "exe", protocPath);
        } else {
            log.debugf("Using protoc from %s", protocPathProperty);
            protocPath = Paths.get(protocPathProperty);
            protocExe = protocPath;
        }

        Path protocGrpcPluginExe = prepareExecutable(workDir, model,
                "io.grpc", "protoc-gen-grpc-java", classifier, "exe");

        Path quarkusGrpcPluginExe = prepareQuarkusGrpcExecutable(model, workDir);

        return new GrpcCodeGen.Executables(protocExe, protocGrpcPluginExe, quarkusGrpcPluginExe);
    }

    private String osClassifier() {
        String architecture = getArchitecture();
        return switch (OS.current()) {
            case LINUX -> "linux-" + architecture;
            case WINDOWS -> "windows-" + architecture;
            case MAC -> "osx-" + architecture;
            default -> throw new RuntimeException(
                    "Unsupported OS, please use maven plugin instead to generate Java classes from proto files");
        };
    }

    /**
     * {@return the bespoke architecture string, or {@code null} if unknown}
     */
    private static String getArchitecture() {
        return switch (CPU.host()) {
            case x64 -> "x86_64";
            case x86 -> "x86_32";
            case arm -> "arm_32";
            case aarch64 -> "aarch_64";
            case mips -> "mips_32";
            case mipsel -> "mipsel_32";
            case mips64 -> "mips_64";
            case mips64el -> "mipsel_64";
            case ppc32 -> "ppc_32";
            case ppc32le -> "ppcle_32";
            case ppc -> "ppc_64";
            case ppcle -> "ppcle_64";
            default -> null;
        };
    }

    private static Path writeScript(Path buildDir, Path pluginPath, String shebang, String suffix) {
        Path script;
        try {
            script = Files.createTempFile(buildDir, "quarkus-grpc", suffix);
            try (BufferedWriter writer = Files.newBufferedWriter(script)) {
                writer.write(shebang);
                writePluginExeCmd(pluginPath, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create a wrapper script for quarkus-grpc plugin", e);
        }
        if (!script.toFile().setExecutable(true)) {
            throw new RuntimeException("failed to set file: " + script + " executable. Protoc invocation may fail");
        }
        return script;
    }

    private static void writePluginExeCmd(Path pluginPath, BufferedWriter writer) throws IOException {
        writer.write("\"" + io.smallrye.common.process.ProcessUtil.pathOfJava().toString() + "\" -cp \"" +
                pluginPath.toAbsolutePath() + "\" " + quarkusProtocPluginMain);
        writer.newLine();
    }

    private static Path prepareQuarkusGrpcExecutable(ApplicationModel appModel, Path buildDir) {
        Path pluginPath = findArtifactPath(appModel, "io.quarkus", "quarkus-grpc-protoc-plugin", "shaded", "jar");
        if (pluginPath == null) {
            throw new RuntimeException("Failed to find Quarkus gRPC protoc plugin among dependencies");
        }

        if (OS.current() != OS.WINDOWS) {
            return writeScript(buildDir, pluginPath, "#!/bin/sh\n", ".sh");
        } else {
            return writeScript(buildDir, pluginPath, "@echo off\r\n", ".cmd");
        }
    }

    private Path prepareExecutable(Path buildDir, ApplicationModel model,
            String groupId, String artifactId, String classifier, String packaging) {
        Path artifactPath = findArtifactPath(model, groupId, artifactId, classifier, packaging);

        return makeExecutableFromPath(buildDir, groupId, artifactId, classifier, packaging, artifactPath);
    }

    private Path makeExecutableFromPath(Path buildDir, String groupId, String artifactId, String classifier, String packaging,
            Path artifactPath) {
        Path exe = buildDir.resolve(String.format("%s-%s-%s-%s", groupId, artifactId, classifier, packaging));

        if (Files.exists(exe)) {
            return exe;
        }

        if (artifactPath == null) {
            String location = String.format("%s:%s:%s:%s", groupId, artifactId, classifier, packaging);
            throw new RuntimeException("Failed to find " + location + " among dependencies");
        }

        try {
            Files.copy(artifactPath, exe, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy file: " + artifactPath + " to " + exe, e);
        }
        if (!exe.toFile().setExecutable(true)) {
            throw new RuntimeException("Failed to make the file executable: " + exe);
        }
        return exe;
    }

    private static Path findArtifactPath(ApplicationModel model, String groupId, String artifactId, String classifier,
            String packaging) {
        Path artifactPath = null;

        for (ResolvedDependency artifact : model.getDependencies()) {
            if (groupId.equals(artifact.getGroupId())
                    && artifactId.equals(artifact.getArtifactId())
                    && classifier.equals(artifact.getClassifier())
                    && packaging.equals(artifact.getType())) {
                artifactPath = artifact.getResolvedPaths().getSinglePath();
            }
        }
        return artifactPath;
    }

    private Collection<Path> gatherProtosFromDependencies(Config properties, Path workDir, Set<String> protoDirectories,
            LaunchModeBuildItem launchModeBuildItem,
            CurateOutcomeBuildItem curateOutcomeBuildItem) {
        if (launchModeBuildItem.isTest()) {
            return Collections.emptyList();
        }
        String scanDependencies = properties.getOptionalValue(SCAN_DEPENDENCIES_FOR_PROTO, String.class)
                .orElse("none");

        if ("none".equalsIgnoreCase(scanDependencies)) {
            return Collections.emptyList();
        }
        boolean scanAll = "all".equalsIgnoreCase(scanDependencies);

        List<String> dependenciesToScan = Arrays.stream(scanDependencies.split(",")).map(String::trim)
                .collect(Collectors.toList());

        ApplicationModel appModel = curateOutcomeBuildItem.getApplicationModel();
        List<Path> protoFilesFromDependencies = new ArrayList<>();
        for (ResolvedDependency artifact : appModel.getRuntimeDependencies()) {
            String packageId = String.format("%s:%s", artifact.getGroupId(), artifact.getArtifactId());
            Collection<String> includes = properties
                    .getOptionalValue(String.format(SCAN_DEPENDENCIES_FOR_PROTO_INCLUDE_PATTERN, packageId), String.class)
                    .map(s -> Arrays.stream(s.split(",")).map(String::trim).collect(Collectors.toList()))
                    .orElse(List.of());

            Collection<String> excludes = properties
                    .getOptionalValue(String.format(SCAN_DEPENDENCIES_FOR_PROTO_EXCLUDE_PATTERN, packageId), String.class)
                    .map(s -> Arrays.stream(s.split(",")).map(String::trim).collect(Collectors.toList()))
                    .orElse(List.of());

            if (scanAll
                    || dependenciesToScan.contains(packageId)) {
                extractProtosFromArtifact(workDir, protoFilesFromDependencies, protoDirectories, artifact, includes, excludes,
                        true);
            }
        }
        return protoFilesFromDependencies;
    }

    private void extractProtosFromArtifact(Path workDir, Collection<Path> protoFiles,
            Set<String> protoDirectories, ResolvedDependency artifact, Collection<String> filesToInclude,
            Collection<String> filesToExclude, boolean isDependency) {

        artifact.getContentTree(new PathFilter(filesToInclude, filesToExclude)).walk(
                pathVisit -> {
                    Path path = pathVisit.getPath();
                    if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(PROTO)) {
                        Path root = pathVisit.getRoot();
                        if (Files.isDirectory(root)) {
                            protoFiles.add(path);
                            protoDirectories.add(path.getParent().normalize().toAbsolutePath().toString());
                        } else { // archive
                            Path relativePath = path.getRoot().relativize(path);
                            String uniqueName = artifact.getGroupId() + ":" + artifact.getArtifactId();
                            if (artifact.getVersion() != null) {
                                uniqueName += ":" + artifact.getVersion();
                            }
                            if (artifact.getClassifier() != null) {
                                uniqueName += "-" + artifact.getClassifier();
                            }
                            Path protoUnzipDir = workDir
                                    .resolve(HashUtil.sha1(uniqueName))
                                    .normalize().toAbsolutePath();
                            try {
                                Files.createDirectories(protoUnzipDir);
                                protoDirectories.add(protoUnzipDir.toString());
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to create directory: " + protoUnzipDir, e);
                            }
                            Path outPath = protoUnzipDir;
                            for (Path part : relativePath) {
                                outPath = outPath.resolve(part.toString());
                            }
                            try {
                                Files.createDirectories(outPath.getParent());
                                if (isDependency) {
                                    copySanitizedProtoFile(artifact, path, outPath);
                                } else {
                                    Files.copy(path, outPath, StandardCopyOption.REPLACE_EXISTING);
                                }
                                protoFiles.add(outPath);
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to extract proto file" + path + " to target: "
                                        + outPath, e);
                            }
                        }
                    }
                });
    }

    private static void copySanitizedProtoFile(ResolvedDependency artifact, Path protoPath, Path outProtoPath)
            throws IOException {
        boolean genericServicesFound = false;

        try (var reader = Files.newBufferedReader(protoPath);
                var writer = Files.newBufferedWriter(outProtoPath)) {

            String line = reader.readLine();
            while (line != null) {
                // filter java_generic_services to avoid "Tried to write the same file twice"
                // when set to true. Generic services are deprecated and replaced by classes generated by
                // this plugin
                if (!line.contains("java_generic_services")) {
                    writer.write(line);
                    writer.newLine();
                } else {
                    genericServicesFound = true;
                }

                line = reader.readLine();
            }
        }

        if (genericServicesFound) {
            log.infof("Ignoring option java_generic_services in %s:%s%s.", artifact.getGroupId(), artifact.getArtifactId(),
                    protoPath);
        }
    }

}
