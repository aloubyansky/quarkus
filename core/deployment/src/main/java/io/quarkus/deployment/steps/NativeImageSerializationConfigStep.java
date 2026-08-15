package io.quarkus.deployment.steps;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.quarkus.bootstrap.json.Json;
import io.quarkus.bootstrap.json.Json.JsonArrayBuilder;
import io.quarkus.bootstrap.json.Json.JsonObjectBuilder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.LambdaCapturingTypeBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;

public class NativeImageSerializationConfigStep {

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void generateSerializationConfig(BuildProducer<GeneratedResourceBuildItem> serializationConfig,
            List<ReflectiveClassBuildItem> reflectiveClassBuildItems,
            List<LambdaCapturingTypeBuildItem> lambdaCapturingTypeBuildItems) {

        final Set<String> serializableClasses = new HashSet<>();
        for (ReflectiveClassBuildItem i : reflectiveClassBuildItems) {
            if (i.isSerialization()) {
                String[] classNames = i.getClassNames().toArray(new String[0]);
                Collections.addAll(serializableClasses, classNames);
            }
        }

        JsonObjectBuilder root = Json.object();
        JsonArrayBuilder types = Json.array();
        for (String serializableClass : serializableClasses) {
            types.append(Json.object().set("name", serializableClass));
        }
        root.set("types", types);

        JsonArrayBuilder lambdaCapturingTypes = Json.array();
        if (!lambdaCapturingTypeBuildItems.isEmpty()) {
            for (LambdaCapturingTypeBuildItem i : lambdaCapturingTypeBuildItems) {
                lambdaCapturingTypes.append(Json.object().set("name", i.getClassName()));
            }
        }
        root.set("lambdaCapturingTypes", lambdaCapturingTypes);

        serializationConfig.produce(new GeneratedResourceBuildItem("META-INF/native-image/serialization-config.json",
                root.toJsonString().getBytes(StandardCharsets.UTF_8)));
    }

}
