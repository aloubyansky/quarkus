package io.quarkus.gradle.workspace.descriptors;

import static io.quarkus.gradle.workspace.descriptors.ProjectDescriptor.TaskType.COMPILE;
import static io.quarkus.gradle.workspace.descriptors.ProjectDescriptor.TaskType.RESOURCES;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile;

import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultArtifactSources;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.maven.dependency.ArtifactCoords;

public class ProjectDescriptorBuilder {

    private final File projectDir;
    private final File buildDir;
    private final File buildFile;
    private final Map<String, QuarkusTaskDescriptor> tasks;
    private final Map<String, Set<String>> sourceSetTasks;
    private final Map<String, Set<String>> sourceSetTasksRaw;

    private final WorkspaceModuleId moduleId;
    private final Map<String, ClassifiedSources> classifiedSources = new HashMap<>();

    private ProjectDescriptorBuilder(Project project) {
        this.tasks = new LinkedHashMap<>();
        this.sourceSetTasks = new LinkedHashMap<>();
        this.sourceSetTasksRaw = new LinkedHashMap<>();
        this.moduleId = WorkspaceModuleId.of(String.valueOf(project.getGroup()), project.getName(),
                String.valueOf(project.getVersion()));
        this.buildFile = project.getBuildFile();
        this.projectDir = project.getLayout().getProjectDirectory().getAsFile();
        this.buildDir = project.getLayout().getBuildDirectory().get().getAsFile();
    }

    public static Provider<DefaultProjectDescriptor> buildForApp(Project project) {
        final ProjectDescriptorBuilder builder = new ProjectDescriptorBuilder(project);
        project.afterEvaluate(evaluated -> {
            evaluated.getTasks().withType(AbstractCompile.class).configureEach(builder::readConfigurationFor);
            builder.withKotlinJvmCompileType(evaluated);
            project.getTasks().withType(ProcessResources.class).configureEach(builder::readConfigurationFor);
        });

        return project.getProviders().provider(() -> {

            var moduleBuilder = WorkspaceModule.builder()
                    .setModuleId(builder.moduleId)
                    .setBuildFile(builder.buildFile.toPath())
                    .setBuildDir(builder.buildDir.toPath())
                    .setModuleDir(builder.projectDir.toPath());

            for (var classifiedSources : builder.classifiedSources.values()) {
                moduleBuilder.addArtifactSources(classifiedSources.toArtifactSources());
            }

            return new DefaultProjectDescriptor(
                    builder.projectDir,
                    builder.buildDir,
                    builder.buildFile,
                    builder.tasks,
                    builder.sourceSetTasks,
                    builder.sourceSetTasksRaw,
                    moduleBuilder);
        });

    }

    private void readConfigurationFor(AbstractCompile task) {
        sourceSetTasksRaw.computeIfAbsent(task.getName(), s -> new HashSet<>())
                .add(task.getDestinationDirectory().getAsFile().get().getAbsolutePath());

        if (task.getEnabled() && !task.getSource().isEmpty()) {
            File destDir = task.getDestinationDirectory().getAsFile().get();
            task.getSource().visit(fileVisitDetails -> {
                if (fileVisitDetails.getRelativePath().getParent().toString().isEmpty()) {
                    File srcDir = fileVisitDetails.getFile().getParentFile();
                    tasks.put(task.getName(), new QuarkusTaskDescriptor(task.getName(), COMPILE, srcDir, destDir));
                    SourceSetContainer sourceSets = task.getProject().getExtensions().getByType(SourceSetContainer.class);
                    sourceSets.stream().filter(sourceSet -> sourceSet.getOutput().getClassesDirs().contains(destDir))
                            .forEach(sourceSet -> {
                                sourceSetTasks.computeIfAbsent(sourceSet.getName(), s -> new HashSet<>()).add(task.getName());
                                classifiedSources.computeIfAbsent(getClassifier(sourceSet), ClassifiedSources::new)
                                        .addSources(srcDir, destDir);
                            });
                    fileVisitDetails.stopVisiting();
                }
            });
        }
    }

