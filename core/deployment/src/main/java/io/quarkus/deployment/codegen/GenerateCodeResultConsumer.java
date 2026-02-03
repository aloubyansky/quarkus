package io.quarkus.deployment.codegen;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.quarkus.builder.BuildResult;
import io.quarkus.deployment.builditem.GeneratedSourceCodeBuildItem;

public class GenerateCodeResultConsumer implements BiConsumer<Consumer<Path>, BuildResult> {
    @Override
    public void accept(Consumer<Path> sourceRegistrar, BuildResult buildResult) {
        for (var sourceCodeBuildItem : buildResult.consumeMulti(GeneratedSourceCodeBuildItem.class)) {
            sourceRegistrar.accept(sourceCodeBuildItem.getDirectory());
        }
    }
}
