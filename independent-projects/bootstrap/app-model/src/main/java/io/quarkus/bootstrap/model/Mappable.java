package io.quarkus.bootstrap.model;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

public interface Mappable {

    static <T extends Mappable> Collection<Object> asMaps(Iterable<T> col, MappableCollectionFactory factory) {
        if (col == null) {
            return null;
        }
        var result = factory.newCollection();
        for (var c : col) {
            result.add(c.asMap(factory));
        }
        return result;
    }

    /**
     * Formats a collection of items as a comma-separated string.
     * If the argument is null, the method will return null.
     * If the argument is an empty collection, the method will return an empty string.
     *
     * @param col collection
     * @return command-separated collection of items
     */
    static Collection<Object> asStringList(Iterable<?> col, MappableCollectionFactory factory) {
        return convertToStringList(col, Object::toString, factory);
    }

    /**
     * Formats a collection of items as a comma-separated string.
     * If the argument is null, the method will return null.
     * If the argument is an empty collection, the method will return an empty string.
     *
     * @param col collection
     * @param converter converts an object to string
     * @return command-separated collection of items
     */
    static <T> Collection<Object> convertToStringList(Iterable<T> col, Function<T, String> converter,
            MappableCollectionFactory factory) {
        if (col == null) {
            return null;
        }
        final Collection<Object> result = factory.newCollection();
        for (var c : col) {
            result.add(converter.apply(c));
        }
        return result;
    }

    Map<String, Object> asMap(MappableCollectionFactory factory);
}
