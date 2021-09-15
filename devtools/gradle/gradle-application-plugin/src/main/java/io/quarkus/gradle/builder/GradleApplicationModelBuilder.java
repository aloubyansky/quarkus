package io.quarkus.gradle.builder;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
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
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
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
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.model.CapabilityContract;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import io.quarkus.bootstrap.model.gradle.ApplicationModel;
import io.quarkus.bootstrap.model.gradle.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.gradle.ModelParameter;
import io.quarkus.bootstrap.model.gradle.impl.ModelParameterImpl;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.util.QuarkusModelHelper;
import io.quarkus.bootstrap.workspace.DefaultProcessedSources;
import io.quarkus.bootstrap.workspace.DefaultProjectModule;
import io.quarkus.gradle.QuarkusPlugin;
import io.quarkus.gradle.dependency.ApplicationDeploymentClasspathBuilder;
import io.quarkus.gradle.dependency.DependencyUtils;
import io.quarkus.gradle.tasks.QuarkusGradleUtils;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DefaultArtifact;
import io.quarkus.maven.dependency.DefaultDependency;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.GACT;
import io.quarkus.maven.dependency.GACTV;
import io.quarkus.maven.dependency.GAV;
import io.quarkus.maven.dependency.ProjectArtifact;
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

    private PrintStream out;

    private void log(String s) {
        if (out != null) {
            out.println(s);
        }
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        //try (PrintStream o = out = new PrintStream(
        //        new FileOutputStream(Paths.get("/home/aloubyansky/playground/gradle-model-building.log").toFile()))) {
        final ModelParameterImpl modelParameter = new ModelParameterImpl();
        modelParameter.setMode(LaunchMode.DEVELOPMENT.toString());
        return buildAll(modelName, modelParameter, project);
        //} catch (IOException e) {
        //            throw new IllegalStateException(e);
        //      }
    }

    @Override
    public Object buildAll(String modelName, ModelParameter parameter, Project project) {
        LaunchMode mode = LaunchMode.valueOf(parameter.getMode());

        //try (PrintStream o = out = new PrintStream(
        //new FileOutputStream(Paths.get("/home/aloubyansky/playground/gradle-model-building.log").toFile()))) {

        log("GradleApplicationModelBuilder.buildAll " + modelName);

        final List<org.gradle.api.artifacts.Dependency> deploymentDeps = DependencyUtils.getEnforcedPlatforms(project);
        final PlatformImports platformImports = resolvePlatformImports(project, deploymentDeps);

        final ArtifactCoords projectGactv = new GACTV(project.getGroup().toString(), project.getName(),
                project.getVersion().toString());

        JavaPluginExtension javaExtension = project.getExtensions().findByType(JavaPluginExtension.class);
        if (javaExtension == null) {
            throw new GradleException("Failed to locate Java plugin extension in " + project.getPath());
        }
        final DefaultProjectModule mainModule = new DefaultProjectModule(
                new GAV(projectGactv.getGroupId(), projectGactv.getArtifactId(), projectGactv.getVersion()),
                project.getProjectDir(), project.getBuildDir());
        initProjectModule(project, mainModule, javaExtension.getSourceSets().findByName(SourceSet.MAIN_SOURCE_SET_NAME),
                false);
        if (mode.equals(LaunchMode.TEST)) {
            initProjectModule(project, mainModule,
                    javaExtension.getSourceSets().findByName(SourceSet.TEST_SOURCE_SET_NAME), true);
        }
        log("Application module: " + mainModule);

        final PathList.Builder paths = PathList.builder();
        mainModule.getMainSources().forEach(src -> {
            if (src.getDestinationDir().exists()) {
                paths.add(src.getDestinationDir().toPath());
            }
        });
        mainModule.getMainResources().forEach(src -> {
            if (src.getDestinationDir().exists()) {
                paths.add(src.getDestinationDir().toPath());
            }
        });

        final ApplicationModelBuilder modelBuilder = new ApplicationModelBuilder()
                .setAppArtifact(new ProjectArtifact(mainModule, projectGactv, paths.build()))
                .setPlatformImports(platformImports);

        final Map<ArtifactKey, DefaultDependency> appDependencies = new LinkedHashMap<>();
        Configuration classpathConfig = classpathConfig(project, mode);
        collectDependencies(classpathConfig.getResolvedConfiguration(), mode, project, appDependencies, modelBuilder);

        Configuration deploymentConfig = deploymentClasspathConfig(project, mode, deploymentDeps);
        collectExtensionDependencies(deploymentConfig, appDependencies, modelBuilder);

        return modelBuilder.build();
        //} catch (IOException e) {
        //  throw new IllegalStateException(e);
        //}
    }

    private static void processQuarkusDir(DefaultDependency d, Path quarkusDir, ApplicationModelBuilder modelBuilder) {
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
        d.setFlag(DependencyFlags.RUNTIME_EXTENSION_ARTIFACT);
        final String extensionCoords = d.getArtifact().getCoords().toString();
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
            Map<ArtifactKey, DefaultDependency> appDependencies, ApplicationModelBuilder modelBuilder) {
        final ResolvedConfiguration rc = deploymentConfiguration.getResolvedConfiguration();
        for (ResolvedArtifact a : rc.getResolvedArtifacts()) {
            if (isDependency(a)) {
                final DefaultDependency dep = appDependencies.computeIfAbsent(
                        toAppDependenciesKey(a.getModuleVersion().getId().getGroup(), a.getName(), a.getClassifier()),
                        k -> {
                            final DefaultDependency d = toDependency(a);
                            modelBuilder.addDependency(d);
                            return d;
                        });
                dep.setFlag(DependencyFlags.DEPLOYMENT_CP);
            }
        }
    }

    private void collectDependencies(ResolvedConfiguration configuration,
            LaunchMode mode, Project project, Map<ArtifactKey, DefaultDependency> appDependencies,
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
                final DefaultDependency dep = new DefaultDependency(
                        new DefaultArtifact(new GACTV(group, name, null, type, version), f.toPath()), "compile",
                        DependencyFlags.DIRECT | DependencyFlags.RUNTIME_CP);
                processQuarkusDependency(dep, modelBuilder);
                modelBuilder.addDependency(dep);
                appDependencies.put(dep.getArtifact().getKey(), dep);
            }
        }
    }

    private void collectDependencies(ResolvedDependency resolvedDep, LaunchMode mode, Project project,
            Map<ArtifactKey, DefaultDependency> appDependencies, Set<File> artifactFiles,
            Set<AppArtifactKey> processedModules, ApplicationModelBuilder modelBuilder) {

        for (ResolvedArtifact a : resolvedDep.getModuleArtifacts()) {
            final ArtifactKey artifactKey = toAppDependenciesKey(a.getModuleVersion().getId().getGroup(), a.getName(),
                    a.getClassifier());
            if (!isDependency(a) || appDependencies.containsKey(artifactKey)) {
                continue;
            }
            int flags = DependencyFlags.RUNTIME_CP;
            if (processedModules.isEmpty()) {
                flags |= DependencyFlags.DIRECT;
            }
            DefaultProjectModule projectModule = null;
            final PathList.Builder paths = PathList.builder();
            if ((LaunchMode.DEVELOPMENT.equals(mode) || LaunchMode.TEST.equals(mode)) &&
                    a.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier) {
                if ("test-fixtures".equals(a.getClassifier()) || "test".equals(a.getClassifier())) {
                    //TODO: test-fixtures are broken under the new ClassLoading model
                    paths.add(a.getFile().toPath());
                } else {
                    final IncludedBuild includedBuild = DependencyUtils.includedBuild(project.getRootProject(), a.getName());
                    if (includedBuild != null) {
                        addSubstitutedProject(paths, includedBuild.getProjectDir());
                    } else {
                        final Project projectDep = project.getRootProject()
                                .findProject(
                                        ((ProjectComponentIdentifier) a.getId().getComponentIdentifier()).getProjectPath());
                        if (projectDep != null) {
                            flags |= DependencyFlags.PROJECT_MODULE | DependencyFlags.RELOADABLE;
                            projectModule = addLocalBuildPaths(paths, a, projectDep, modelBuilder);
                        } else {
                            paths.add(a.getFile().toPath());
                        }
                    }
                }
            } else {
                paths.add(a.getFile().toPath());
            }

            final DefaultDependency dep = toDependency(a, paths.build(), projectModule, flags);
            processQuarkusDependency(dep, modelBuilder);
            modelBuilder.addDependency(dep);
            appDependencies.put(dep.getArtifact().getKey(), dep);

            if (artifactFiles != null) {
                artifactFiles.add(a.getFile());
            }

            if (dep.isProjectModule()) {
                log("Local dep " + dep.getArtifact() + " " + dep.getArtifact().getModule());
            }
        }

        processedModules.add(new AppArtifactKey(resolvedDep.getModuleGroup(), resolvedDep.getModuleName()));
        for (ResolvedDependency child : resolvedDep.getChildren()) {
            if (!processedModules.contains(new AppArtifactKey(child.getModuleGroup(), child.getModuleName()))) {
                collectDependencies(child, mode, project, appDependencies, artifactFiles, processedModules, modelBuilder);
            }
        }
    }

    private void processQuarkusDependency(final DefaultDependency dep, ApplicationModelBuilder modelBuilder) {
        dep.getArtifact().getResolvedPaths().forEach(artifactPath -> {
            if (!Files.exists(artifactPath) || !dep.getArtifact().getCoords().getType().equals("jar")) {
                return;
            }
            if (Files.isDirectory(artifactPath)) {
                processQuarkusDir(dep,
                        artifactPath.resolve(BootstrapConstants.META_INF), modelBuilder);
            } else {
                try (FileSystem artifactFs = FileSystems.newFileSystem(artifactPath,
                        QuarkusModelHelper.class.getClassLoader())) {
                    processQuarkusDir(dep,
                            artifactFs.getPath(BootstrapConstants.META_INF), modelBuilder);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to process " + artifactPath, e);
                }
            }
        });
    }

    private DefaultProjectModule addLocalBuildPaths(PathList.Builder paths, ResolvedArtifact a, Project project,
            ApplicationModelBuilder modelBuilder) {
        JavaPluginExtension javaExt = project.getExtensions().findByType(JavaPluginExtension.class);
        if (javaExt == null) {
            paths.add(a.getFile().toPath());
            return null;
        }
        final SourceSet mainSourceSet = javaExt.getSourceSets().findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        if (mainSourceSet == null) {
            paths.add(a.getFile().toPath());
            return null;
        }
        final String classes = QuarkusGradleUtils.getClassesDir(mainSourceSet, project.getBuildDir(), false);
        if (classes == null) {
            paths.add(a.getFile().toPath());
        } else {
            final File classesDir = new File(classes);
            if (classesDir.exists()) {
                paths.add(classesDir.toPath());
            } else {
                paths.add(a.getFile().toPath());
            }
        }
        for (File resourcesDir : mainSourceSet.getResources().getSourceDirectories()) {
            if (resourcesDir.exists()) {
                paths.add(resourcesDir.toPath());
            }
        }
        final Task resourcesTask = project.getTasks().findByName(JavaPlugin.PROCESS_RESOURCES_TASK_NAME);
        for (File outputDir : resourcesTask.getOutputs().getFiles()) {
            if (outputDir.exists()) {
                paths.add(outputDir.toPath());
            }
        }

        final DefaultProjectModule module = modelBuilder.getOrCreateProjectModule(
                new GAV(a.getModuleVersion().getId().getGroup(), a.getName(), a.getModuleVersion().getId().getVersion()),
                project.getProjectDir(),
                project.getBuildDir());

        initProjectModule(project, module, mainSourceSet, false);
        return module;
    }

    private void initProjectModule(Project project, DefaultProjectModule module, SourceSet sourceSet, boolean test) {

        module.setBuildFiles(PathList.of(project.getBuildFile().toPath()));

        /* @formatter:off */
        log("Initializing project module " + project.getName());
        //System.log("  project dir: " + project.getProjectDir());
        //System.log("  build dir: " + project.getBuildDir());

        //log("ALL SOURCES:");
        //mainSourceSet.getAllSource().getSourceDirectories().forEach(f -> {
        //    log(" - " + f + " " + f.exists());
        //});
        log("ALL JAVA SOURCES:");
        final List<Path> allJavaDirs = new ArrayList<>(2);
        sourceSet.getAllJava().getSourceDirectories().forEach(f -> {
            if (f.exists()) {
                log(" - " + f + " " + f.exists());
            	allJavaDirs.add(f.toPath());
            }
        });

        if(!allJavaDirs.isEmpty()) {
        final TaskCollection<AbstractCompile> compileTasks = project.getTasks().withType(AbstractCompile.class);
        log("COMPILE TASKS: " + compileTasks.size());
        compileTasks.forEach(t -> {
            log("  " + t.getName() + " " + t.getDidWork());
            //if (!t.getDidWork()) {
            //    return;
            //}
            //System.log("    inputs:");
            //t.getInputs().getSourceFiles().forEach(f -> System.log("    - " + f + " " + f.exists()));
            //System.log("    outputs:");
            //t.getOutputs().getFiles().forEach(f -> System.log("    - " + f + " " + f.exists()));

            String compiler = t.getName();
            if(compiler.startsWith("compile")) {
            	compiler = compiler.substring("compile".length()).toLowerCase();
            }

            final File destDir = t.getDestinationDirectory().getAsFile().get();
			//log("    destination: " + destDir);

            //log("    source");
            final List<Path> srcDirs = new ArrayList<>(1);
            for(File f : t.getSource().getFiles()) {
                log("    - " + f + " " + f.exists());
                if(!f.exists()) {
                	return;
                }
                final Path p = f.toPath();
                int i = 0;
                while(i < srcDirs.size()) {
                	if(p.startsWith(srcDirs.get(i))) {
                		break;
                	}
                	++i;
                }
                if(i < srcDirs.size()) {
                	continue;
                }
                for(Path srcDir : allJavaDirs) {
                	if(p.startsWith(srcDir)) {
                		log("      adding src dir: " + srcDir);
                		srcDirs.add(srcDir);
                		DefaultProcessedSources sources = new DefaultProcessedSources(srcDir.toFile(), destDir, Collections.singletonMap("compiler", compiler));
                		if(test) {
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

        log("RESOURCES:");
        final List<Path> allResourcesDirs = new ArrayList<>(1);
        for (File resourcesDir : sourceSet.getResources().getSourceDirectories()) {
            if (resourcesDir.exists()) {
            	allResourcesDirs.add(resourcesDir.toPath());
                log(" - " + resourcesDir);
            }
        }

        if(!allResourcesDirs.isEmpty()) {
        final TaskCollection<ProcessResources> resources = project.getTasks().withType(ProcessResources.class);
        log("RESOURCE TASKS: " + resources.size());
        resources.forEach(t -> {
            log("  " + t.getName() + " " + t.getDidWork());
            //if (!t.getDidWork()) {
              //  return;
            //}
            //System.log("    inputs:");
            //t.getInputs().getSourceFiles().forEach(f -> System.log("    - " + f + " " + f.exists()));
            //System.log("    outputs:");
            //t.getOutputs().getFiles().forEach(f -> System.log("    - " + f + " " + f.exists()));
            //System.log("    source");
            //t.getSource().forEach(f -> System.log("    - " + f + " " + f.exists()));

            final File destDir = t.getDestinationDir();
            //log("    destination: " + destDir);

            //log("    source");
            final List<Path> srcDirs = new ArrayList<>(1);
            for(File f : t.getSource().getFiles()) {
                if(!f.exists()) {
                	return;
                }
                //log("    - " + f);
                final Path p = f.toPath();
                int i = 0;
                while(i < srcDirs.size()) {
                	if(p.startsWith(srcDirs.get(i))) {
                		break;
                	}
                	++i;
                }
                if(i < srcDirs.size()) {
                	continue;
                }
                for(Path srcDir : allResourcesDirs) {
                	if(p.startsWith(srcDir)) {
                		log("      adding resources dir: " + srcDir);
                		srcDirs.add(srcDir);
                		final DefaultProcessedSources resrc = new DefaultProcessedSources(srcDir.toFile(), destDir);
                		if(test) {
    						module.addTestResources(resrc);
                		} else {
						module.addMainResources(resrc);
                		}
                		break;
                	}
                }
            }

        });
        }
/* @formatter:on */
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
        return BootstrapConstants.JAR.equalsIgnoreCase(a.getExtension()) || "exe".equalsIgnoreCase(a.getExtension()) ||
                a.getFile().isDirectory();
    }

    /**
     * Creates an instance of Dependency and associates it with the ResolvedArtifact's path
     */
    static DefaultDependency toDependency(ResolvedArtifact a, int... flags) {
        return toDependency(a, PathList.of(a.getFile().toPath()), null, flags);
    }

    static DefaultDependency toDependency(ResolvedArtifact a, PathCollection paths, DefaultProjectModule module, int... flags) {
        return new DefaultDependency(module == null ? new DefaultArtifact(toArtifactCoords(a), paths)
                : new ProjectArtifact(module, toArtifactCoords(a), paths), "compile", flags);
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
