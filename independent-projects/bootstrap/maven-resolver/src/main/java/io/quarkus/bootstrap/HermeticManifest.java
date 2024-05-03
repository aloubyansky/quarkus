package io.quarkus.bootstrap;

import static io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext.createFileProfileActivator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.model.Extension;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.profile.activation.JdkVersionProfileActivator;
import org.apache.maven.model.profile.activation.OperatingSystemProfileActivator;
import org.apache.maven.model.profile.activation.PropertyProfileActivator;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.listener.ChainedRepositoryListener;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.EffectiveModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.options.BootstrapMavenOptions;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.fs.util.ZipUtils;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.GAV;

public class HermeticManifest {

    private static final Map<GAV, ModuleArtifacts> moduleArtifacts = new ConcurrentHashMap<>();

    private static final String MAVEN_VERSION = "3.9.6";

    private static class ModuleArtifacts {

        final GAV gav;
        String repositoryUrl;
        List<ArtifactKey> artifacts = List.of();

        ModuleArtifacts(GAV gav) {
            this.gav = gav;
        }

        public boolean isNonDefaultRepo() {
            if (repositoryUrl == null) {
                System.out.println("WARN no repo for " + gav);
                return false;
            }
            return !repositoryUrl.equals("https://repo.maven.apache.org/maven2");
        }

        public void addArtifact(Artifact a) {
            if (!ArtifactCoords.TYPE_POM.equals(a.getExtension())) {
                var key = ArtifactKey.of(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getExtension());
                if (artifacts.isEmpty()) {
                    artifacts = new ArrayList<>(1);
                    artifacts.add(key);
                }
                if (!artifacts.contains(key)) {
                    artifacts.add(key);
                }
            }
        }
    }

    private static ModuleArtifacts getModuleArtifacts(Artifact a) {
        return moduleArtifacts.computeIfAbsent(getGav(a), ModuleArtifacts::new);
    }

    private static void addModuleArtifact(Artifact a) {
        var ma = moduleArtifacts.get(getGav(a));
        if (ma != null) {
            ma.addArtifact(a);
        }
    }

    private static GAV getGav(Artifact a) {
        return new GAV(a.getGroupId(), a.getArtifactId(), a.getVersion());
    }

    public static void main(String[] args) throws Exception {

        final Path projectDir = Path.of("/home/aloubyansky/git/camel");
        if (!Files.exists(projectDir)) {
            throw new IllegalArgumentException("Project directory " + projectDir + " does not exist");
        }
        final Path localRepo = Path.of("/home/aloubyansky/playground/test-repo");
        IoUtils.recursiveDelete(localRepo);
        Files.createDirectories(localRepo);

        BootstrapMavenContext ctx = getMavenContext(projectDir, localRepo);

        final Path extensionsXmlPath = ctx.getCurrentProject().getDir().resolve(".mvn/extensions.xml");
        if (Files.exists(extensionsXmlPath)) {
            final Xpp3Dom extensionsXml;
            try (BufferedReader reader = Files.newBufferedReader(extensionsXmlPath)) {
                extensionsXml = Xpp3DomBuilder.build(reader);
            }
            for (var extension : extensionsXml.getChildren()) {
                var ext = new Extension();
                var groupId = getRequiredChild(extension, "groupId").getValue();
                var artifactId = getRequiredChild(extension, "artifactId").getValue();
                var version = getRequiredChild(extension, "version").getValue();
                ext.setGroupId(groupId);
                ext.setArtifactId(artifactId);
                ext.setVersion(version);
                collectExtensionDeps(ctx, ext);
            }
        }

        var defaultPlugins = resolveDefaultLifecyclePlugins(ctx);

        var modelBuilder = new EffectiveModelResolver(new MavenArtifactResolver(ctx));
        for (var module : ctx.getWorkspace().getProjects().values()) {
            collectModuleBuildDeps(module, modelBuilder, ctx, defaultPlugins);
        }

        createManifest(localRepo);
    }

