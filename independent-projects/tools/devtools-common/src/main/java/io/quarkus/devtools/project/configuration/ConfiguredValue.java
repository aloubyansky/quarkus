package io.quarkus.devtools.project.configuration;

import java.util.Objects;

public class ConfiguredValue {

    public static ConfiguredValue of(String effectiveValue) {
        return new ConfiguredValue(effectiveValue, ResolvedValue.of(effectiveValue));
    }

    public static ConfiguredValue of(String rawValue, String effectiveValue) {
        return new ConfiguredValue(rawValue, ResolvedValue.of(effectiveValue));
    }

    public static ConfiguredValue of(String rawValue, ResolvedValue resolvedValue) {
        return new ConfiguredValue(rawValue, resolvedValue);
    }

    public static boolean isPropertyExpression(final String value) {
        return value != null && !value.isBlank() && value.startsWith("${") && value.endsWith("}");
    }

    public static String getPropertyName(String propertyExpr) {
        return propertyExpr.substring(2, propertyExpr.length() - 1);
    }

    private final ResolvedValue resolvedValue;
    private final String rawValue;

    private ConfiguredValue(String rawValue, ResolvedValue resolvedValue) {
        this.resolvedValue = resolvedValue;
        this.rawValue = rawValue;
    }

    public ResolvedValue getResolvedValue() {
        return resolvedValue;
    }

    public String getEffectiveValue() {
        return resolvedValue == null ? null : resolvedValue.getValue();
    }

    public boolean isEffectivelyNull() {
        return resolvedValue == null || resolvedValue.getValue() == null;
    }

    public boolean isProperty() {
        return isPropertyExpression(rawValue);
    }

    public String getPropertyName() {
        if (isProperty()) {
            return getPropertyName(rawValue);
        }
        return null;
    }

    public String getRawValue() {
        return rawValue;
    }

    public boolean isRawEffective() {
        return Objects.equals(rawValue, getEffectiveValue());
    }

    @Override
    public int hashCode() {
        return Objects.hash(resolvedValue, rawValue);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ConfiguredValue other = (ConfiguredValue) obj;
        return Objects.equals(resolvedValue, other.resolvedValue) && Objects.equals(rawValue, other.rawValue);
    }

    @Override
    public String toString() {
        if (resolvedValue == null) {
            return "null";
        }
        if (isRawEffective()) {
            return rawValue;
        }
        final StringBuilder sb = new StringBuilder();
        if (rawValue != null) {
            sb.append(rawValue);
        }
        return sb.append("(").append(resolvedValue).append(")").toString();
    }
}
