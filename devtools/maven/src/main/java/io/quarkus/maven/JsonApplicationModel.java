package io.quarkus.maven;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.MappableCollectionFactory;
import io.quarkus.builder.Json;
import io.quarkus.builder.JsonReader;
import io.quarkus.builder.json.JsonArray;
import io.quarkus.builder.json.JsonObject;
import io.quarkus.builder.json.JsonValue;

public class JsonApplicationModel {

    private static final MappableCollectionFactory JSON_CONTAINER_FACTORY = new MappableCollectionFactory() {
        @Override
        public Map<String, Object> newMap() {
            return Json.object();
        }

        @Override
        public Map<String, Object> newMap(int initialCapacity) {
            return Json.object(initialCapacity);
        }

        @Override
        public Collection<Object> newCollection() {
            return Json.array();
        }

        @Override
        public Collection<Object> newCollection(int initialCapacity) {
            return Json.array(initialCapacity);
        }
    };

    public static void serialize(ApplicationModel appModel, Path path) throws IOException {
        final Json.JsonObjectBuilder jsonObject = (Json.JsonObjectBuilder) appModel.asMap(JSON_CONTAINER_FACTORY);
        try (Writer writer = Files.newBufferedWriter(path)) {
            jsonObject.appendTo(writer);
        }
    }

    public static ApplicationModel deserialize(Path path) throws IOException {
        final Map<String, Object> modelMap = asMap(JsonReader.of(Files.readString(path)).read());
        dumpMap(modelMap, 0);
        return ApplicationModel.fromMap(modelMap);
    }

    private static Map<String, Object> asMap(JsonObject jsonObject) {
        var members = jsonObject.members();
        final Map<String, Object> map = new HashMap<>(members.size());
        for (var member : members) {
            map.put(member.attribute().value(), asObject(member.value()));
        }
        return map;
    }

    private static Collection<Object> asCollection(JsonArray jsonArray) {
        final Collection<Object> col = new ArrayList<>(jsonArray.size());
        jsonArray.stream().map(JsonApplicationModel::asObject).forEach(col::add);
        return col;
    }

    private static Object asObject(JsonValue jsonValue) {
        if (jsonValue instanceof JsonObject) {
            return asMap((JsonObject) jsonValue);
        }
        if (jsonValue instanceof JsonArray) {
            return asCollection((JsonArray) jsonValue);
        }
        return jsonValue.toString();
    }

    private static void dumpMap(Map<String, Object> map, int offset) {
        final String offsetString = " ".repeat(Math.max(0, offset));
        for (var entry : map.entrySet()) {
            if (entry.getValue() instanceof Map) {
                System.out.println(offsetString + entry.getKey());
                dumpMap((Map<String, Object>) entry.getValue(), offset + 2);
            } else if (entry.getValue() instanceof Collection) {
                System.out.println(offsetString + entry.getKey());
                dumpCollection((Collection<Object>) entry.getValue(), offset + 2);
            } else {
                System.out.println(offsetString + entry.getKey() + ": " + entry.getValue());
            }
        }
    }

    private static void dumpCollection(Collection<Object> col, int offset) {
        final String offsetString = " ".repeat(Math.max(0, offset));
        for (var entry : col) {
            if (entry instanceof Map) {
                dumpMap((Map<String, Object>) entry, offset + 2);
            } else if (entry instanceof Collection) {
                dumpCollection((Collection<Object>) entry, offset + 2);
            } else {
                System.out.println(offsetString + entry);
            }
        }
    }
}
