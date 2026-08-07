package io.quarkus.registry.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.bootstrap.json.JsonObject;
import io.quarkus.bootstrap.json.JsonString;
import io.quarkus.bootstrap.json.JsonValue;
import io.quarkus.maven.dependency.ArtifactCoords;

/**
 * Deserializes extension catalog model objects from the bootstrap JSON API types.
 */
public class ExtensionCatalogJsonReader {

    /**
     * Deserializes an {@link ExtensionCatalog} from a parsed {@link JsonObject}.
     *
     * @param json the parsed JSON object
     * @return a built extension catalog
     */
    public static ExtensionCatalog deserializeExtensionCatalog(JsonObject json) {
        return deserializeMutableExtensionCatalog(json).build();
    }

    /**
     * Deserializes a mutable {@link ExtensionCatalog.Mutable} from a parsed {@link JsonObject}.
     *
     * @param json the parsed JSON object
     * @return a mutable extension catalog builder
     */
    public static ExtensionCatalog.Mutable deserializeMutableExtensionCatalog(JsonObject json) {
        ExtensionCatalog.Mutable catalog = ExtensionCatalog.builder();

        catalog.setId(json.unwrapString("id"));
        catalog.setPlatform(json.unwrapBoolean("platform"));
        setBom(catalog, json);
        catalog.setQuarkusCoreVersion(json.unwrapString("quarkus-core-version"));
        catalog.setUpstreamQuarkusCoreVersion(json.unwrapString("upstream-quarkus-core-version"));

        setMetadata(catalog, json);

        catalog.setDerivedFrom(json.mapArray("derived-from", ExtensionCatalogJsonReader::deserializeOrigin));
        catalog.setCategories(json.mapArray("categories", ExtensionCatalogJsonReader::deserializeCategory));

        Map<String, ExtensionOrigin> originIndex = buildOriginIndex(catalog);
        catalog.setExtensions(json.mapArray("extensions", ext -> deserializeMutableExtension(ext, originIndex)));

        return catalog;
    }

    /**
     * Deserializes a {@link Category} from a parsed {@link JsonObject}.
     *
     * @param json the parsed JSON object
     * @return a built category
     */
    public static Category deserializeCategory(JsonObject json) {
        Category.Mutable category = Category.builder();
        category.setId(json.unwrapString("id"));
        category.setName(json.unwrapString("name"));
        category.setDescription(json.unwrapString("description"));
        setMetadata(category, json);
        return category.build();
    }

    /**
     * Deserializes an {@link ExtensionOrigin} from a parsed {@link JsonObject}.
     *
     * @param json the parsed JSON object
     * @return a built extension origin
     */
    public static ExtensionOrigin deserializeOrigin(JsonObject json) {
        ExtensionOrigin.Mutable origin = ExtensionOrigin.builder();
        origin.setId(json.unwrapString("id"));
        origin.setPlatform(json.unwrapBoolean("platform"));
        setBom(origin, json);
        setMetadata(origin, json);
        return origin.build();
    }

    private static Extension.Mutable deserializeMutableExtension(JsonObject json, Map<String, ExtensionOrigin> originIndex) {
        Extension.Mutable ext = Extension.builder();
        ext.setName(json.unwrapString("name"));
        ext.setDescription(json.unwrapString("description"));
        setMetadata(ext, json);

        String artifact = json.unwrapString("artifact");
        if (artifact != null) {
            ext.setArtifact(ArtifactCoords.fromString(artifact));
        }

        // legacy format support
        String groupId = json.unwrapString("group-id");
        if (groupId == null) {
            groupId = json.unwrapString("groupId");
        }
        if (groupId != null) {
            ext.setGroupId(groupId);
            String artifactId = json.unwrapString("artifact-id");
            if (artifactId == null) {
                artifactId = json.unwrapString("artifactId");
            }
            ext.setArtifactId(artifactId);
            ext.setVersion(json.unwrapString("version"));
        }

        ext.setOrigins(resolveOrigins(json, originIndex));
        return ext;
    }

    private static Extension deserializeExtension(JsonObject json, Map<String, ExtensionOrigin> originIndex) {
        return deserializeMutableExtension(json, originIndex).build();
    }

    private static List<ExtensionOrigin> resolveOrigins(JsonObject json, Map<String, ExtensionOrigin> originIndex) {
        var originsArray = json.unwrapArray("origins");
        if (originsArray == null) {
            return List.of();
        }
        return originsArray.map(v -> resolveOrigin(v, originIndex));
    }

    private static ExtensionOrigin resolveOrigin(JsonValue v, Map<String, ExtensionOrigin> originIndex) {
        if (v instanceof JsonString s) {
            ExtensionOrigin origin = originIndex.get(s.value());
            if (origin != null) {
                return origin;
            }
            return ExtensionOrigin.builder().setId(s.value()).build();
        }
        if (v instanceof JsonObject obj) {
            return deserializeOrigin(obj);
        }
        return ExtensionOrigin.builder().setId(v.toString()).build();
    }

