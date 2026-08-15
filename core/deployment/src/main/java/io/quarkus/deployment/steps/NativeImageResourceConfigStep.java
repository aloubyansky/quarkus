package io.quarkus.deployment.steps;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import io.quarkus.bootstrap.json.Json;
import io.quarkus.bootstrap.json.Json.JsonArrayBuilder;
import io.quarkus.bootstrap.json.Json.JsonObjectBuilder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;

public class NativeImageResourceConfigStep {

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void generateResourceConfig(BuildProducer<GeneratedResourceBuildItem> resourceConfig,
            List<NativeImageResourcePatternsBuildItem> resourcePatterns,
            List<NativeImageResourceBundleBuildItem> resourceBundles,
            List<NativeImageResourceBuildItem> resources,
            List<ServiceProviderBuildItem> serviceProviderBuildItems) {
        JsonObjectBuilder root = Json.object();

        JsonObjectBuilder resourcesJs = Json.object();
        JsonArrayBuilder includes = Json.array();
        JsonArrayBuilder excludes = Json.array();

        for (NativeImageResourceBuildItem i : resources) {
            for (String path : i.getResources()) {
                JsonObjectBuilder pat = Json.object();
                pat.set("pattern", Pattern.quote(path));
                includes.append(pat);
            }
        }

        for (ServiceProviderBuildItem i : serviceProviderBuildItems) {
            includes.append(Json.object().set("pattern", Pattern.quote(i.serviceDescriptorFile())));
        }

        for (NativeImageResourcePatternsBuildItem resourcePatternsItem : resourcePatterns) {
            addListToJsonArray(includes, resourcePatternsItem.getIncludePatterns());
            addListToJsonArray(excludes, resourcePatternsItem.getExcludePatterns());
        }
        resourcesJs.set("includes", includes);
        resourcesJs.set("excludes", excludes);
        root.set("resources", resourcesJs);

        JsonArrayBuilder bundles = Json.array();
        for (NativeImageResourceBundleBuildItem i : resourceBundles) {
            JsonObjectBuilder bundle = Json.object();
            String moduleName = i.getModuleName();
            StringBuilder sb = new StringBuilder();
            if (moduleName != null) {
                sb.append(moduleName).append(":");
            }
            sb.append(i.getBundleName().replace("/", "."));
            bundle.set("name", sb.toString());
            bundles.append(bundle);
        }
        root.set("bundles", bundles);

        resourceConfig.produce(new GeneratedResourceBuildItem("META-INF/native-image/resource-config.json",
                root.toJsonString().getBytes(StandardCharsets.UTF_8)));
    }

    private void addListToJsonArray(JsonArrayBuilder array, List<String> patterns) {
        for (String pattern : patterns) {
            JsonObjectBuilder pat = Json.object();
            pat.set("pattern", pattern);
            array.append(pat);
        }
    }
}
