package io.quarkus.poc.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.configuration.ConfiguredApplication;
import io.quarkus.devtools.project.configuration.maven.MavenConfiguredApplicationResolver;
import io.quarkus.devtools.project.configuration.maven.MavenProjectConfigurationLoader;

class Utils {

    static Path getProjectDir(Path arg) {
        if (arg == null) {
            return Path.of("").normalize().toAbsolutePath();
        }
        if (!Files.isDirectory(arg)) {
            throw new IllegalArgumentException(arg + " is not a directory");
        }
        return arg;
    }

    static BootstrapMavenContext initMavenContext(Path projectDir) {
        try {
            return MavenProjectConfigurationLoader.getBootstrapMavenContext(projectDir);
        } catch (BootstrapMavenException e) {
            throw new RuntimeException("Failed to initialize Maven artifact resolver", e);
        }
    }

    static Collection<ConfiguredApplication> readAppConfig(Path projectDir, BootstrapMavenContext mavenCtx, MessageWriter log) {
        try {
            return MavenConfiguredApplicationResolver.load(projectDir, mavenCtx, log);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load project configuration", e);
        }
    }
}
