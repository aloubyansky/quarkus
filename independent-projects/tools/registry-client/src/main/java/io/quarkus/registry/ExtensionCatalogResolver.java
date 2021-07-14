package io.quarkus.registry;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.maven.ArtifactCoords;
import io.quarkus.maven.StreamCoords;
import io.quarkus.registry.catalog.ExtensionCatalog;
import io.quarkus.registry.catalog.Platform;
import io.quarkus.registry.catalog.PlatformCatalog;
import io.quarkus.registry.catalog.PlatformRelease;
import io.quarkus.registry.catalog.PlatformStream;
import io.quarkus.registry.catalog.json.JsonCatalogMerger;
import io.quarkus.registry.catalog.json.JsonExtensionCatalog;
import io.quarkus.registry.catalog.json.JsonPlatformCatalog;
import io.quarkus.registry.client.RegistryClientFactory;
import io.quarkus.registry.client.maven.MavenRegistryClientFactory;
import io.quarkus.registry.client.spi.RegistryClientEnvironment;
import io.quarkus.registry.client.spi.RegistryClientFactoryProvider;
import io.quarkus.registry.config.RegistriesConfig;
import io.quarkus.registry.config.RegistriesConfigLocator;
import io.quarkus.registry.config.RegistryConfig;
import io.quarkus.registry.union.ElementCatalog;
import io.quarkus.registry.union.ElementCatalogBuilder;
import io.quarkus.registry.union.ElementCatalogBuilder.UnionBuilder;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.eclipse.aether.artifact.DefaultArtifact;

public class ExtensionCatalogResolver {

    public static ExtensionCatalogResolver empty() {
        final ExtensionCatalogResolver resolver = new ExtensionCatalogResolver();
        resolver.registries = Collections.emptyList();
        return resolver;
    }

    public static Builder builder() {
        return new ExtensionCatalogResolver().new Builder();
    }

    public class Builder {

        private MavenArtifactResolver artifactResolver;
        private RegistriesConfig config;
        private boolean built;

        private RegistryClientFactory defaultClientFactory;
        private RegistryClientEnvironment clientEnv;

        private Builder() {
        }

        public Builder artifactResolver(MavenArtifactResolver resolver) {
            assertNotBuilt();
            artifactResolver = resolver;
            return this;
        }

        public Builder messageWriter(MessageWriter messageWriter) {
            assertNotBuilt();
            log = messageWriter;
            return this;
        }

        public Builder config(RegistriesConfig registriesConfig) {
            assertNotBuilt();
            config = registriesConfig;
            return this;
        }

        public ExtensionCatalogResolver build() {
            assertNotBuilt();
            built = true;
            completeConfig();
            buildRegistryClients();
            return ExtensionCatalogResolver.this;
        }

        private void completeConfig() {
            if (config == null) {
                config = RegistriesConfigLocator.resolveConfig();
            }
            if (log == null) {
                log = config.isDebug() ? MessageWriter.debug() : MessageWriter.info();
            }
            if (artifactResolver == null) {
                try {
                    artifactResolver = MavenArtifactResolver.builder()
                            .setWorkspaceDiscovery(false)
                            .setArtifactTransferLogging(config.isDebug())
                            .build();
                } catch (BootstrapMavenException e) {
                    throw new IllegalStateException("Failed to intialize the default Maven artifact resolver", e);
                }
            }
        }

        private void buildRegistryClients() {
            registries = new ArrayList<>(config.getRegistries().size());
            for (RegistryConfig config : config.getRegistries()) {
                if (config.isDisabled()) {
                    continue;
                }
                final RegistryClientFactory clientFactory = getClientFactory(config);
                try {
                    registries.add(new RegistryExtensionResolver(clientFactory.buildRegistryClient(config), log));
                } catch (RegistryResolutionException e) {
                    // TODO this should be enabled once the registry comes to life
                    log.debug(e.getMessage());
                    continue;
                }
            }
        }

        private RegistryClientFactory getClientFactory(RegistryConfig config) {
            if (config.getExtra().isEmpty()) {
                return getDefaultClientFactory();
            }
            Object provider = config.getExtra().get("client-factory-artifact");
            if (provider != null) {
                return loadFromArtifact(config, provider);
            }
            provider = config.getExtra().get("client-factory-url");
            if (provider != null) {
                final URL url;
                try {
                    url = new URL((String) provider);
                } catch (MalformedURLException e) {
                    throw new IllegalStateException("Failed to translate " + provider + " to URL", e);
                }
                return loadFromUrl(url);
            }
            return getDefaultClientFactory();
        }

