package io.quarkus.bootstrap.model;

/**
 * Represents a logical group of extensions typically developed and maintained as a set by a development team.
 */
public interface ExtensionOffering {

    /**
     * Parses a string formatted as {@code <name>@<version>} and returns a new instance of {@link ExtensionOffering}.
     *
     * @param s string following format <name>@<version>
     * @return new instance of ExtensionOffering
     * @throws IllegalArgumentException in case the argument is not properly formatted
     */
    static ExtensionOffering parse(String s) {
        var i = s.indexOf('@');
        if (i <= 0 || i == s.length() - 1) {
            throw new IllegalArgumentException(
                    "Expected the argument to be in the format <name>@<version> but got '" + s + "'");
        }
        return new OfferingImpl(s.substring(0, i), s.substring(i + 1));
    }

    /**
     * Name of an offering, typically a simple word, can't be null.
     *
     * @return name of an offering, never null
     */
    String getName();

    /**
     * Version of an offering, can't be null.
     *
     * @return version of an offering, never null
     */
    String getVersion();
}
