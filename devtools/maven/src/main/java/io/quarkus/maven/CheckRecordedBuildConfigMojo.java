package io.quarkus.maven;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.runtime.LaunchMode;

@Mojo(name = "check-recorded-build-config", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class CheckRecordedBuildConfigMojo extends QuarkusBootstrapMojo {

    /**
     * Skip the execution of this mojo
     */
    @Parameter(defaultValue = "false", property = "quarkus.dump-config.skip")
    boolean skip = false;

    @Parameter(property = "launchMode")
    String mode;

    @Parameter(property = "quarkus.check-build-config.outputDirectory", defaultValue = "${project.build.directory}")
    File outputDirectory;

    @Parameter(property = "quarkus.check-build-config.outputFile", required = false)
    File outputFile;

    @Parameter(property = "quarkus.recorded-build-config.directory", defaultValue = "${basedir}/.quarkus")
    File recordedBuildConfigDirectory;

    @Parameter(property = "quarkus.recorded-build-config.file", required = false)
    File recordedBuildConfigFile;

    @Override
    protected boolean beforeExecute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping config dump");
            return false;
        }
        return true;
    }

    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        final String lifecyclePhase = mojoExecution.getLifecyclePhase();
        if (mode == null) {
            if (lifecyclePhase == null) {
                mode = "NORMAL";
            } else {
                mode = lifecyclePhase.contains("test") ? "TEST" : "NORMAL";
            }
        }
        final LaunchMode launchMode = LaunchMode.valueOf(mode);
        if (getLog().isDebugEnabled()) {
            getLog().debug("Bootstrapping Quarkus application in mode " + launchMode);
        }

        Path targetFile;
        if (outputFile == null) {
            targetFile = outputDirectory.toPath()
                    .resolve("quarkus-" + launchMode.getDefaultProfile() + "-config-check");
        } else if (outputFile.isAbsolute()) {
            targetFile = outputFile.toPath();
        } else {
            targetFile = outputDirectory.toPath().resolve(outputFile.toPath());
        }

        Path compareFile;
        if (this.recordedBuildConfigFile == null) {
            compareFile = recordedBuildConfigDirectory.toPath()
                    .resolve("quarkus-" + launchMode.getDefaultProfile() + "-config-dump");
        } else if (this.recordedBuildConfigFile.isAbsolute()) {
            compareFile = this.recordedBuildConfigFile.toPath();
        } else {
            compareFile = recordedBuildConfigDirectory.toPath().resolve(this.recordedBuildConfigFile.toPath());
        }

        if (!Files.exists(compareFile)) {
            getLog().info(compareFile + " not found");
            return;
        }
        final Properties compareProps = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(compareFile)) {
            compareProps.load(reader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + compareFile, e);
        }

        CuratedApplication curatedApplication = null;
        QuarkusClassLoader deploymentClassLoader = null;
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        Properties actualProps;
        try {
            curatedApplication = bootstrapApplication(launchMode);
            deploymentClassLoader = curatedApplication.createDeploymentClassLoader();
            Thread.currentThread().setContextClassLoader(deploymentClassLoader);

            final Class<?> codeGenerator = deploymentClassLoader.loadClass("io.quarkus.deployment.CodeGenerator");
            final Method dumpConfig = codeGenerator.getMethod("readCurrentValues", ApplicationModel.class, String.class,
                    Properties.class, QuarkusClassLoader.class, Properties.class);
            actualProps = (Properties) dumpConfig.invoke(null, curatedApplication.getApplicationModel(),
                    launchMode.name(), getBuildSystemProperties(true),
                    deploymentClassLoader, compareProps);
        } catch (Exception any) {
            throw new MojoExecutionException("Failed to bootstrap Quarkus application", any);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
            if (deploymentClassLoader != null) {
                deploymentClassLoader.close();
            }
        }

        final List<String> names = new ArrayList<>(actualProps.stringPropertyNames());
        Collections.sort(names);

        final Path outputDir = targetFile.getParent();
        if (outputDir != null && !Files.exists(outputDir)) {
            try {
                Files.createDirectories(outputDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(targetFile)) {
            for (var name : names) {
                writer.write(name);
                writer.write('=');
                writer.write(actualProps.getProperty(name));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