        public RegistryClientFactory loadFromArtifact(RegistryConfig config, final Object providerValue) {
            ArtifactCoords providerArtifact = null;
            try {
                final String providerStr = (String) providerValue;
                providerArtifact = ArtifactCoords.fromString(providerStr);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to process configuration of " + config.getId()
                        + " registry: failed to cast " + providerValue + " to String", e);
            }
            final File providerJar;
            try {
                providerJar = artifactResolver.resolve(new DefaultArtifact(providerArtifact.getGroupId(),
                        providerArtifact.getArtifactId(), providerArtifact.getClassifier(),
                        providerArtifact.getType(), providerArtifact.getVersion())).getArtifact().getFile();
            } catch (BootstrapMavenException e) {
                throw new IllegalStateException(
                        "Failed to resolve the registry client factory provider artifact " + providerArtifact, e);
            }
            log.debug("Loading registry client factory for %s from %s", config.getId(), providerArtifact);
            final URL url;
            try {
                url = providerJar.toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Failed to translate " + providerJar + " to URL", e);
            }
            return loadFromUrl(url);
        }

        private RegistryClientFactory loadFromUrl(final URL url) {
            final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
            try {
                ClassLoader providerCl = new URLClassLoader(new URL[] { url }, originalCl);
                final Iterator<RegistryClientFactoryProvider> i = ServiceLoader
                        .load(RegistryClientFactoryProvider.class, providerCl).iterator();
                if (!i.hasNext()) {
                    throw new Exception("Failed to locate an implementation of " + RegistryClientFactoryProvider.class.getName()
                            + " service provider");
                }
                final RegistryClientFactoryProvider provider = i.next();
                if (i.hasNext()) {
                    final StringBuilder buf = new StringBuilder();
                    buf.append("Found more than one registry client factory provider "
                            + provider.getClass().getName());
                    while (i.hasNext()) {
                        buf.append(", ").append(i.next().getClass().getName());
                    }
                    throw new Exception(buf.toString());
                }
                return provider.newRegistryClientFactory(getClientEnv());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load registry client factory from " + url, e);
            } finally {
                Thread.currentThread().setContextClassLoader(originalCl);
            }
        }

        private RegistryClientFactory getDefaultClientFactory() {
            return defaultClientFactory == null ? defaultClientFactory = new MavenRegistryClientFactory(artifactResolver, log)
                    : defaultClientFactory;
        }

        private RegistryClientEnvironment getClientEnv() {
            return clientEnv == null ? clientEnv = new RegistryClientEnvironment() {

                @Override
                public MessageWriter log() {
                    return log;
                }

                @Override
                public MavenArtifactResolver resolver() {
                    return artifactResolver;
                }
            } : clientEnv;
        }

