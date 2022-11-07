package io.quarkus.poc.update;

import static io.quarkus.poc.update.Utils.initMavenContext;
import static io.quarkus.poc.update.Utils.readAppConfig;

import java.nio.file.Path;
import java.util.Collection;

import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.configuration.ConfiguredApplication;
import picocli.CommandLine;

@CommandLine.Command(name = "info")
public class InfoCommand implements Runnable {

    @CommandLine.Parameters(arity = "0..1", description = "Project or module directory")
    Path projectDir;

    @Override
    public void run() {
        var log = MessageWriter.info();
        final Path projectDir = Utils.getProjectDir(this.projectDir);
        logConfiguredApps(readAppConfig(projectDir, initMavenContext(projectDir), log), log);
    }

    private static void logConfiguredApps(Collection<ConfiguredApplication> apps, MessageWriter log) {
        int i = 1;
        for (var app : apps) {
            log.info(i++ + ") Quarkus application " + app.getId());
            log.info("  Enforced platform BOMs:");
            for (var platformBom : app.getPlatformBoms()) {
                log.info("  - " + platformBom + ", " + platformBom.getArtifact().getConfigurationFile());
            }
            log.info("  Top extensions dependencies:");
            for (var topExtDep : app.getTopExtensionDependencies()) {
                log.info("  - " + topExtDep.toCompactString() + ", " + topExtDep.getConfigurationFile());
            }
            log.info("  Quarkus Maven plugin");
            log.info("  - " + app.getQuarkusPlugin().toCompactString());
        }
    }
}
