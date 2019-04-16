package io.quarkus.servlet.container.integration;

import java.util.List;
import java.util.Map;

import io.quarkus.builder.item.MultiBuildItem;

public final class QuarkusServletFilterBuildItem extends MultiBuildItem {

    private final String name;
    private final String filterClass;
    private final int loadOnStartup;
    private final boolean asyncSupported;
    private final List<QuarkusServletFilterMappingInfo> mappings;
    private final Map<String, String> initParams;

    public QuarkusServletFilterBuildItem(String name, String filterClass, int loadOnStartup, boolean asyncSupported,
            List<QuarkusServletFilterMappingInfo> mappings, Map<String, String> initParams) {
        super();
        this.name = name;
        this.filterClass = filterClass;
        this.loadOnStartup = loadOnStartup;
        this.asyncSupported = asyncSupported;
        this.mappings = mappings;
        this.initParams = initParams;
    }

    public String getName() {
        return name;
    }

    public String getFilterClass() {
        return filterClass;
    }

    public List<QuarkusServletFilterMappingInfo> getMappings() {
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
