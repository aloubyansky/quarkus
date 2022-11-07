package io.quarkus.devtools.project.configuration;

import java.util.Objects;

public class ResolvedValue {

    public static ResolvedValue of(String value) {
        return of(value, null);
    }

    public static ResolvedValue of(String value, ValueSource source) {
        return new ResolvedValue(value, source);
    }

    private final String value;
    private final ValueSource source;

    private ResolvedValue(String value, ValueSource source) {
        this.value = value;
        this.source = source;
    }

    public String getValue() {
        return value;
    }

    public boolean hasSource() {
        return source != null;
    }

    public ValueSource getSource() {
        return source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ResolvedValue other = (ResolvedValue) obj;
        return Objects.equals(source, other.source) && Objects.equals(value, other.value);
    }

    @Override
    public String toString() {
        return source == null ? value : value + " from " + source;
    }
}