    private void readConfigurationFor(ProcessResources task) {
        if (task.getEnabled() && !task.getSource().isEmpty()) {
            File destDir = task.getDestinationDir();
            task.getSource().getAsFileTree().visit(fileVisitDetails -> {
                if (fileVisitDetails.getRelativePath().getParent().toString().isEmpty()) {
                    File srcDir = fileVisitDetails.getFile().getParentFile();
                    tasks.put(task.getName(), new QuarkusTaskDescriptor(task.getName(), RESOURCES, srcDir, destDir));
                    SourceSetContainer sourceSets = task.getProject().getExtensions().getByType(SourceSetContainer.class);
                    sourceSets.stream().filter(sourceSet -> destDir.equals(sourceSet.getOutput().getResourcesDir()))
                            .forEach(sourceSet -> {
                                sourceSetTasks.computeIfAbsent(sourceSet.getName(), s -> new HashSet<>()).add(task.getName());
                                classifiedSources.computeIfAbsent(getClassifier(sourceSet), ClassifiedSources::new)
                                        .addResources(srcDir, destDir);
                            });
                    fileVisitDetails.stopVisiting();
                }
            });
        }
    }

    private void withKotlinJvmCompileType(Project project) {
        try {
            Class.forName("org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile");
            project.getTasks().withType(KotlinJvmCompile.class).configureEach(this::readConfigurationFor);
        } catch (ClassNotFoundException e) {
            // Ignore
        }
    }

    private void readConfigurationFor(KotlinJvmCompile task) {
        if (task.getEnabled() && !task.getSources().isEmpty()) {
            File destDir = task.getDestinationDirectory().getAsFile().get();
            AtomicReference<File> srcDir = new AtomicReference<>();
            task.getSources().getAsFileTree().visit(fileVisitDetails -> {
                if (fileVisitDetails.getRelativePath().getParent().toString().isEmpty()) {
                    srcDir.set(fileVisitDetails.getFile().getParentFile());
                    fileVisitDetails.stopVisiting();
                }
            });
            tasks.put(task.getName(), new QuarkusTaskDescriptor(task.getName(), COMPILE, srcDir.get(), destDir));
            SourceSetContainer sourceSets = task.getProject().getExtensions().getByType(SourceSetContainer.class);
            sourceSets.stream().filter(sourceSet -> sourceSet.getOutput().getClassesDirs().contains(destDir))
                    .forEach(sourceSet -> {
                        sourceSetTasks.computeIfAbsent(sourceSet.getName(), s -> new HashSet<>()).add(task.getName());
                        classifiedSources.computeIfAbsent(getClassifier(sourceSet), ClassifiedSources::new)
                                .addSources(srcDir.get(), destDir);
                    });
        }
    }

    private static String getClassifier(SourceSet sourceSet) {
        return switch (sourceSet.getName()) {
            case SourceSet.MAIN_SOURCE_SET_NAME -> ArtifactCoords.DEFAULT_CLASSIFIER;
            case SourceSet.TEST_SOURCE_SET_NAME -> "tests";
            case "testFixtures" -> "test-fixtures";
            default -> sourceSet.getName();
        };
    }

    private static class ClassifiedSources {
        private final String classifier;
        private final List<SourceOutputDir> sources = new ArrayList<>(1);
        private final List<SourceOutputDir> resources = new ArrayList<>(1);

        public ClassifiedSources(String classifier) {
            this.classifier = classifier;
        }

        private void addSources(File src, File output) {
            addIfNotPresent(sources, src, output);
        }

        private void addResources(File src, File output) {
            addIfNotPresent(resources, src, output);
        }

        private static Collection<SourceDir> toSourceDir(List<SourceOutputDir> dirs) {
            if (dirs.isEmpty()) {
                return List.of();
            }
            if (dirs.size() == 1) {
                return List.of(dirs.get(0).toSourceDir());
            }
            final List<SourceDir> result = new ArrayList<>(dirs.size());
            for (var dir : dirs) {
                result.add(dir.toSourceDir());
            }
            return result;
        }

        private ArtifactSources toArtifactSources() {
            return new DefaultArtifactSources(classifier, toSourceDir(sources), toSourceDir(resources));
        }

        private static void addIfNotPresent(List<SourceOutputDir> resources, File src, File output) {
            if (resources.isEmpty()) {
                resources.add(new SourceOutputDir(src, output));
            } else {
                for (var added : resources) {
                    if (added.src().equals(src)) {
                        return;
                    }
                }
                resources.add(new SourceOutputDir(src, output));
            }
        }
    }

    private record SourceOutputDir(File src, File output) {
        private SourceDir toSourceDir() {
            return SourceDir.of(src.toPath(), output.toPath());
        }
    };
}