    private static Map<String, ExtensionOrigin> buildOriginIndex(ExtensionCatalog.Mutable catalog) {
        Map<String, ExtensionOrigin> index = new HashMap<>();
        if (catalog.getId() != null) {
            index.put(catalog.getId(), catalogAsOrigin(catalog));
        }
        for (ExtensionOrigin origin : catalog.getDerivedFrom()) {
            if (origin.getId() != null) {
                index.put(origin.getId(), origin);
            }
        }
        return index;
    }

    private static ExtensionOrigin catalogAsOrigin(ExtensionCatalog catalog) {
        return ExtensionOrigin.builder()
                .setId(catalog.getId())
                .setPlatform(catalog.isPlatform())
                .setBom(catalog.getBom())
                .setMetadata(catalog.getMetadata())
                .build();
    }

    /**
     * Deserializes a mutable {@link PlatformCatalog.Mutable} from a parsed {@link JsonObject}.
     *
     * @param json the parsed JSON object
     * @return a mutable platform catalog builder
     */
    public static PlatformCatalog.Mutable deserializeMutablePlatformCatalog(JsonObject json) {
        PlatformCatalog.Mutable catalog = PlatformCatalog.builder();
        for (Platform.Mutable p : json.mapArray("platforms", ExtensionCatalogJsonReader::deserializeMutablePlatform)) {
            catalog.addPlatform(p);
        }
        JsonObject metadata = json.unwrapObject("metadata");
        if (metadata != null) {
            catalog.setMetadata(metadata.toMap());
        }
        return catalog;
    }

    private static Platform.Mutable deserializeMutablePlatform(JsonObject json) {
        Platform.Mutable platform = Platform.builder();
        platform.setPlatformKey(json.unwrapString("platform-key"));
        platform.setName(json.unwrapString("name"));
        for (PlatformStream.Mutable stream : json.mapArray("streams",
                ExtensionCatalogJsonReader::deserializeMutablePlatformStream)) {
            platform.addStream(stream);
        }
        JsonObject metadata = json.unwrapObject("metadata");
        if (metadata != null) {
            platform.setMetadata(metadata.toMap());
        }
        return platform;
    }

    private static PlatformStream.Mutable deserializeMutablePlatformStream(JsonObject json) {
        PlatformStream.Mutable stream = PlatformStream.builder();
        stream.setId(json.unwrapString("id"));
        stream.setName(json.unwrapString("name"));
        for (PlatformRelease release : json.mapArray("releases",
                ExtensionCatalogJsonReader::deserializePlatformRelease)) {
            stream.addRelease(release);
        }
        JsonObject metadata = json.unwrapObject("metadata");
        if (metadata != null) {
            stream.setMetadata(metadata.toMap());
        }
        return stream;
    }

    private static PlatformRelease deserializePlatformRelease(JsonObject json) {
        PlatformRelease.Mutable release = PlatformRelease.builder();
        String version = json.unwrapString("version");
        if (version != null) {
            release.setVersion(PlatformReleaseVersion.fromString(version));
        }
        release.setQuarkusCoreVersion(json.unwrapString("quarkus-core-version"));
        release.setUpstreamQuarkusCoreVersion(json.unwrapString("upstream-quarkus-core-version"));
        release.setMemberBoms(json.unwrapStringList("member-boms").stream()
                .map(ArtifactCoords::fromString)
                .collect(java.util.stream.Collectors.toList()));
        JsonObject metadata = json.unwrapObject("metadata");
        if (metadata != null) {
            release.setMetadata(metadata.toMap());
        }
        return release.build();
    }

    /**
     * Deserializes an {@link Extension} from a parsed {@link JsonObject}.
     *
     * @param json the parsed JSON object
     * @return a built extension
     */
    public static Extension deserializeExtension(JsonObject json) {
        return deserializeExtension(json, Map.of());
    }

    private static void setBom(ExtensionOrigin.Mutable target, JsonObject json) {
        String bom = json.unwrapString("bom");
        if (bom != null) {
            target.setBom(ArtifactCoords.fromString(bom));
        }
    }

    private static void setMetadata(ExtensionOrigin.Mutable target, JsonObject json) {
        JsonObject metadata = json.unwrapObject("metadata");
        if (metadata != null) {
            target.setMetadata(metadata.toMap());
        }
    }

    private static void setMetadata(Category.Mutable target, JsonObject json) {
        JsonObject metadata = json.unwrapObject("metadata");
        if (metadata != null) {
            target.setMetadata(metadata.toMap());
        }
    }

    private static void setMetadata(Extension.Mutable target, JsonObject json) {
        JsonObject metadata = json.unwrapObject("metadata");
        if (metadata != null) {
            target.setMetadata(metadata.toMap());
        }
    }
}
