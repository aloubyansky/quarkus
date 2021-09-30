package io.quarkus.gradle.builder;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyArtifact;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.gradle.tooling.provider.model.ParameterizedToolingModelBuilder;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.CapabilityContract;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import io.quarkus.bootstrap.model.gradle.ModelParameter;
import io.quarkus.bootstrap.model.gradle.impl.ModelParameterImpl;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.util.QuarkusModelHelper;
import io.quarkus.bootstrap.workspace.DefaultProcessedSources;
import io.quarkus.bootstrap.workspace.DefaultWorkspaceModule;
import io.quarkus.bootstrap.workspace.ProcessedSources;
import io.quarkus.gradle.QuarkusPlugin;
import io.quarkus.gradle.dependency.ApplicationDeploymentClasspathBuilder;
import io.quarkus.gradle.dependency.DependencyUtils;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.GACT;
import io.quarkus.maven.dependency.GACTV;
import io.quarkus.maven.dependency.GAV;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathCollection;
import io.quarkus.paths.PathList;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.util.HashUtil;

public class GradleApplicationModelBuilder implements ParameterizedToolingModelBuilder<ModelParameter> {

    private static final String MAIN_RESOURCES_OUTPUT = "build/resources/main";
    private static final String CLASSES_OUTPUT = "build/classes";
    private static final String DEPLOYMENT_CONFIGURATION = "quarkusDeploymentConfiguration";
    private static final String CLASSPATH_CONFIGURATION = "quarkusClasspathConfiguration";

