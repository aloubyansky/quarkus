package io.quarkus.bootstrap.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public interface MappableCollectionFactory {

    static MappableCollectionFactory defaultInstance() {
        return new MappableCollectionFactory() {
            @Override
            public Map<String, Object> newMap() {
                return new HashMap<>();
            }

            @Override
            public Map<String, Object> newMap(int initialCapacity) {
                return new HashMap<>(initialCapacity);
            }

            @Override
            public Collection<Object> newCollection() {
                return new ArrayList<>();
            }

            @Override
            public Collection<Object> newCollection(int initialCapacity) {
                return new ArrayList<>(initialCapacity);
            }
        };
    }

    Map<String, Object> newMap();

    Map<String, Object> newMap(int initialCapacity);

    Collection<Object> newCollection();

    Collection<Object> newCollection(int initialCapacity);
}
