package io.quarkus.servlet.container.integration;

import javax.servlet.DispatcherType;

public class QuarkusServletFilterMappingInfo {

    private QuarkusServletFilterMappingType mappingType;
    private String mapping;
    private DispatcherType dispatcher;

    public QuarkusServletFilterMappingInfo(final QuarkusServletFilterMappingType mappingType, final String mapping,
            final DispatcherType dispatcher) {
        this.mappingType = mappingType;
        this.mapping = mapping;
        this.dispatcher = dispatcher;
    }

    public void setMappingType(QuarkusServletFilterMappingType mappingType) {
        this.mappingType = mappingType;
    }

    public void setMapping(String mapping) {
        this.mapping = mapping;
    }

    public void setDispatcher(DispatcherType dispatcher) {
        this.dispatcher = dispatcher;
    }

    public QuarkusServletFilterMappingType getMappingType() {
        return mappingType;
    }

    public String getMapping() {
        return mapping;
    }

    public DispatcherType getDispatcher() {
        return dispatcher;
    }
}