    private static void collectModuleBuildDeps(LocalProject module, EffectiveModelResolver modelBuilder,
            BootstrapMavenContext ctx, Map<ArtifactKey, String> defaultPlugins) throws Exception {
        if (!module.getArtifactId().equals("camel-kudu")) {
            return;
        }
        var model = modelBuilder.resolveEffectiveModel(module.getAppArtifact());
        System.out.println(model.getGroupId() + ":" + model.getArtifactId());
        collectModuleDeps(ctx, model);

        var moduleDefaultPlugins = new HashMap<>(defaultPlugins);
        if (model.getBuild() != null) {
            for (var plugin : model.getBuild().getPlugins()) {
                System.out.println("plugin " + plugin.getId());
                collectPluginDeps(ctx, plugin, model);
                moduleDefaultPlugins.remove(ArtifactKey.of(plugin.getGroupId(), plugin.getArtifactId(),
                        ArtifactCoords.DEFAULT_CLASSIFIER, ArtifactCoords.TYPE_JAR));
            }
            for (var ext : model.getBuild().getExtensions()) {
                collectExtensionDeps(ctx, ext);
            }
        }
        if (!moduleDefaultPlugins.isEmpty()) {
            if (model.getBuild() != null) {
                for (var pl : model.getBuild().getPluginManagement().getPlugins()) {
                    moduleDefaultPlugins.computeIfPresent(ArtifactKey.of(pl.getGroupId(), pl.getArtifactId(),
                            ArtifactCoords.DEFAULT_CLASSIFIER, ArtifactCoords.TYPE_JAR), (k, v) -> pl.getVersion());
                }
            }
            for (var plugin : moduleDefaultPlugins.entrySet()) {
                var d = new Dependency(
                        new DefaultArtifact(plugin.getKey().getGroupId(), plugin.getKey().getArtifactId(),
                                ArtifactCoords.TYPE_JAR, plugin.getValue()),
                        JavaScopes.COMPILE, false, List.of());

                var allRepos = new ArrayList<RemoteRepository>();
                for (org.apache.maven.model.Repository repo : model.getPluginRepositories()) {
                    final RemoteRepository.Builder repoBuilder = new RemoteRepository.Builder(repo.getId(),
                            repo.getLayout(),
                            repo.getUrl());
                    org.apache.maven.model.RepositoryPolicy policy = repo.getReleases();
                    if (policy != null) {
                        repoBuilder.setReleasePolicy(toAetherRepoPolicy(policy));
                    }
                    policy = repo.getSnapshots();
                    if (policy != null) {
                        repoBuilder.setSnapshotPolicy(toAetherRepoPolicy(policy));
                    }
                    allRepos.add(repoBuilder.build());
                }

                var request = new CollectRequest()
                        .setRoot(d)
                        .setRepositories(ctx.getRemoteRepositoryManager().aggregateRepositories(
                                ctx.getRepositorySystemSession(), ctx.getRemotePluginRepositories(), allRepos, true));

                collectDeps(
                        ctx.getRepositorySystem().collectDependencies(ctx.getRepositorySystemSession(), request).getRoot(), -1);
            }
        }
    }

    private static RepositoryPolicy toAetherRepoPolicy(org.apache.maven.model.RepositoryPolicy modelPolicy) {
        return new RepositoryPolicy(modelPolicy.isEnabled(),
                isEmpty(modelPolicy.getUpdatePolicy()) ? RepositoryPolicy.UPDATE_POLICY_DAILY
                        : modelPolicy.getUpdatePolicy(),
                isEmpty(modelPolicy.getChecksumPolicy()) ? RepositoryPolicy.CHECKSUM_POLICY_WARN
                        : modelPolicy.getChecksumPolicy());
    }

