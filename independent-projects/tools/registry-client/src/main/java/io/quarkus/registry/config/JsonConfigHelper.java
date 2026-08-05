package io.quarkus.registry.config;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.exc.InvalidFormatException;

class JsonConfigHelper {

    static void ensureNextToken(JsonParser p, JsonToken expected, DeserializationContext ctxt) {
        if (p.nextToken() != expected) {
            throw InvalidFormatException.from(p, "Expected " + expected, ctxt, RegistryConfigImpl.Builder.class);
        }
    }
}
