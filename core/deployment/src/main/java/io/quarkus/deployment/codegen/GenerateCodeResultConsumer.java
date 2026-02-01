package io.quarkus.deployment.codegen;

import java.util.function.BiConsumer;

import io.quarkus.builder.BuildResult;
import io.quarkus.deployment.builditem.GeneratedSourceCodeBuildItem;

public class GenerateCodeResultConsumer implements BiConsumer<GenerateCodeContext, BuildResult> {
    @Override
    public void accept(GenerateCodeContext generateCodeContext, BuildResult buildResult) {
        System.out.println("Source code generation complete: " + buildResult);
        for (var sourceCodeBuildItem : buildResult.consumeMulti(GeneratedSourceCodeBuildItem.class)) {
            System.out.println("Source code generation complete: " + sourceCodeBuildItem);
        }
    }
}
