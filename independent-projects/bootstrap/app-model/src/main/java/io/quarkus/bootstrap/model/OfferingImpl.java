package io.quarkus.bootstrap.model;

import java.io.Serializable;
import java.util.Objects;

public class OfferingImpl implements ExtensionOffering, Serializable {

    private final String name;
    private final String version;

    public OfferingImpl(String name, String version) {
        this.name = Objects.requireNonNull(name);
        this.version = Objects.requireNonNull(version);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        OfferingImpl offering = (OfferingImpl) o;
        return Objects.equals(name, offering.name) && Objects.equals(version, offering.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, version);
    }

    @Override
    public String toString() {
        return name + "@" + version;
    }
}
