package io.quarkus.poc.update;

import static io.quarkus.poc.update.Utils.initMavenContext;
import static io.quarkus.poc.update.Utils.readAppConfig;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.devtools.commands.data.QuarkusCommandException;
import io.quarkus.devtools.commands.handlers.RegistryProjectInfo;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.configuration.ConfiguredApplication;
import io.quarkus.devtools.project.configuration.ConfiguredArtifact;
import io.quarkus.devtools.project.configuration.step.UpdateSteps;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.registry.ExtensionCatalogResolver;
import io.quarkus.registry.RegistryResolutionException;
import io.quarkus.registry.catalog.ExtensionCatalog;
import picocli.CommandLine;

@CommandLine.Command(name = "update")
public class UpdateCommand implements Runnable {

    @CommandLine.Parameters(arity = "0..1", description = "Project or module directory")
    Path projectDir;

    @Override
    public void run() {
        var log = MessageWriter.info();
        final Path projectDir = Utils.getProjectDir(this.projectDir);
        var mavenCtx = initMavenContext(projectDir);
        logUpdateSteps(readAppConfig(projectDir, mavenCtx, log), mavenCtx, log);
    }

    private static void logUpdateSteps(Collection<ConfiguredApplication> apps, BootstrapMavenContext mavenCtx,
            MessageWriter log) {
        var extensionCatalog = getCatalogFromRegistry(apps, mavenCtx, log);
        var updateSteps = new UpdateSteps();
        for (var app : apps) {
            addUpdateSteps(updateSteps, app, extensionCatalog, log);
        }
        var updateStepList = updateSteps.asList();
        if (updateStepList.isEmpty()) {
            log.info("No updates recommended");
        } else {
            log.info("Recommended updates:");
            for (var updateStep : updateStepList) {
                log.info(updateStep.toString());
            }
        }
    }

    private static void addUpdateSteps(UpdateSteps updateSteps, ConfiguredApplication app, ExtensionCatalog extensionCatalog,
            MessageWriter log) {
        log.info("Quarkus application " + app.getId());
        var extDeps = getTopExtensionMap(app);
        final RegistryProjectInfo registryInfo;
        try {
            registryInfo = RegistryProjectInfo.fromCatalogWithKeys(extensionCatalog, extDeps.keySet());
        } catch (QuarkusCommandException e) {
            throw new RuntimeException(e);
        }
        UpdateSteps.addUpdateSteps(app, registryInfo, updateSteps);
    }

    private static Map<ArtifactKey, ConfiguredArtifact> getTopExtensionMap(ConfiguredApplication quarkusApp) {
        final List<ConfiguredArtifact> exts = quarkusApp.getTopExtensionDependencies();
        final Map<ArtifactKey, ConfiguredArtifact> topExtensions = new HashMap<>(exts.size());
        for (var e : exts) {
            topExtensions.put(e.getKey(), e);
        }
        return topExtensions;
    }

    private static ExtensionCatalog getCatalogFromRegistry(Collection<ConfiguredApplication> apps,
            BootstrapMavenContext mavenCtx, MessageWriter log) {
        final ExtensionCatalogResolver registryClient;
        try {
            registryClient = registryClient(mavenCtx, log);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize the registry client", e);
        }
        //var extensionCatalog = registryClient.resolveExtensionCatalog(toArtifactCoordsList(quarkusApp.getPlatformBoms()));
        final ExtensionCatalog extensionCatalog;
        try {
            extensionCatalog = registryClient.resolveExtensionCatalog();
        } catch (RegistryResolutionException e) {
            throw new RuntimeException("Failed to resolve extension catalog", e);
        }
        return extensionCatalog;
    }

    private static ExtensionCatalogResolver registryClient(BootstrapMavenContext mvnCtx, MessageWriter log) throws Exception {
        return ExtensionCatalogResolver.builder()
                .artifactResolver(new MavenArtifactResolver(mvnCtx))
                .messageWriter(log)
                .build();
    }
}
