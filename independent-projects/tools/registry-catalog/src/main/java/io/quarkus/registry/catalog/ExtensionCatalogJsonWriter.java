package io.quarkus.registry.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.quarkus.bootstrap.json.Json;
import io.quarkus.bootstrap.json.Json.JsonArrayBuilder;
import io.quarkus.bootstrap.json.Json.JsonObjectBuilder;

/**
 * Serializes extension catalog model objects to JSON using the bootstrap JSON API.
 */
public class ExtensionCatalogJsonWriter {

    /**
     * Serializes an {@link ExtensionCatalog} to a {@link JsonObjectBuilder}.
     *
     * @param catalog the catalog to serialize
     * @return a JSON object builder
     */
    public static JsonObjectBuilder serializeExtensionCatalog(ExtensionCatalog catalog) {
        JsonObjectBuilder json = Json.object();
        putIfNonNull(json, "id", catalog.getId());
        if (catalog.isPlatform()) {
            json.put("platform", true);
        }
        putBom(json, catalog.getBom());
        putIfNonNull(json, "quarkus-core-version", catalog.getQuarkusCoreVersion());
        putIfNonNull(json, "upstream-quarkus-core-version", catalog.getUpstreamQuarkusCoreVersion());

        putOrigins(json, "derived-from", catalog.getDerivedFrom());
        putExtensions(json, catalog.getExtensions());
        putCategories(json, catalog.getCategories());
        putMetadata(json, catalog.getMetadata());

        return json;
    }

    /**
     * Serializes a {@link PlatformCatalog} to a {@link JsonObjectBuilder}.
     *
     * @param catalog the platform catalog to serialize
     * @return a JSON object builder
     */
    public static JsonObjectBuilder serializePlatformCatalog(PlatformCatalog catalog) {
        JsonObjectBuilder json = Json.object();
        putPlatforms(json, catalog.getPlatforms());
        putMetadata(json, catalog.getMetadata());
        return json;
    }

    private static void putExtensions(JsonObjectBuilder json, Collection<Extension> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return;
        }
        JsonArrayBuilder arr = Json.array(extensions.size());
        for (Extension ext : extensions) {
            arr.add(serializeExtension(ext));
        }
        json.put("extensions", arr);
    }

    /**
     * Serializes an {@link Extension} to a {@link JsonObjectBuilder}.
     *
     * @param ext the extension to serialize
     * @return a JSON object builder
     */
    public static JsonObjectBuilder serializeExtension(Extension ext) {
        JsonObjectBuilder json = Json.object();
        putIfNonNull(json, "name", ext.getName());
        putIfNonNull(json, "description", ext.getDescription());
        putMetadata(json, ext.getMetadata());
        if (ext.getArtifact() != null) {
            json.put("artifact", ext.getArtifact().toString());
        }
        List<ExtensionOrigin> origins = ext.getOrigins();
        if (origins != null && !origins.isEmpty()) {
            JsonArrayBuilder arr = Json.array(origins.size());
            for (ExtensionOrigin origin : origins) {
                arr.add(origin.getId());
            }
            json.put("origins", arr);
        }
        return json;
    }

    private static void putCategories(JsonObjectBuilder json, List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        JsonArrayBuilder arr = Json.array(categories.size());
        for (Category cat : categories) {
            arr.add(serializeCategory(cat));
        }
        json.put("categories", arr);
    }

    private static JsonObjectBuilder serializeCategory(Category cat) {
        JsonObjectBuilder json = Json.object();
        putIfNonNull(json, "id", cat.getId());
        putIfNonNull(json, "name", cat.getName());
        putIfNonNull(json, "description", cat.getDescription());
        putMetadata(json, cat.getMetadata());
        return json;
    }

    private static void putOrigins(JsonObjectBuilder json, String name, List<ExtensionOrigin> origins) {
        if (origins == null || origins.isEmpty()) {
            return;
        }
        JsonArrayBuilder arr = Json.array(origins.size());
        for (ExtensionOrigin origin : origins) {
            arr.add(serializeOrigin(origin));
        }
        json.put(name, arr);
    }

    private static JsonObjectBuilder serializeOrigin(ExtensionOrigin origin) {
        JsonObjectBuilder json = Json.object();
        putIfNonNull(json, "id", origin.getId());
        if (origin.isPlatform()) {
            json.put("platform", true);
        }
        putBom(json, origin.getBom());
        putMetadata(json, origin.getMetadata());
        return json;
    }

    private static void putPlatforms(JsonObjectBuilder json, Collection<Platform> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return;
        }
        JsonArrayBuilder arr = Json.array(platforms instanceof List ? platforms.size() : 4);
        for (Platform platform : platforms) {
            arr.add(serializePlatform(platform));
        }
        json.put("platforms", arr);
    }

    private static JsonObjectBuilder serializePlatform(Platform platform) {
        JsonObjectBuilder json = Json.object();
        putIfNonNull(json, "platform-key", platform.getPlatformKey());
        putIfNonNull(json, "name", platform.getName());
        Collection<PlatformStream> streams = platform.getStreams();
        if (streams != null && !streams.isEmpty()) {
            JsonArrayBuilder arr = Json.array(streams instanceof List ? streams.size() : 4);
            for (PlatformStream stream : streams) {
                arr.add(serializePlatformStream(stream));
            }
            json.put("streams", arr);
        }
        putMetadata(json, platform.getMetadata());
        return json;
    }

    private static JsonObjectBuilder serializePlatformStream(PlatformStream stream) {
        JsonObjectBuilder json = Json.object();
        putIfNonNull(json, "id", stream.getId());
        putIfNonNull(json, "name", stream.getName());
        Collection<PlatformRelease> releases = stream.getReleases();
        if (releases != null && !releases.isEmpty()) {
            JsonArrayBuilder arr = Json.array(releases instanceof List ? releases.size() : 4);
            for (PlatformRelease release : releases) {
                arr.add(serializePlatformRelease(release));
            }
            json.put("releases", arr);
        }
        putMetadata(json, stream.getMetadata());
        return json;
    }

    private static JsonObjectBuilder serializePlatformRelease(PlatformRelease release) {
        JsonObjectBuilder json = Json.object();
        if (release.getVersion() != null) {
            json.put("version", release.getVersion().toString());
        }
        Collection<?> memberBoms = release.getMemberBoms();
        if (memberBoms != null && !memberBoms.isEmpty()) {
            JsonArrayBuilder arr = Json.array(memberBoms.size());
            for (Object bom : memberBoms) {
                arr.add(bom.toString());
            }
            json.put("member-boms", arr);
        }
        putIfNonNull(json, "quarkus-core-version", release.getQuarkusCoreVersion());
        putIfNonNull(json, "upstream-quarkus-core-version", release.getUpstreamQuarkusCoreVersion());
        putMetadata(json, release.getMetadata());
        return json;
    }

    private static void putBom(JsonObjectBuilder json, io.quarkus.maven.dependency.ArtifactCoords bom) {
        if (bom != null) {
            json.put("bom", bom.toString());
        }
    }

    private static void putIfNonNull(JsonObjectBuilder json, String name, String value) {
        if (value != null) {
            json.put(name, value);
        }
    }

    private static void putMetadata(JsonObjectBuilder json, Map<String, Object> metadata) {
        if (metadata != null && !metadata.isEmpty()) {
            json.put("metadata", metadata);
        }
    }
}
