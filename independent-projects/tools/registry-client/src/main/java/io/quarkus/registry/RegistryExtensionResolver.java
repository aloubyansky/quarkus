package io.quarkus.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.catalog.Extension;
import io.quarkus.registry.catalog.ExtensionCatalog;
import io.quarkus.registry.catalog.Platform;
import io.quarkus.registry.catalog.PlatformCatalog;
import io.quarkus.registry.client.RegistryClient;
import io.quarkus.registry.config.RegistryConfig;
import io.quarkus.util.GlobUtil;

class RegistryExtensionResolver {

    public static final int VERSION_NOT_RECOGNIZED = -1;
    public static final int VERSION_NOT_CONFIGURED = 0;
    public static final int VERSION_RECOGNIZED = 1;
    public static final int VERSION_EXCLUSIVE_PROVIDER = 2;

    private final RegistryConfig config;
    private final RegistryClient extensionResolver;
    private final int index;
    private final MessageWriter log;

    private final Pattern recognizedQuarkusVersions;
    private final Collection<String> recognizedGroupIds;

    private final Set<String> offerings;

    RegistryExtensionResolver(RegistryClient extensionResolver,
            MessageWriter log, int index) throws RegistryResolutionException {
        this.extensionResolver = Objects.requireNonNull(extensionResolver, "Registry extension resolver is null");
        this.config = extensionResolver.resolveRegistryConfig();
        this.index = index;
        this.log = log;

        final String versionExpr = config.getQuarkusVersions() == null ? null
                : config.getQuarkusVersions().getRecognizedVersionsExpression();
        recognizedQuarkusVersions = versionExpr == null ? null : Pattern.compile(GlobUtil.toRegexPattern(versionExpr));
        this.recognizedGroupIds = config.getQuarkusVersions() == null ? Collections.emptyList()
                : config.getQuarkusVersions().getRecognizedGroupIds();

        var offerings = config.getExtra().get(Constants.OFFERINGS);
        if (offerings == null) {
            this.offerings = Set.of();
        } else if (offerings instanceof Collection) {
            this.offerings = Set.copyOf((Collection<String>) offerings);
        } else {
            log.warn("Offerings for " + config.getId() + " are not configured as a list but " + offerings);
            this.offerings = Set.of();
        }
    }

    String getId() {
        return config.getId();
    }

    int getIndex() {
        return index;
    }

    int checkQuarkusVersion(String quarkusVersion) {
        if (recognizedQuarkusVersions == null) {
            return VERSION_NOT_CONFIGURED;
        }
        if (quarkusVersion == null) {
            throw new IllegalArgumentException();
        }
        if (!recognizedQuarkusVersions.matcher(quarkusVersion).matches()) {
            return VERSION_NOT_RECOGNIZED;
        }
        return config.getQuarkusVersions().isExclusiveProvider() ? VERSION_EXCLUSIVE_PROVIDER
                : VERSION_RECOGNIZED;
    }

    boolean isExclusiveProviderOf(String quarkusVersion) {
        return checkQuarkusVersion(quarkusVersion) == VERSION_EXCLUSIVE_PROVIDER;
    }

    boolean isAcceptsQuarkusVersionQueries(String quarkusVersion) {
        return checkQuarkusVersion(quarkusVersion) >= 0;
    }

    int checkPlatform(ArtifactCoords platform) {
        if (!recognizedGroupIds.isEmpty() && !recognizedGroupIds.contains(platform.getGroupId())) {
            return VERSION_NOT_RECOGNIZED;
        }
        return checkQuarkusVersion(platform.getVersion());
    }

    PlatformCatalog.Mutable resolvePlatformCatalog() throws RegistryResolutionException {
        return resolvePlatformCatalog(null);
    }

    PlatformCatalog.Mutable resolvePlatformCatalog(String quarkusCoreVersion) throws RegistryResolutionException {
        return extensionResolver.resolvePlatforms(quarkusCoreVersion);
    }

    Platform resolveRecommendedPlatform() throws RegistryResolutionException {
        return resolvePlatformCatalog().getRecommendedPlatform();
    }

    ExtensionCatalog.Mutable resolveNonPlatformExtensions(String quarkusCoreVersion) throws RegistryResolutionException {
        return extensionResolver.resolveNonPlatformExtensions(quarkusCoreVersion);
    }

    ExtensionCatalog.Mutable resolvePlatformExtensions(ArtifactCoords platform) throws RegistryResolutionException {
        var catalog = extensionResolver.resolvePlatformExtensions(platform);
        if (offerings.isEmpty()) {
            return catalog;
        }
        var filteredExtensions = new ArrayList<Extension>(catalog.getExtensions().size());
        for (var e : catalog.getExtensions()) {
            var o = e.getMetadata().get(Constants.OFFERINGS);
            if (o == null) {
                continue;
            }
            if (!(o instanceof Collection)) {
                log.warn("Offerings from " + catalog.getBom().toCompactCoords() + " are not a collection but " + o);
                continue;
            }
            for (Object extOffering : (Collection<?>) o) {
                if (extOffering instanceof Map) {
                    if (offerings.contains(((Map) extOffering).get("name"))) {
                        filteredExtensions.add(e);
                        break;
                    }
                } else {
                    log.warn("Expected offering to be a map but got " + extOffering);
                }
            }
        }
        if (filteredExtensions.size() == catalog.getExtensions().size()) {
            return catalog;
        }
        if (filteredExtensions.isEmpty()) {
            return null;
        }
        catalog.setExtensions(filteredExtensions);
        return catalog;
    }

    void clearCache() throws RegistryResolutionException {
        extensionResolver.clearCache();
    }
}