    private static Configuration classpathConfig(Project project, LaunchMode mode) {
        if (LaunchMode.TEST.equals(mode)) {
            return project.getConfigurations().getByName(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        }
        if (LaunchMode.DEVELOPMENT.equals(mode)) {
            Configuration classpathConfiguration = project.getConfigurations().findByName(CLASSPATH_CONFIGURATION);
            if (classpathConfiguration != null) {
                project.getConfigurations().remove(classpathConfiguration);
            }

            return project.getConfigurations().create(CLASSPATH_CONFIGURATION).extendsFrom(
                    project.getConfigurations().getByName(QuarkusPlugin.DEV_MODE_CONFIGURATION_NAME),
                    project.getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME),
                    project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
        }
        return project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
    }

    private static Configuration deploymentClasspathConfig(Project project, LaunchMode mode,
            Collection<org.gradle.api.artifacts.Dependency> platforms) {

        Configuration deploymentConfiguration = project.getConfigurations().findByName(DEPLOYMENT_CONFIGURATION);
        if (deploymentConfiguration != null) {
            project.getConfigurations().remove(deploymentConfiguration);
        }

        deploymentConfiguration = project.getConfigurations().create(DEPLOYMENT_CONFIGURATION)
                .withDependencies(ds -> ds.addAll(platforms));
        Configuration implementationDeployment = project.getConfigurations().findByName(ApplicationDeploymentClasspathBuilder
                .toDeploymentConfigurationName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME));
        if (implementationDeployment != null) {
            deploymentConfiguration.extendsFrom(implementationDeployment);
        }

        if (LaunchMode.TEST.equals(mode)) {
            Configuration testDeploymentConfiguration = project.getConfigurations()
                    .findByName(ApplicationDeploymentClasspathBuilder
                            .toDeploymentConfigurationName(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME));
            if (testDeploymentConfiguration != null) {
                deploymentConfiguration.extendsFrom(testDeploymentConfiguration);
            }
        }
        if (LaunchMode.DEVELOPMENT.equals(mode)) {
            Configuration devDeploymentConfiguration = project.getConfigurations()
                    .findByName(ApplicationDeploymentClasspathBuilder
                            .toDeploymentConfigurationName(QuarkusPlugin.DEV_MODE_CONFIGURATION_NAME));
            if (devDeploymentConfiguration != null) {
                deploymentConfiguration.extendsFrom(devDeploymentConfiguration);
            }

        }
        return deploymentConfiguration;
    }

    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals(ApplicationModel.class.getName());
    }

    @Override
    public Class<ModelParameter> getParameterType() {
        return ModelParameter.class;
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        final ModelParameterImpl modelParameter = new ModelParameterImpl();
        modelParameter.setMode(LaunchMode.DEVELOPMENT.toString());
        return buildAll(modelName, modelParameter, project);
    }

    @Override
    public Object buildAll(String modelName, ModelParameter parameter, Project project) {
        final LaunchMode mode = LaunchMode.valueOf(parameter.getMode());

        final List<org.gradle.api.artifacts.Dependency> deploymentDeps = DependencyUtils.getEnforcedPlatforms(project);
        final PlatformImports platformImports = resolvePlatformImports(project, deploymentDeps);

        final ResolvedDependency appArtifact = getProjectArtifact(project, mode);
        final ApplicationModelBuilder modelBuilder = new ApplicationModelBuilder()
                .setAppArtifact(appArtifact)
                .addLocalProjectArtifact(new GACT(appArtifact.getGroupId(), appArtifact.getArtifactId()))
                .setPlatformImports(platformImports);

        final Map<ArtifactKey, ResolvedDependencyBuilder> appDependencies = new LinkedHashMap<>();
        Configuration classpathConfig = classpathConfig(project, mode);
        collectDependencies(classpathConfig.getResolvedConfiguration(), mode, project, appDependencies, modelBuilder);

        Configuration deploymentConfig = deploymentClasspathConfig(project, mode, deploymentDeps);
        collectExtensionDependencies(deploymentConfig, appDependencies, modelBuilder);

        for (ResolvedDependencyBuilder d : appDependencies.values()) {
            modelBuilder.addDependency(d.build());
        }
        return modelBuilder.build();
    }

    public static ResolvedDependency getProjectArtifact(Project project, LaunchMode mode) {
        final ResolvedDependencyBuilder appArtifact = ResolvedDependencyBuilder.newInstance()
                .setGroupId(project.getGroup().toString())
                .setArtifactId(project.getName())
                .setVersion(project.getVersion().toString());

        final JavaPluginExtension javaExtension = project.getExtensions().findByType(JavaPluginExtension.class);
        if (javaExtension == null) {
            throw new GradleException("Failed to locate Java plugin extension in " + project.getPath());
        }
        final DefaultWorkspaceModule mainModule = new DefaultWorkspaceModule(
                new GAV(appArtifact.getGroupId(), appArtifact.getArtifactId(), appArtifact.getVersion()),
                project.getProjectDir(), project.getBuildDir());

        initProjectModule(project, mainModule, javaExtension.getSourceSets().findByName(SourceSet.MAIN_SOURCE_SET_NAME), false);
        if (mode.equals(LaunchMode.TEST)) {
            initProjectModule(project, mainModule, javaExtension.getSourceSets().findByName(SourceSet.TEST_SOURCE_SET_NAME),
                    true);
        }

        final PathList.Builder paths = PathList.builder();
        mainModule.getMainSources().forEach(src -> {
            collectDestinationDirs(src, paths);
        });
        mainModule.getMainResources().forEach(src -> {
            collectDestinationDirs(src, paths);
        });

        return appArtifact.setWorkspaceModule(mainModule).setResolvedPaths(paths.build()).build();
    }

    private static void collectDestinationDirs(ProcessedSources src, final PathList.Builder paths) {
        if (!src.getDestinationDir().exists()) {
            return;
        }
        final Path path = src.getDestinationDir().toPath();
        if (paths.contains(path)) {
            return;
        }
        paths.add(path);
    }

    private static void processQuarkusDir(ResolvedDependencyBuilder artifactBuilder, Path quarkusDir,
            ApplicationModelBuilder modelBuilder) {
        if (!Files.exists(quarkusDir)) {
            return;
        }
        final Path quarkusDescr = quarkusDir.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME);
        if (!Files.exists(quarkusDescr)) {
            return;
        }
        final Properties extProps = QuarkusModelHelper.resolveDescriptor(quarkusDescr);
        if (extProps == null) {
            return;
        }
        artifactBuilder.setRuntimeExtensionArtifact();
        final String extensionCoords = artifactBuilder.toGACTVString();
        modelBuilder.handleExtensionProperties(extProps, extensionCoords);

        final String providesCapabilities = extProps.getProperty(BootstrapConstants.PROP_PROVIDES_CAPABILITIES);
        if (providesCapabilities != null) {
            modelBuilder
                    .addExtensionCapabilities(CapabilityContract.providesCapabilities(extensionCoords, providesCapabilities));
        }
    }

    private PlatformImports resolvePlatformImports(Project project,
            List<org.gradle.api.artifacts.Dependency> deploymentDeps) {
        final Configuration boms = project.getConfigurations()
                .detachedConfiguration(deploymentDeps.toArray(new org.gradle.api.artifacts.Dependency[0]));
        final PlatformImportsImpl platformImports = new PlatformImportsImpl();
        boms.getResolutionStrategy().eachDependency(d -> {
            final String group = d.getTarget().getGroup();
            final String name = d.getTarget().getName();
            if (name.endsWith(BootstrapConstants.PLATFORM_DESCRIPTOR_ARTIFACT_ID_SUFFIX)) {
                platformImports.addPlatformDescriptor(group, name, d.getTarget().getVersion(), "json",
                        d.getTarget().getVersion());
            } else if (name.endsWith(BootstrapConstants.PLATFORM_PROPERTIES_ARTIFACT_ID_SUFFIX)) {
                final DefaultDependencyArtifact dep = new DefaultDependencyArtifact();
                dep.setExtension("properties");
                dep.setType("properties");
                dep.setName(name);

                final DefaultExternalModuleDependency gradleDep = new DefaultExternalModuleDependency(
                        group, name, d.getTarget().getVersion(), null);
                gradleDep.addArtifact(dep);

                for (ResolvedArtifact a : project.getConfigurations().detachedConfiguration(gradleDep)
                        .getResolvedConfiguration().getResolvedArtifacts()) {
                    if (a.getName().equals(name)) {
                        try {
                            platformImports.addPlatformProperties(group, name, null, "properties", d.getTarget().getVersion(),
                                    a.getFile().toPath());
                        } catch (AppModelResolverException e) {
                            throw new GradleException("Failed to import platform properties " + a.getFile(), e);
                        }
                        break;
                    }
                }
            }

        });
        boms.getResolvedConfiguration();
        return platformImports;
    }

    private void collectExtensionDependencies(Configuration deploymentConfiguration,
            Map<ArtifactKey, ResolvedDependencyBuilder> appDependencies, ApplicationModelBuilder modelBuilder) {
        final ResolvedConfiguration rc = deploymentConfiguration.getResolvedConfiguration();
        for (ResolvedArtifact a : rc.getResolvedArtifacts()) {
            if (isDependency(a)) {
                final ResolvedDependencyBuilder dep = appDependencies.computeIfAbsent(
                        toAppDependenciesKey(a.getModuleVersion().getId().getGroup(), a.getName(), a.getClassifier()),
                        k -> {
                            return toDependency(a);
                        });
                dep.setDeploymentCp();
            }
        }
    }

    private void collectDependencies(ResolvedConfiguration configuration,
            LaunchMode mode, Project project, Map<ArtifactKey, ResolvedDependencyBuilder> appDependencies,
            ApplicationModelBuilder modelBuilder) {

        final Set<ResolvedArtifact> resolvedArtifacts = configuration.getResolvedArtifacts();
        // if the number of artifacts is less than the number of files then probably
        // the project includes direct file dependencies
        final Set<File> artifactFiles = resolvedArtifacts.size() < configuration.getFiles().size()
                ? new HashSet<>(resolvedArtifacts.size())
                : null;

        configuration.getFirstLevelModuleDependencies()
                .forEach(d -> collectDependencies(d, mode, project, appDependencies, artifactFiles, new HashSet<>(),
                        modelBuilder));

        if (artifactFiles != null) {
            // detect FS paths that aren't provided by the resolved artifacts
            for (File f : configuration.getFiles()) {
                if (artifactFiles.contains(f)) {
                    continue;
                }
                // here we are trying to represent a direct FS path dependency
                // as an artifact dependency
                // SHA1 hash is used to avoid long file names in the lib dir
                final String parentPath = f.getParent();
                final String group = HashUtil.sha1(parentPath == null ? f.getName() : parentPath);
                String name = f.getName();
                String type = "jar";
                if (!f.isDirectory()) {
                    final int dot = f.getName().lastIndexOf('.');
                    if (dot > 0) {
                        name = f.getName().substring(0, dot);
                        type = f.getName().substring(dot + 1);
                    }
                }
                // hash could be a better way to represent the version
                final String version = String.valueOf(f.lastModified());
                final ResolvedDependencyBuilder artifactBuilder = ResolvedDependencyBuilder.newInstance()
                        .setGroupId(group)
                        .setArtifactId(name)
                        .setType(type)
                        .setVersion(version)
                        .setResolvedPath(f.toPath())
                        .setDirect(true)
                        .setRuntimeCp();
                processQuarkusDependency(artifactBuilder, modelBuilder);
                appDependencies.put(artifactBuilder.getKey(), artifactBuilder);
            }
        }
    }

    private void collectDependencies(org.gradle.api.artifacts.ResolvedDependency resolvedDep, LaunchMode mode, Project project,
            Map<ArtifactKey, ResolvedDependencyBuilder> appDependencies, Set<File> artifactFiles,
            Set<ArtifactKey> processedModules, ApplicationModelBuilder modelBuilder) {

        for (ResolvedArtifact a : resolvedDep.getModuleArtifacts()) {
            final ArtifactKey artifactKey = toAppDependenciesKey(a.getModuleVersion().getId().getGroup(), a.getName(),
                    a.getClassifier());
            if (!isDependency(a) || appDependencies.containsKey(artifactKey)) {
                continue;
            }
            final ResolvedDependencyBuilder depBuilder = ResolvedDependencyBuilder.newInstance()
                    .setCoords(toArtifactCoords(a))
                    .setDirect(processedModules.isEmpty())
                    .setRuntimeCp();

            DefaultWorkspaceModule projectModule = null;
            PathCollection paths = null;
            if ((LaunchMode.DEVELOPMENT.equals(mode) || LaunchMode.TEST.equals(mode)) &&
                    a.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier) {

                final Project projectDep = project.getRootProject().findProject(
                        ((ProjectComponentIdentifier) a.getId().getComponentIdentifier()).getProjectPath());
                final JavaPluginExtension javaExtension = projectDep == null ? null
                        : projectDep.getExtensions().findByType(JavaPluginExtension.class);

                final String classifier = a.getClassifier();
                if (classifier == null || classifier.isEmpty()) {
                    final IncludedBuild includedBuild = DependencyUtils.includedBuild(project.getRootProject(), a.getName());
                    if (includedBuild != null) {
                        final PathList.Builder pathBuilder = PathList.builder();
                        addSubstitutedProject(pathBuilder, includedBuild.getProjectDir());
                        paths = pathBuilder.build();
                    } else if (javaExtension != null) {
                        final PathList.Builder pathBuilder = PathList.builder();
                        projectModule = initProjectModuleAndBuildPaths(projectDep, a, modelBuilder, depBuilder,
                                javaExtension, pathBuilder, SourceSet.MAIN_SOURCE_SET_NAME, false);
                        paths = pathBuilder.build();
                    }
                } else if (javaExtension != null) {
                    if ("test".equals(classifier)) {
                        final PathList.Builder pathBuilder = PathList.builder();
                        projectModule = initProjectModuleAndBuildPaths(projectDep, a, modelBuilder, depBuilder,
                                javaExtension, pathBuilder, SourceSet.TEST_SOURCE_SET_NAME, true);
                        paths = pathBuilder.build();
                    } else if ("test-fixtures".equals(classifier)) {
                        final PathList.Builder pathBuilder = PathList.builder();
                        projectModule = initProjectModuleAndBuildPaths(projectDep, a, modelBuilder, depBuilder,
                                javaExtension, pathBuilder, "testFixtures", true);
                        paths = pathBuilder.build();
                    }
                }
            }

            final ResolvedDependencyBuilder artifactBuilder = depBuilder
                    .setResolvedPaths(paths == null ? PathList.of(a.getFile().toPath()) : paths)
                    .setWorkspaceModule(projectModule);
            processQuarkusDependency(artifactBuilder, modelBuilder);
            appDependencies.put(artifactBuilder.getKey(), artifactBuilder);

            if (artifactFiles != null) {
                artifactFiles.add(a.getFile());
            }
        }

        processedModules.add(new GACT(resolvedDep.getModuleGroup(), resolvedDep.getModuleName()));
        for (org.gradle.api.artifacts.ResolvedDependency child : resolvedDep.getChildren()) {
            if (!processedModules.contains(new GACT(child.getModuleGroup(), child.getModuleName()))) {
                collectDependencies(child, mode, project, appDependencies, artifactFiles, processedModules, modelBuilder);
            }
        }
    }

    private DefaultWorkspaceModule initProjectModuleAndBuildPaths(final Project project,
            ResolvedArtifact resolvedArtifact, ApplicationModelBuilder appModel, final ResolvedDependencyBuilder appDep,
            final JavaPluginExtension javaExt, PathList.Builder buildPaths, String sourceName, boolean test) {

        appDep.setWorkspaceModule().setReloadable();

        final DefaultWorkspaceModule projectModule = appModel.getOrCreateProjectModule(
                new GAV(resolvedArtifact.getModuleVersion().getId().getGroup(), resolvedArtifact.getName(),
                        resolvedArtifact.getModuleVersion().getId().getVersion()),
                project.getProjectDir(),
                project.getBuildDir());

        initProjectModule(project, projectModule, javaExt.getSourceSets().findByName(sourceName), test);

        if (test) {
            projectModule.getTestSources().forEach(src -> {
                collectDestinationDirs(src, buildPaths);
            });
            projectModule.getTestResources().forEach(src -> {
                collectDestinationDirs(src, buildPaths);
            });
        } else {
            projectModule.getMainSources().forEach(src -> {
                collectDestinationDirs(src, buildPaths);
            });
            projectModule.getMainResources().forEach(src -> {
                collectDestinationDirs(src, buildPaths);
            });
        }

        appModel.addLocalProjectArtifact(
                new GACT(resolvedArtifact.getModuleVersion().getId().getGroup(), resolvedArtifact.getName()));
        return projectModule;
    }

    private void processQuarkusDependency(ResolvedDependencyBuilder artifactBuilder, ApplicationModelBuilder modelBuilder) {
        artifactBuilder.getResolvedPaths().forEach(artifactPath -> {
            if (!Files.exists(artifactPath) || !artifactBuilder.getType().equals(GACTV.TYPE_JAR)) {
                return;
            }
            if (Files.isDirectory(artifactPath)) {
                processQuarkusDir(artifactBuilder,
                        artifactPath.resolve(BootstrapConstants.META_INF), modelBuilder);
            } else {
                try (FileSystem artifactFs = FileSystems.newFileSystem(artifactPath,
                        QuarkusModelHelper.class.getClassLoader())) {
                    processQuarkusDir(artifactBuilder,
                            artifactFs.getPath(BootstrapConstants.META_INF), modelBuilder);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to process " + artifactPath, e);
                }
            }
        });
    }

    private static void initProjectModule(Project project, DefaultWorkspaceModule module, SourceSet sourceSet, boolean test) {

        module.setBuildFiles(PathList.of(project.getBuildFile().toPath()));
        final List<Path> allJavaDirs = new ArrayList<>(2);
        sourceSet.getAllJava().getSourceDirectories().forEach(f -> {
            if (f.exists()) {
                allJavaDirs.add(f.toPath());
            }
        });

        if (!allJavaDirs.isEmpty()) {
            final TaskCollection<AbstractCompile> compileTasks = project.getTasks().withType(AbstractCompile.class);
            compileTasks.forEach(t -> {
                String compiler = t.getName();
                if (compiler.startsWith("compile")) {
                    compiler = compiler.substring("compile".length()).toLowerCase();
                }

                final File destDir = t.getDestinationDirectory().getAsFile().get();

                final List<Path> srcDirs = new ArrayList<>(1);
                for (File f : t.getSource().getFiles()) {
                    if (!f.exists()) {
                        return;
                    }
                    final Path p = f.toPath();
                    int i = 0;
                    while (i < srcDirs.size()) {
                        if (p.startsWith(srcDirs.get(i))) {
                            break;
                        }
                        ++i;
                    }
                    if (i < srcDirs.size()) {
                        continue;
                    }
                    for (Path srcDir : allJavaDirs) {
                        if (p.startsWith(srcDir)) {
                            srcDirs.add(srcDir);
                            DefaultProcessedSources sources = new DefaultProcessedSources(srcDir.toFile(), destDir,
                                    Collections.singletonMap("compiler", compiler));
                            if (test) {
                                module.addTestSources(sources);
                            } else {
                                module.addMainSources(sources);
                            }
                            break;
                        }
                    }
                }
            });
        }

        final List<Path> allResourcesDirs = new ArrayList<>(1);
        final List<Path> addedSrcDirs = new ArrayList<>(1);
        final File outputDir = sourceSet.getOutput().getResourcesDir();
        for (File resourcesDir : sourceSet.getResources().getSourceDirectories()) {
            allResourcesDirs.add(resourcesDir.toPath());
            addResources(module, resourcesDir, outputDir, test, addedSrcDirs);
        }

        if (!allResourcesDirs.isEmpty()) {
            final TaskCollection<ProcessResources> resources = project.getTasks().withType(ProcessResources.class);
            resources.forEach(t -> {
                final File destDir = t.getDestinationDir();
                for (File f : t.getSource().getFiles()) {
                    if (!f.exists()) {
                        return;
                    }
                    final Path p = f.toPath();
                    int i = 0;
                    while (i < addedSrcDirs.size()) {
                        if (p.startsWith(addedSrcDirs.get(i))) {
                            break;
                        }
                        ++i;
                    }
                    if (i < addedSrcDirs.size()) {
                        continue;
                    }
                    for (Path srcDir : allResourcesDirs) {
                        if (p.startsWith(srcDir)) {
                            addResources(module, srcDir.toFile(), destDir, test, addedSrcDirs);
                            break;
                        }
                    }
                }
            });
        }
    }

    private static void addResources(DefaultWorkspaceModule module, File srcDir, final File destDir, boolean test,
            Collection<Path> addedSrcDirs) {
        final DefaultProcessedSources resrc = new DefaultProcessedSources(srcDir, destDir);
        if (test) {
            module.addTestResources(resrc);
        } else {
            module.addMainResources(resrc);
        }
        addedSrcDirs.add(srcDir.toPath());
    }

    private void addSubstitutedProject(PathList.Builder paths, File projectFile) {
        File mainResourceDirectory = new File(projectFile, MAIN_RESOURCES_OUTPUT);
        if (mainResourceDirectory.exists()) {
            paths.add(mainResourceDirectory.toPath());
        }
        File classesOutput = new File(projectFile, CLASSES_OUTPUT);
        File[] languageDirectories = classesOutput.listFiles();
        if (languageDirectories == null) {
            throw new GradleException(
                    "The project does not contain a class output directory. " + classesOutput.getPath() + " must exist.");
        }
        for (File languageDirectory : languageDirectories) {
            if (languageDirectory.isDirectory()) {
                for (File sourceSet : languageDirectory.listFiles()) {
                    if (sourceSet.isDirectory() && sourceSet.getName().equals(SourceSet.MAIN_SOURCE_SET_NAME)) {
                        paths.add(sourceSet.toPath());
                    }
                }
            }
        }
    }

    private static boolean isDependency(ResolvedArtifact a) {
        return ArtifactCoords.TYPE_JAR.equalsIgnoreCase(a.getExtension()) || "exe".equalsIgnoreCase(a.getExtension()) ||
                a.getFile().isDirectory();
    }

    /**
     * Creates an instance of Dependency and associates it with the ResolvedArtifact's path
     */
    static ResolvedDependencyBuilder toDependency(ResolvedArtifact a, int... flags) {
        return toDependency(a, PathList.of(a.getFile().toPath()), null, flags);
    }

    static ResolvedDependencyBuilder toDependency(ResolvedArtifact a, PathCollection paths, DefaultWorkspaceModule module,
            int... flags) {
        int allFlags = 0;
        for (int f : flags) {
            allFlags |= f;
        }
        return ResolvedDependencyBuilder.newInstance()
                .setCoords(toArtifactCoords(a))
                .setResolvedPaths(paths)
                .setWorkspaceModule(module)
                .setFlags(allFlags);
    }

    private static ArtifactCoords toArtifactCoords(ResolvedArtifact a) {
        final String[] split = a.getModuleVersion().toString().split(":");
        return new GACTV(split[0], split[1], a.getClassifier(), a.getType(), split.length > 2 ? split[2] : null);
    }

    private static ArtifactKey toAppDependenciesKey(String groupId, String artifactId, String classifier) {
        // Default classifier is empty string and not null value, lets keep it that way
        classifier = classifier == null ? "" : classifier;
        return new GACT(groupId, artifactId, classifier, ArtifactCoords.TYPE_JAR);
    }
}
