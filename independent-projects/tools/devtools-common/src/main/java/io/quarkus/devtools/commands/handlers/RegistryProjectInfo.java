package io.quarkus.devtools.commands.handlers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.quarkus.devtools.commands.data.QuarkusCommandException;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.registry.CatalogMergeUtility;
import io.quarkus.registry.catalog.Extension;
import io.quarkus.registry.catalog.ExtensionCatalog;

public class RegistryProjectInfo {

    public static RegistryProjectInfo fromCatalogWithKeys(ExtensionCatalog registryCatalog,
            Collection<ArtifactKey> extensionKeys)
            throws QuarkusCommandException {
        return fromCatalogWithExtensions(registryCatalog, collectExtensions(registryCatalog, extensionKeys));
    }

    public static RegistryProjectInfo fromCatalogWithExtensions(ExtensionCatalog registryCatalog, List<Extension> extensions)
            throws QuarkusCommandException {
        final List<ExtensionCatalog> extensionOrigins = CreateProjectCommandHandler.getExtensionOrigins(registryCatalog,
                extensions);

        ExtensionCatalog primaryCatalog = registryCatalog;
        Map<?, ?> projectProperties;
        if (!extensionOrigins.isEmpty()) {
            // necessary to set the versions from the selected origins
            final ExtensionCatalog mergedCatalog = CatalogMergeUtility.merge(extensionOrigins);
            extensions = syncWithCatalog(mergedCatalog, extensions);
            primaryCatalog = getPrimaryCatalog(extensionOrigins);
            projectProperties = getProjectProperties(mergedCatalog);
        } else {
            extensions = syncWithCatalog(primaryCatalog, extensions);
            projectProperties = getProjectProperties(primaryCatalog);
        }

        return new RegistryProjectInfo(primaryCatalog, extensionOrigins, extensions, projectProperties);
    }

    private static Map<?, ?> getProjectProperties(ExtensionCatalog catalog) {
        var o = catalog.getMetadata().get("project");
        if (o instanceof Map projectMetadata) {
            o = projectMetadata.get("properties");
            if (o instanceof Map projectProperties) {
                return projectProperties;
            } else {
                throw new RuntimeException("Project properties are not a Map but " + o);
            }
        } else {
            throw new RuntimeException("Project metadata is not a Map but " + o);
        }
    }

    private static String getRequiredProperty(Map<?, ?> map, String propName) {
        var o = map.get(propName);
        if (o == null) {
            throw new RuntimeException("Required project property " + propName + " is missing from the platform metadata");
        }
        return String.valueOf(o);
    }

    private static ExtensionCatalog getPrimaryCatalog(List<ExtensionCatalog> extensionOrigins) {
        ExtensionCatalog primaryCatalog;
        primaryCatalog = null;
        for (ExtensionCatalog c : extensionOrigins) {
            if (c.isPlatform()) {
                if (c.getBom().getArtifactId().equals("quarkus-bom")) {
                    primaryCatalog = c;
                    break;
                } else if (primaryCatalog == null) {
                    primaryCatalog = c;
                }
            }
        }
        return primaryCatalog;
    }

    private static List<Extension> collectExtensions(ExtensionCatalog catalog, Collection<ArtifactKey> extensionKeys) {
        var result = new ArrayList<Extension>(extensionKeys.size());
        var catalogExtensions = toMap(catalog.getExtensions());
        for (var key : extensionKeys) {
            var catalogExt = catalogExtensions.get(key);
            if (catalogExt == null) {
                throw new IllegalArgumentException("Failed to locate " + key + " in the catalog");
            }
            result.add(catalogExt);
        }
        return result;
    }

    private static List<Extension> syncWithCatalog(ExtensionCatalog catalog, List<Extension> extensions) {
        var result = new ArrayList<Extension>(extensions.size());
        var catalogExtensions = toMap(catalog.getExtensions());
        for (var e : extensions) {
            var catalogExt = catalogExtensions.get(e.getArtifact().getKey());
            result.add(catalogExt == null ? e : catalogExt);
        }
        return result;
    }

    private static Map<ArtifactKey, Extension> toMap(Collection<Extension> extensions) {
        var result = new HashMap<ArtifactKey, Extension>(extensions.size());
        for (var e : extensions) {
            result.put(e.getArtifact().getKey(), e);
        }
        return result;
    }

    private final ExtensionCatalog primaryPlatformBom;
    private final List<ExtensionCatalog> extensionOrigins;
    private final List<Extension> extensions;
    private final Map<?, ?> projectProperties;

    public RegistryProjectInfo(ExtensionCatalog primaryPlatformBom, List<ExtensionCatalog> extensionOrigins,
            List<Extension> extensions, Map<?, ?> projectProperties) {
        this.primaryPlatformBom = Objects.requireNonNull(primaryPlatformBom, "Primary platform BOM is null");
        this.extensionOrigins = Objects.requireNonNull(extensionOrigins, "Extension origins are null");
        this.extensions = Objects.requireNonNull(extensions, "Extensions are null");
        this.projectProperties = Objects.requireNonNull(projectProperties, "Project properties are null");
    }

    public ExtensionCatalog getPrimaryPlatformBom() {
        return primaryPlatformBom;
    }

    public List<ExtensionCatalog> getExtensionOrigins() {
        return extensionOrigins;
    }

    public List<Extension> getExtensions() {
        return extensions;
    }

    public ArtifactCoords getQuarkusMavenPlugin() {
        return ArtifactCoords.jar(getRequiredProperty(projectProperties, "maven-plugin-groupId"),
                getRequiredProperty(projectProperties, "maven-plugin-artifactId"),
                getRequiredProperty(projectProperties, "maven-plugin-version"));
    }
}