    private static boolean isEmpty(final CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    private static void createManifest(Path localRepo) throws IOException {
        Path output = localRepo.resolve("artifacts.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            for (var ma : moduleArtifacts.values()) {
                writer.append(ma.gav.getGroupId()).append(':').append(ma.gav.getArtifactId()).append("::")
                        .append(ArtifactCoords.TYPE_POM).append(':').append(ma.gav.getVersion());
                if (ma.isNonDefaultRepo()) {
                    writer.append('(').append(ma.repositoryUrl).append(')');
                }
                writer.newLine();
                for (var a : ma.artifacts) {
                    writer.append(a.getGroupId()).append(':').append(a.getArtifactId()).append(':');
                    if (a.getType().equals("test-jar")) {
                        writer.append("tests").append(':').append(ArtifactCoords.TYPE_JAR);
                    } else {
                        writer.append(a.getClassifier()).append(':').append(a.getType());
                    }
                    writer.append(':').append(ma.gav.getVersion());
                    if (ma.isNonDefaultRepo()) {
                        writer.append('(').append(ma.repositoryUrl).append(')');
                    }
                    writer.newLine();
                }
            }
        }
    }

    private static Map<ArtifactKey, String> resolveDefaultLifecyclePlugins(BootstrapMavenContext ctx) throws Exception {

        var mavenCoreJar = ctx.getRepositorySystem().resolveArtifact(ctx.getRepositorySystemSession(),
                new ArtifactRequest()
                        .setArtifact(
                                new DefaultArtifact("org.apache.maven", "maven-core", ArtifactCoords.TYPE_JAR, MAVEN_VERSION))
                        .setRepositories(ctx.getRemoteRepositories()))
                .getArtifact().getFile().toPath();
        final Xpp3Dom defaultBindingsDom;
        try (FileSystem fs = ZipUtils.newFileSystem(mavenCoreJar)) {
            var defaultBindingsPath = fs.getPath("META-INF/plexus/default-bindings.xml");
            if (!Files.exists(defaultBindingsPath)) {
                throw new RuntimeException("Failed to locate META-INF/plexus/default-bindings.xml in " + mavenCoreJar);
            }
            try (BufferedReader reader = Files.newBufferedReader(defaultBindingsPath)) {
                defaultBindingsDom = Xpp3DomBuilder.build(reader);
            }
        }
        var components = getRequiredChild(defaultBindingsDom, "components");
        var pluginsCoords = new HashMap<ArtifactKey, String>();
        for (var component : components.getChildren()) {
            var role = component.getChild("role");
            if (role == null || !"org.apache.maven.lifecycle.mapping.LifecycleMapping".equals(role.getValue())) {
                continue;
            }
            var config = getRequiredChild(component, "configuration");
            config = getRequiredChild(config, "lifecycles");
            config = getRequiredChild(config, "lifecycle");
            config = getRequiredChild(config, "phases");
            for (var phase : config.getChildren()) {
                var goals = phase.getValue().split(",");
                for (var goal : goals) {
                    goal = goal.trim();
                    var i = goal.lastIndexOf(':');
                    if (i < 0) {
                        throw new RuntimeException("Failed to locate ':' in " + goal);
                    }
                    var coords = ArtifactCoords.fromString(goal.substring(0, i));
                    pluginsCoords.put(coords.getKey(), coords.getVersion());
                }
            }
        }
        return pluginsCoords;
    }

    private static Xpp3Dom getRequiredChild(Xpp3Dom parent, String name) {
        var child = parent.getChild(name);
        if (child == null) {
            throw new IllegalArgumentException(parent.getName() + " element does not contain child " + name);
        }
        return child;
    }

