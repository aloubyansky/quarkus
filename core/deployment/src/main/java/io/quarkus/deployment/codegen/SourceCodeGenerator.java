package io.quarkus.deployment.codegen;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.deployment.builditem.GeneratedSourceCodeBuildItem;

public class SourceCodeGenerator {

    public static void generateCode(CuratedApplication application) {
        application.createAugmentor().performCustomBuild(GenerateCodeResultConsumer.class.getName(),
                new GenerateCodeContext(),
                GeneratedSourceCodeBuildItem.class.getName());

    }
}
