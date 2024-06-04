package io.quarkus.bootstrap.app;

import java.util.Collection;

public class ApplicationManifest {

    private ApplicationComponent mainComponent;
    private Collection<ApplicationComponent> components;

    public ApplicationComponent getMainComponent() {
        return mainComponent;
    }

    public Collection<ApplicationComponent> getComponents() {
        return components;
    }
}
