package io.quarkus.servlet.container.integration;

import io.quarkus.builder.item.MultiBuildItem;

public final class QuarkusServletInitParamBuildItem extends MultiBuildItem {

    final String key;
    final String value;

    public QuarkusServletInitParamBuildItem(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
