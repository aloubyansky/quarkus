package io.quarkus.devui.deployment.menu;

import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ConfigurationBuildItem;
import io.quarkus.devui.deployment.InternalPageBuildItem;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import io.quarkus.vertx.http.runtime.devmode.ResourceNotFoundData;

/**
 * This creates Endpoints Page
 */
public class EndpointsProcessor {
    private static final String NAMESPACE = "devui-endpoints";
    private static final String DEVUI = "dev-ui";

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    InternalPageBuildItem createEndpointsPage(Capabilities capabilities, ConfigurationBuildItem configurationBuildItem,
            NonApplicationRootPathBuildItem nonApplicationRootPathBuildItem) {

        final boolean swaggerIsAvailable = capabilities.isPresent(Capability.SMALLRYE_OPENAPI);
        final String swaggerUiPath;
        if (swaggerIsAvailable) {
            swaggerUiPath = nonApplicationRootPathBuildItem.resolvePath(
                    getProperty(configurationBuildItem, "quarkus.swagger-ui.path"));
        } else {
            swaggerUiPath = "";
        }

        String basepath = nonApplicationRootPathBuildItem.resolvePath(DEVUI);

        InternalPageBuildItem endpointsPage = new InternalPageBuildItem("Endpoints", 25);

        endpointsPage.addBuildTimeData("basepath", basepath);
        endpointsPage.addBuildTimeData("swaggerUiPath", swaggerUiPath);

        // Page
        endpointsPage.addPage(Page.webComponentPageBuilder()
                .namespace(NAMESPACE)
                .title("Endpoints")
                .icon("font-awesome-solid:plug")
                .componentLink("qwc-endpoints.js"));

        endpointsPage.addPage(Page.webComponentPageBuilder()
                .namespace(NAMESPACE)
                .title("Routes")
                .icon("font-awesome-solid:route")
                .componentLink("qwc-routes.js"));

        return endpointsPage;
    }

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    JsonRPCProvidersBuildItem createJsonRPCService() {
        return new JsonRPCProvidersBuildItem(NAMESPACE, ResourceNotFoundData.class);
    }

    private static String getProperty(ConfigurationBuildItem configurationBuildItem,
            String propertyKey) {

        String propertyValue = configurationBuildItem
                .getReadResult()
                .getAllBuildTimeValues()
                .get(propertyKey);

        if (propertyValue == null) {
            propertyValue = configurationBuildItem
                    .getReadResult()
                    .getBuildTimeRunTimeValues()
                    .get(propertyKey);
        } else {
            return propertyValue;
        }

        if (propertyValue == null) {
            propertyValue = configurationBuildItem
                    .getReadResult()
                    .getRunTimeDefaultValues()
                    .get(propertyKey);
        }

        return propertyValue;
    }
}
