package io.quarkus.devtools.commands.handlers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.devtools.commands.data.QuarkusCommandException;
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
        if (!extensionOrigins.isEmpty()) {
            // necessary to set the versions from the selected origins
            final ExtensionCatalog mergedCatalog = CatalogMergeUtility.merge(extensionOrigins);
            extensions = syncWithCatalog(mergedCatalog, extensions);
            primaryCatalog = getPrimaryCatalog(extensionOrigins);
        } else {
            extensions = syncWithCatalog(primaryCatalog, extensions);
        }

        return new RegistryProjectInfo(primaryCatalog, extensionOrigins, extensions);
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

    public RegistryProjectInfo(ExtensionCatalog primaryPlatformBom, List<ExtensionCatalog> extensionOrigins,
            List<Extension> extensions) {
        this.primaryPlatformBom = primaryPlatformBom;
        this.extensionOrigins = extensionOrigins;
        this.extensions = extensions;
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
}
