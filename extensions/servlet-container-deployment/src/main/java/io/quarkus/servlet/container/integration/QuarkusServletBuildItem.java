package io.quarkus.servlet.container.integration;

import java.util.List;
import java.util.Map;

import io.quarkus.builder.item.MultiBuildItem;

public final class QuarkusServletBuildItem extends MultiBuildItem {

    private final String name;
    private final String servletClass;
    private final int loadOnStartup;
    private final boolean asyncSupported;
    private final List<String> mappings;
    private final Map<String, String> initParams;

    public QuarkusServletBuildItem(String name, String servletClass, int loadOnStartup, boolean asyncSupported,
            List<String> mappings,
            Map<String, String> initParams) {
        super();
        this.name = name;
        this.servletClass = servletClass;
        this.loadOnStartup = loadOnStartup;
        this.asyncSupported = asyncSupported;
        this.mappings = mappings;
        this.initParams = initParams;
    }

    public String getName() {
        return name;
    }

    public String getServletClass() {
        return servletClass;
    }

    public List<String> getMappings() {
        return mappings;
    }

    public int getLoadOnStartup() {
        return loadOnStartup;
    }

    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    public Map<String, String> getInitParams() {
        return initParams;
    }
}
