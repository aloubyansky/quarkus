package io.quarkus.deployment.codegen;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.Properties;
import java.util.function.Consumer;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.deployment.CodeGenerator;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.GeneratedSourceCodeBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.paths.PathCollection;
import io.quarkus.paths.PathList;
import io.quarkus.runtime.LaunchMode;

public class SourceCodeGenerator {

    public static void generateCode(CuratedApplication application, Consumer<Path> sourceRegistrar) {
        application.createAugmentor().performCustomBuild(GenerateCodeResultConsumer.class.getName(),
                sourceRegistrar,
                GeneratedSourceCodeBuildItem.class.getName());

    }

    @BuildStep
    void generateSourceCode(BuildProducer<GeneratedSourceCodeBuildItem> generatedSourceCodeProducer,
            OutputTargetBuildItem outputTargetBuildItem,
            LaunchModeBuildItem launchModeBuildItem,
            CurateOutcomeBuildItem curateOutcomeBuildItem) {

        final WorkspaceModule wsModule = curateOutcomeBuildItem.getApplicationModel().getApplicationModule();
        if (wsModule == null) {
            return;
        }

        final boolean test = launchModeBuildItem.getLaunchMode().equals(LaunchMode.TEST);
        final Collection<SourceDir> sourceDirs = (test ? wsModule.getTestSources() : wsModule.getMainSources()).getSourceDirs();
        final PathCollection sourceParentDirs = getParentDirs(sourceDirs);

        try {
            CodeGenerator.initAndRun((QuarkusClassLoader) Thread.currentThread().getContextClassLoader(),
                    sourceParentDirs,
                    generatedSourcesDir(test, outputTargetBuildItem.getOutputDirectory()),
                    outputTargetBuildItem.getOutputDirectory(),
                    path -> generatedSourceCodeProducer.produce(new GeneratedSourceCodeBuildItem("java", path)),
                    curateOutcomeBuildItem.getApplicationModel(),
                    new Properties(),
                    launchModeBuildItem.getLaunchMode().toString(),
                    test);
        } catch (CodeGenException e) {
            throw new RuntimeException(e);
        }
    }

    protected PathCollection getParentDirs(Collection<SourceDir> sourceDirs) {
        final Iterator<SourceDir> i = sourceDirs.iterator();
        if (sourceDirs.size() == 1) {
            return PathList.of(i.next().getDir().getParent());
        }
        final PathList.Builder builder = PathList.builder();
        while (i.hasNext()) {
            final Path parentDir = i.next().getDir().getParent();
            if (!builder.contains(parentDir)) {
                builder.add(parentDir);
            }
        }
        return builder.build();
    }

    private Path generatedSourcesDir(boolean test, Path buildDir) {
        return test ? buildDir.resolve("generated-test-sources") : buildDir.resolve("generated-sources");
    }
}