        private void assertNotBuilt() {
            if (built) {
                throw new IllegalStateException("The builder has already built an instance");
            }
        }
    }

    private MessageWriter log;
    private List<RegistryExtensionResolver> registries;

    public boolean hasRegistries() {
        return !registries.isEmpty();
    }

    public PlatformCatalog resolvePlatformCatalog() throws RegistryResolutionException {
        return resolvePlatformCatalog(null);
    }

    public PlatformCatalog resolvePlatformCatalog(String quarkusVersion) throws RegistryResolutionException {

        List<PlatformCatalog> catalogs = new ArrayList<>(registries.size());
        for (RegistryExtensionResolver qer : registries) {
            final PlatformCatalog catalog = qer.resolvePlatformCatalog(quarkusVersion);
            if (catalog != null) {
                catalogs.add(catalog);
            }
        }

        if (catalogs.isEmpty()) {
            return null;
        }
        if (catalogs.size() == 1) {
            return catalogs.get(0);
        }

        final JsonPlatformCatalog result = new JsonPlatformCatalog();

        final List<Platform> collectedPlatforms = new ArrayList<>();
        result.setPlatforms(collectedPlatforms);

        final Set<String> collectedPlatformKeys = new HashSet<>();
        for (PlatformCatalog c : catalogs) {
            collectPlatforms(c, collectedPlatforms, collectedPlatformKeys);
        }

        return result;
    }

    private void collectPlatforms(PlatformCatalog catalog, List<Platform> collectedPlatforms,
            Set<String> collectedPlatformKeys) {
        for (Platform p : catalog.getPlatforms()) {
            if (collectedPlatformKeys.add(p.getPlatformKey())) {
                collectedPlatforms.add(p);
            }
        }
    }

    private class ExtensionCatalogBuilder {
        final List<ExtensionCatalog> catalogs = new ArrayList<>();
        final Map<String, List<RegistryExtensionResolver>> registriesByQuarkusCore = new HashMap<>();
        private final Map<String, ElementCatalogBuilder<ExtensionCatalog>> catalogBuilders = new HashMap<>();
        private final Map<String, Integer> compatibilityCodes = new HashMap<>();

        ElementCatalogBuilder<ExtensionCatalog> getRegistryCatalog(String registryId) {
            return catalogBuilders.computeIfAbsent(registryId, id -> ElementCatalogBuilder.newInstance());
        }

        List<RegistryExtensionResolver> getRegistriesForQuarkusCore(String quarkusVersion) {
            return registriesByQuarkusCore.computeIfAbsent(quarkusVersion, v -> getRegistriesForQuarkusVersion(v));
        }

        public int getCompatiblityCode(String quarkusVersion, String upstreamQuarkusVersion) {
            Integer i = compatibilityCodes.get(quarkusVersion);
            if (i == null) {
                if (upstreamQuarkusVersion != null) {
                    i = compatibilityCodes.get(upstreamQuarkusVersion);
                    if (i == null) {
                        i = compatibilityCodes.size();
                        compatibilityCodes.put(upstreamQuarkusVersion, i);
                    }
                }
                compatibilityCodes.put(quarkusVersion, i);
            }
            return i;
        }

        ExtensionCatalog build() {
            final ExtensionCatalog catalog = JsonCatalogMerger.merge(catalogs);
            final List<ElementCatalog<ExtensionCatalog>> catalogs = new ArrayList<>(catalogBuilders.size());
            for (RegistryExtensionResolver r : registries) {
                final ElementCatalogBuilder<ExtensionCatalog> cb = catalogBuilders.get(r.getId());
                if (cb == null) {
                    continue;
                }
                final ElementCatalog<ExtensionCatalog> ec = cb.build();
                if (!ec.isEmpty()) {
                    catalogs.add(ec);
                }
            }
            if (!catalogs.isEmpty()) {
                ElementCatalogBuilder.setElementCatalogs(catalog, catalogs);
            }
            return catalog;
        }
    }

    public ExtensionCatalog resolveExtensionCatalog() throws RegistryResolutionException {

        ensureRegistriesConfigured();

        final ExtensionCatalogBuilder catalogBuilder = new ExtensionCatalogBuilder();

        int platformIndex = 0;
        for (int registryIndex = 0; registryIndex < registries.size(); ++registryIndex) {
            RegistryExtensionResolver registry = registries.get(registryIndex);
            final PlatformCatalog pc = registry.resolvePlatformCatalog();
            if (pc == null) {
                continue;
            }
            final ElementCatalogBuilder<ExtensionCatalog> elemCatalog = catalogBuilder.getRegistryCatalog(registry.getId());
            for (Platform platform : pc.getPlatforms()) {
                platformIndex++;
                int streamIndex = 0;
                for (PlatformStream stream : platform.getStreams()) {
                    streamIndex++;
                    int releaseIndex = 0;
                    for (PlatformRelease release : stream.getReleases()) {
                        releaseIndex++;
                        final int compatiblityCode = catalogBuilder
                                .getCompatiblityCode(release.getQuarkusCoreVersion(),
                                        release.getUpstreamQuarkusCoreVersion());

                        final UnionBuilder<ExtensionCatalog> union = elemCatalog
                                .getOrCreateUnion(new PlatformStackIndex(platformIndex, streamIndex, releaseIndex));
                        int memberIndex = 0;
                        for (ArtifactCoords bom : release.getMemberBoms()) {
                            memberIndex++;
                            final ExtensionCatalog ec = registry.resolvePlatformExtensions(bom);
                            if (ec != null) {
                                Map<String, Object> metadata = ec.getMetadata();
                                if (metadata.isEmpty()) {
                                    metadata = new HashMap<>();
                                    ((JsonExtensionCatalog) ec).setMetadata(metadata);
                                }

                                final OriginPreference originPref = new OriginPreference(registryIndex, platformIndex,
                                        releaseIndex, memberIndex, compatiblityCode);

                                metadata.put("origin-preference", originPref);
                                catalogBuilder.catalogs.add(ec);
                                ElementCatalogBuilder.addUnionMember(union, ec);
                            } else {
                                log.warn("Failed to resolve extension catalog for %s from registry %s", bom, registry.getId());
                            }
                        }

                        appendNonPlatformExtensions(catalogBuilder, release.getQuarkusCoreVersion(), union);
                        if (release.getUpstreamQuarkusCoreVersion() != null) {
                            appendNonPlatformExtensions(catalogBuilder, release.getUpstreamQuarkusCoreVersion(), union);
                        }
                    }
                }
            }
        }

        return catalogBuilder.build();
    }

    public ExtensionCatalog resolveExtensionCatalog(String quarkusCoreVersion) throws RegistryResolutionException {
        if (quarkusCoreVersion == null) {
            return resolveExtensionCatalog();
        }

        final int registriesTotal = registries.size();
        if (registriesTotal == 0) {
            throw new RegistryResolutionException("No registries configured");
        }

        final ExtensionCatalogBuilder catalogBuilder = new ExtensionCatalogBuilder();
        final List<String> upstreamQuarkusVersions = new ArrayList<>(1);

        final AtomicInteger platformIndex = new AtomicInteger();
        collectPlatforms(quarkusCoreVersion, catalogBuilder, upstreamQuarkusVersions, platformIndex);

        int i = 0;
        while (i < upstreamQuarkusVersions.size()) {
            collectPlatforms(upstreamQuarkusVersions.get(i++), catalogBuilder, upstreamQuarkusVersions, platformIndex);
        }
        return catalogBuilder.build();
    }

    public ExtensionCatalog resolveExtensionCatalog(StreamCoords streamCoords) throws RegistryResolutionException {

        ensureRegistriesConfigured();

        final ExtensionCatalogBuilder catalogBuilder = new ExtensionCatalogBuilder();

        final String platformKey = streamCoords.getPlatformKey();
        final String streamId = streamCoords.getStreamId();

        PlatformStream stream = null;
        RegistryExtensionResolver registry = null;
        for (RegistryExtensionResolver qer : registries) {
            final PlatformCatalog platforms = qer.resolvePlatformCatalog();
            if (platforms == null) {
                continue;
            }
            if (platformKey == null) {
                for (Platform p : platforms.getPlatforms()) {
                    stream = p.getStream(streamId);
                    if (stream != null) {
                        registry = qer;
                        break;
                    }
                }
            } else {
                final Platform platform = platforms.getPlatform(platformKey);
                if (platform == null) {
                    continue;
                }
                stream = platform.getStream(streamId);
                registry = qer;
            }
            break;
        }

        if (stream == null) {
            Platform requestedPlatform = null;
            final List<Platform> knownPlatforms = new ArrayList<>();
            for (RegistryExtensionResolver qer : registries) {
                final PlatformCatalog platforms = qer.resolvePlatformCatalog();
                if (platforms == null) {
                    continue;
                }
                if (platformKey != null) {
                    requestedPlatform = platforms.getPlatform(platformKey);
                    if (requestedPlatform != null) {
                        break;
                    }
                }
                for (Platform platform : platforms.getPlatforms()) {
                    knownPlatforms.add(platform);
                }
            }

            final StringBuilder buf = new StringBuilder();
            if (requestedPlatform != null) {
                buf.append("Failed to locate stream ").append(streamId)
                        .append(" in platform " + requestedPlatform.getPlatformKey());
            } else if (knownPlatforms.isEmpty()) {
                buf.append("None of the registries provided any platform");
            } else {
                if (platformKey == null) {
                    buf.append("Failed to locate stream ").append(streamId).append(" in platform(s): ");
                } else {
                    buf.append("Failed to locate platform ").append(platformKey).append(" among available platform(s): ");
                }
                buf.append(knownPlatforms.get(0).getPlatformKey());
                for (int i = 1; i < knownPlatforms.size(); ++i) {
                    buf.append(", ").append(knownPlatforms.get(i).getPlatformKey());
                }
            }
            throw new RegistryResolutionException(buf.toString());
        }

        final ElementCatalogBuilder<ExtensionCatalog> elemBuilder = catalogBuilder.getRegistryCatalog(registry.getId());
        int releaseIndex = 0;
        for (PlatformRelease release : stream.getReleases()) {
            final UnionBuilder<ExtensionCatalog> union = elemBuilder
                    .getOrCreateUnion(new PlatformStackIndex(0, 0, releaseIndex++));
            for (ArtifactCoords bom : release.getMemberBoms()) {
                final ExtensionCatalog ec = registry.resolvePlatformExtensions(bom);
                catalogBuilder.catalogs.add(ec);
                ElementCatalogBuilder.addUnionMember(union, ec);
            }

            appendNonPlatformExtensions(catalogBuilder, release.getQuarkusCoreVersion(), union);
            if (release.getUpstreamQuarkusCoreVersion() != null) {
                appendNonPlatformExtensions(catalogBuilder, release.getUpstreamQuarkusCoreVersion(), union);
            }
        }

        return catalogBuilder.build();
    }

    public ExtensionCatalog resolveExtensionCatalog(Collection<ArtifactCoords> platforms)
            throws RegistryResolutionException {
        if (platforms.isEmpty()) {
            return resolveExtensionCatalog();
        }

        final ExtensionCatalogBuilder catalogBuilder = new ExtensionCatalogBuilder();

        String quarkusVersion = null;
        for (ArtifactCoords bom : platforms) {
            final List<RegistryExtensionResolver> registries;
            try {
                registries = filterRegistries(r -> r.checkPlatform(bom));
            } catch (ExclusiveProviderConflictException e) {
                final StringBuilder buf = new StringBuilder();
                buf.append(
                        "The following registries were configured as exclusive providers of the ");
                buf.append(bom);
                buf.append("platform: ").append(e.conflictingRegistries.get(0).getId());
                for (int i = 1; i < e.conflictingRegistries.size(); ++i) {
                    buf.append(", ").append(e.conflictingRegistries.get(i).getId());
                }
                throw new RuntimeException(buf.toString());
            }

            final ExtensionCatalog catalog = resolvePlatformExtensions(bom, registries);
            if (catalog != null) {
                catalogBuilder.catalogs.add(catalog);
                if (quarkusVersion == null) {
                    quarkusVersion = catalog.getQuarkusCoreVersion();
                }
                appendNonPlatformExtensions(catalogBuilder, quarkusVersion, null);

                if (catalog.getUpstreamQuarkusCoreVersion() != null) {
                    appendNonPlatformExtensions(catalogBuilder, catalog.getUpstreamQuarkusCoreVersion(), null);
                }
            }
        }
        return catalogBuilder.build();
    }

    public void clearRegistryCache() throws RegistryResolutionException {
        for (RegistryExtensionResolver registry : registries) {
            registry.clearCache();
        }
    }

    private void ensureRegistriesConfigured() throws RegistryResolutionException {
        final int registriesTotal = registries.size();
        if (registriesTotal == 0) {
            throw new RegistryResolutionException("No registries configured");
        }
    }

    private ExtensionCatalog resolvePlatformExtensions(ArtifactCoords bom, List<RegistryExtensionResolver> registries) {
        if (registries.isEmpty()) {
            log.debug("None of the configured registries recognizes platform %s", bom);
            return null;
        }
        for (RegistryExtensionResolver registry : registries) {
            try {
                return registry.resolvePlatformExtensions(bom);
            } catch (RegistryResolutionException e) {
            }
        }
        final StringBuilder buf = new StringBuilder();
        buf.append("Failed to resolve platform ").append(bom).append(" using the following registries: ");
        buf.append(registries.get(0).getId());
        for (int i = 1; i < registries.size(); ++i) {
            buf.append(", ").append(registries.get(i++));
        }
        log.warn(buf.toString());
        return null;
    }

    private void appendNonPlatformExtensions(
            ExtensionCatalogBuilder catalogBuilder,
            String quarkusVersion,
            UnionBuilder<ExtensionCatalog> union) throws RegistryResolutionException {
        for (RegistryExtensionResolver registry : catalogBuilder.getRegistriesForQuarkusCore(quarkusVersion)) {
            final ExtensionCatalog nonPlatformCatalog = registry.resolveNonPlatformExtensions(quarkusVersion);
            if (nonPlatformCatalog != null) {
                catalogBuilder.catalogs.add(nonPlatformCatalog);
                if (union != null) {
                    ElementCatalogBuilder.addUnionMember(union, nonPlatformCatalog);
                }
            }
        }
    }

    private void collectPlatforms(String quarkusCoreVersion,
            ExtensionCatalogBuilder catalogBuilder,
            Collection<String> upstreamQuarkusVersions,
            AtomicInteger platformIndex)
            throws RegistryResolutionException {
        final List<RegistryExtensionResolver> quarkusVersionRegistries = catalogBuilder
                .getRegistriesForQuarkusCore(quarkusCoreVersion);

        for (RegistryExtensionResolver registry : quarkusVersionRegistries) {
            final PlatformCatalog platformCatalog = registry.resolvePlatformCatalog(quarkusCoreVersion);
            if (platformCatalog == null) {
                continue;
            }
            final Collection<Platform> platforms = platformCatalog.getPlatforms();
            if (platforms.isEmpty()) {
                continue;
            }
            final ElementCatalogBuilder<ExtensionCatalog> elemBuilder = catalogBuilder.getRegistryCatalog(registry.getId());
            for (Platform p : platforms) {
                int streamIndex = 0;
                for (PlatformStream s : p.getStreams()) {
                    ++streamIndex;
                    int releaseIndex = 0;
                    for (PlatformRelease r : s.getReleases()) {
                        ++releaseIndex;
                        final UnionBuilder<ExtensionCatalog> union = elemBuilder.getOrCreateUnion(
                                new PlatformStackIndex(platformIndex.incrementAndGet(), streamIndex, releaseIndex));
                        for (ArtifactCoords bom : r.getMemberBoms()) {
                            final ExtensionCatalog catalog = registry.resolvePlatformExtensions(bom);
                            if (catalog != null) {
                                catalogBuilder.catalogs.add(catalog);
                                ElementCatalogBuilder.addUnionMember(union, catalog);
                            }
                        }

                        appendNonPlatformExtensions(catalogBuilder, quarkusCoreVersion, union);
                        final String upstreamQuarkusVersion = r.getUpstreamQuarkusCoreVersion();
                        if (upstreamQuarkusVersion != null) {
                            if (!upstreamQuarkusVersions.contains(upstreamQuarkusVersion)) {
                                upstreamQuarkusVersions.add(upstreamQuarkusVersion);
                            }
                            appendNonPlatformExtensions(catalogBuilder, upstreamQuarkusVersion, union);
                        }
                    }
                }
            }
        }
    }

    private List<RegistryExtensionResolver> getRegistriesForQuarkusVersion(String quarkusCoreVersion) {
        try {
            return filterRegistries(r -> r.checkQuarkusVersion(quarkusCoreVersion));
        } catch (ExclusiveProviderConflictException e) {
            final StringBuilder buf = new StringBuilder();
            buf.append(
                    "The following registries were configured as exclusive providers of extensions based on Quarkus version ");
            buf.append(quarkusCoreVersion);
            buf.append(": ").append(e.conflictingRegistries.get(0).getId());
            for (int i = 1; i < e.conflictingRegistries.size(); ++i) {
                buf.append(", ").append(e.conflictingRegistries.get(i).getId());
            }
            throw new RuntimeException(buf.toString());
        }
    }

    private List<RegistryExtensionResolver> filterRegistries(Function<RegistryExtensionResolver, Integer> recognizer)
            throws ExclusiveProviderConflictException {
        RegistryExtensionResolver exclusiveProvider = null;
        List<RegistryExtensionResolver> filtered = null;
        List<RegistryExtensionResolver> conflicts = null;
        for (int i = 0; i < registries.size(); ++i) {
            final RegistryExtensionResolver registry = registries.get(i);
            final int versionCheck = recognizer.apply(registry);

            if (versionCheck == RegistryExtensionResolver.VERSION_NOT_RECOGNIZED) {
                if (exclusiveProvider == null && filtered == null) {
                    filtered = new ArrayList<>(registries.size() - 1);
                    for (int j = 0; j < i; ++j) {
                        filtered.add(registries.get(j));
                    }
                }
                continue;
            }

            if (versionCheck == RegistryExtensionResolver.VERSION_EXCLUSIVE_PROVIDER) {
                if (exclusiveProvider == null) {
                    exclusiveProvider = registry;
                } else {
                    if (conflicts == null) {
                        conflicts = new ArrayList<>();
                        conflicts.add(exclusiveProvider);
                    }
                    conflicts.add(registry);
                }
            }

            if (filtered != null) {
                filtered.add(registry);
            }
        }

        if (conflicts != null) {
            throw new ExclusiveProviderConflictException(conflicts);
        }

        return exclusiveProvider == null ? filtered == null ? registries : filtered : Arrays.asList(exclusiveProvider);
    }
}