    private static BootstrapMavenContext getMavenContext(Path projectDir, Path localRepo) throws BootstrapMavenException {
        BootstrapMavenContext ctx = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setWorkspaceDiscovery(true)
                .setEffectiveModelBuilder(true)
                .setPreferPomsFromWorkspace(true)
                .setCurrentProject(projectDir.toString())
                .setLocalRepository(localRepo.toString())
                .setArtifactTransferLogging(false));
        var session = ctx.getRepositorySystemSession();
        var mutableSession = new DefaultRepositorySystemSession(session);
        mutableSession.setRepositoryListener(ChainedRepositoryListener.newInstance(session.getRepositoryListener(),
                new AbstractRepositoryListener() {
                    public void artifactResolved(RepositoryEvent event) {
                        final ArtifactRequest request = (ArtifactRequest) event.getTrace().getData();
                        RemoteRepository repo = null;
                        for (var r : request.getRepositories()) {
                            if (event.getRepository().getId().equals(r.getId())) {
                                repo = r;
                                break;
                            }
                        }
                        if (repo != null) {
                            getModuleArtifacts(event.getArtifact()).repositoryUrl = repo.getUrl();
                        } // otherwise it's a local module
                    }
                }));
        ctx = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setRepositorySystem(ctx.getRepositorySystem())
                .setRepositorySystemSession(mutableSession)
                .setRemoteRepositories(ctx.getRemoteRepositories())
                .setRemotePluginRepositories(ctx.getRemotePluginRepositories())
                .setRemoteRepositoryManager(ctx.getRemoteRepositoryManager())
                .setSettingsDecrypter(ctx.getSettingsDecrypter())
                .setCurrentProject(ctx.getCurrentProject()));
        return ctx;
    }

    private static void collectExtensionDeps(BootstrapMavenContext ctx, Extension ext) throws Exception {
        var d = new Dependency(
                new DefaultArtifact(ext.getGroupId(), ext.getArtifactId(), ArtifactCoords.TYPE_JAR, ext.getVersion()),
                JavaScopes.COMPILE, false, List.of());
        var request = new CollectRequest()
                .setRoot(d)
                .setRepositories(ctx.getRemoteRepositories());
        collectDeps(ctx.getRepositorySystem().collectDependencies(ctx.getRepositorySystemSession(), request).getRoot(), -1);
    }

    private static void collectPluginDeps(BootstrapMavenContext ctx, Plugin plugin, Model module) throws Exception {
        var pluginDep = new Dependency(
                new DefaultArtifact(plugin.getGroupId(), plugin.getArtifactId(), ArtifactCoords.TYPE_JAR, plugin.getVersion()),
                JavaScopes.COMPILE, false, List.of());
        var request = new CollectRequest()
                .setRoot(pluginDep)
                .setRepositories(ctx.getRemotePluginRepositories());
        if (!plugin.getDependencies().isEmpty()) {
            var pluginDescr = getPluginDescriptor(ctx, pluginDep.getArtifact());
            if (pluginDescr.getDependencies().isEmpty()) {
                request.setDependencies(toAetherDeps(plugin.getDependencies(), false));
            } else {
                var effectiveDeps = new ArrayList<Dependency>(pluginDescr.getDependencies().size());
                var map = new HashMap<ArtifactKey, org.apache.maven.model.Dependency>(plugin.getDependencies().size());
                for (var pd : plugin.getDependencies()) {
                    map.put(ArtifactKey.of(pd.getGroupId(), pd.getArtifactId(), pd.getClassifier(), pd.getType()), pd);
                    effectiveDeps.add(toAetherDep(pd));
                }
                for (var pd : pluginDescr.getDependencies()) {
                    var a = pd.getArtifact();
                    if (!JavaScopes.TEST.equals(pd.getScope()) && !map.containsKey(
                            ArtifactKey.of(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getExtension()))) {
                        effectiveDeps.add(pd);
                    }
                }
                request.setDependencies(effectiveDeps);
            }
        }
        collectDeps(ctx.getRepositorySystem().collectDependencies(ctx.getRepositorySystemSession(), request).getRoot(), -1);

        if (plugin.getArtifactId().equals("maven-compiler-plugin")) {
            var config = plugin.getConfiguration();
            if (config instanceof Xpp3Dom dom) {
                var useDm = isAnnotationProcessorDependencyManagement(dom);
                final List<Dependency> managedDeps = useDm
                        ? toAetherDeps(module.getDependencyManagement().getDependencies(), true)
                        : List.of();
                var appList = dom.getChild("annotationProcessorPaths");
                if (appList != null) {
                    var arr = appList.getChildren();
                    if (arr != null) {
                        for (var path : arr) {
                            var e = path.getChild("groupId");
                            if (e == null) {
                                continue;
                            }
                            var groupId = e.getValue();
                            e = path.getChild("artifactId");
                            if (e == null) {
                                continue;
                            }
                            var artifactId = e.getValue();
                            e = path.getChild("classifier");
                            var classifier = e == null ? ArtifactCoords.DEFAULT_CLASSIFIER : e.getValue();
                            e = path.getChild("type");
                            var type = e == null ? ArtifactCoords.TYPE_JAR : e.getValue();
                            e = path.getChild("version");
                            String version = null;
                            if (e != null) {
                                version = e.getValue();
                            } else {
                                if (!useDm) {
                                    throw new RuntimeException(
                                            "Missing value for annotation processor " + groupId + ":" + artifactId
                                                    + ":" + classifier + ":" + type);
                                }
                                if (module.getDependencyManagement() != null) {
                                    for (var md : module.getDependencyManagement().getDependencies()) {
                                        if (md.getArtifactId().equals(artifactId)
                                                && md.getGroupId().equals(groupId)
                                                && md.getClassifier().equals(classifier)
                                                && md.getType().equals(type)) {
                                            version = md.getVersion();
                                            break;
                                        }
                                    }
                                }
                                if (version == null) {
                                    throw new RuntimeException("Failed to determine version for annotation processor "
                                            + groupId + ":" + artifactId + ":" + classifier + ":" + type);
                                }
                            }
                            // TODO add support for exclusions
                            collectDeps(ctx, new DefaultArtifact(groupId, artifactId, classifier, type, version),
                                    List.of(),
                                    managedDeps,
                                    ctx.getRemotePluginRepositories());
                        }
                    }
                }
            }
        }
    }

    private static boolean isAnnotationProcessorDependencyManagement(Xpp3Dom dom) {
        var useDm = dom.getChild("annotationProcessorPathsUseDepMgmt");
        return useDm != null && Boolean.parseBoolean(useDm.getValue());
    }

    private static ArtifactDescriptorResult getPluginDescriptor(BootstrapMavenContext ctx, Artifact pluginArtifact)
            throws ArtifactDescriptorException, BootstrapMavenException {
        return ctx.getRepositorySystem().readArtifactDescriptor(ctx.getRepositorySystemSession(),
                new ArtifactDescriptorRequest()
                        .setArtifact(pluginArtifact)
                        .setRepositories(ctx.getRemotePluginRepositories()));
    }

    private static void collectModuleDeps(BootstrapMavenContext ctx, Model module) throws Exception {
        final Artifact artifact = new DefaultArtifact(module.getGroupId(), module.getArtifactId(), ArtifactCoords.TYPE_POM,
                module.getVersion());

        var root = ctx.getRepositorySystem().collectDependencies(ctx.getRepositorySystemSession(), new CollectRequest()
                .setRoot(new Dependency(artifact, JavaScopes.COMPILE))
                .setRepositories(ctx.getRemoteRepositories()))
                .getRoot();
        collectDeps(root, -1);

        collectDeps(ctx,
                artifact,
                toAetherDeps(module.getDependencies(), true),
                module.getDependencyManagement() == null ? List.of()
                        : toAetherDeps(module.getDependencyManagement().getDependencies(), true),
                ctx.getRemoteRepositories());

        if (artifact.getArtifactId().equals("camel-kudu")) {
            System.out.println("MODEL");
            module.getDependencies().forEach(d -> System.out.println("- " + d.getGroupId() + ":" + d.getArtifactId() + ":"
                    + d.getClassifier() + ":" + d.getType() + ":" + d.getVersion()));

            final BootstrapMavenOptions mvnArgs = ctx.getCliOptions();
            final DefaultProfileActivationContext context = new DefaultProfileActivationContext()
                    .setActiveProfileIds(mvnArgs.getActiveProfileIds())
                    .setInactiveProfileIds(mvnArgs.getInactiveProfileIds())
                    .setSystemProperties(System.getProperties())
                    .setProjectProperties(module.getProperties())
                    .setProjectDirectory(module.getProjectDirectory());
            final DefaultProfileSelector profileSelector = new DefaultProfileSelector()
                    .addProfileActivator(new PropertyProfileActivator())
                    .addProfileActivator(new JdkVersionProfileActivator())
                    .addProfileActivator(new OperatingSystemProfileActivator())
                    .addProfileActivator(createFileProfileActivator());
            List<org.apache.maven.model.Profile> selectedProfiles = profileSelector
                    .getActiveProfiles(module.getProfiles(), context, new ModelProblemCollector() {
                        public void add(ModelProblemCollectorRequest req) {
                            System.err.println("Failed to activate a Maven profile: " + req.getMessage());
                        }
                    });

            System.out.println("SELECTED PROFILES");
            selectedProfiles.forEach(p -> System.out.println("- " + p.getId()));
        }
    }

    private static void collectDeps(BootstrapMavenContext ctx, Artifact artifact, List<Dependency> deps,
            List<Dependency> managedDeps, List<RemoteRepository> repos)
            throws DependencyCollectionException, BootstrapMavenException {
        var root = ctx.getRepositorySystem().collectDependencies(ctx.getRepositorySystemSession(), new CollectRequest()
                .setRootArtifact(artifact)
                .setDependencies(deps)
                .setManagedDependencies(managedDeps)
                .setRepositories(repos))
                .getRoot();
        collectDeps(root, -1);
    }

    private static void collectDeps(DependencyNode node, int depth) {
        var a = node.getArtifact();
        if (a != null) {
            addModuleArtifact(a);
        }
        if (depth >= 0) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; ++i) {
                sb.append("  ");
            }
            sb.append(node.getDependency());
            System.out.println(sb);
        }
        for (var c : node.getChildren()) {
            collectDeps(c, depth < 0 ? depth : depth + 1);
        }
    }

    private static List<Dependency> toAetherDeps(List<org.apache.maven.model.Dependency> modelDeps, boolean includeTest) {
        if (modelDeps.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<Dependency>(modelDeps.size());
        for (var modelDep : modelDeps) {
            if (includeTest || !JavaScopes.TEST.equals(modelDep.getScope())) {
                result.add(toAetherDep(modelDep));
            }
        }
        return result;
    }

    private static Dependency toAetherDep(org.apache.maven.model.Dependency modelDep) {
        return new Dependency(
                new DefaultArtifact(modelDep.getGroupId(), modelDep.getArtifactId(), modelDep.getClassifier(),
                        modelDep.getType(), modelDep.getVersion()),
                modelDep.getScope(), modelDep.isOptional(), toAetherExclusions(modelDep.getExclusions()));
    }

    private static List<Exclusion> toAetherExclusions(List<org.apache.maven.model.Exclusion> modelExclusions) {
        if (modelExclusions.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<Exclusion>(modelExclusions.size());
        for (var e : modelExclusions) {
            result.add(new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"));
        }
        return result;
    }

    private static class PlexusLoggerImpl extends org.codehaus.plexus.logging.AbstractLogger {
        public PlexusLoggerImpl(int threshold, String name) {
            super(threshold, name);
        }

        @Override
        public void debug(String s, Throwable throwable) {
            if (isDebugEnabled()) {
                System.out.println("[DEBUG] " + s);
            }
        }

        @Override
        public void info(String s, Throwable throwable) {
            if (isInfoEnabled()) {
                System.out.println("[INFO] " + s);
            }
        }

        @Override
        public void warn(String s, Throwable throwable) {
            if (isWarnEnabled()) {
                System.out.println("[WARN] " + s);
            }
        }

        @Override
        public void error(String s, Throwable throwable) {
            if (isErrorEnabled()) {
                System.out.println("[ERROR] " + s);
            }
        }

        @Override
        public void fatalError(String s, Throwable throwable) {
            if (isFatalErrorEnabled()) {
                System.out.println("[FATAL] " + s);
            }
        }

        @Override
        public Logger getChildLogger(String s) {
            return this;
        }
    }
}
