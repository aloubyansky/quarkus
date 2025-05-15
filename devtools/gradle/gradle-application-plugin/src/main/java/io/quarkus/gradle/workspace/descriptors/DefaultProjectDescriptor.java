package io.quarkus.gradle.workspace.descriptors;

import java.io.File;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;

public class DefaultProjectDescriptor implements Serializable, ProjectDescriptor {

    private static final long serialVersionUID = 1L;

    private final File projectDir;
    private final File buildDir;
    private final File buildFile;

    private final Map<String, QuarkusTaskDescriptor> tasks;
    private final Map<String, Set<String>> sourceSetTasks;
    private final Map<String, Set<String>> sourceSetTasksRaw;
    private WorkspaceModule.Mutable module;

    public DefaultProjectDescriptor(File projectDir, File buildDir, File buildFile, Map<String, QuarkusTaskDescriptor> tasks,
            Map<String, Set<String>> sourceSetTasks, Map<String, Set<String>> sourceSetTasksRaw,
            WorkspaceModule.Mutable module) {
        this.projectDir = projectDir;
        this.buildDir = buildDir;
        this.buildFile = buildFile;
        this.tasks = tasks;
        this.sourceSetTasks = sourceSetTasks;
        this.sourceSetTasksRaw = sourceSetTasksRaw;
        this.module = module;
    }

    @Override
    public File getProjectDir() {
        return projectDir;
    }

    @Override
    public File getBuildDir() {
        return buildDir;
    }

    @Override
    public File getBuildFile() {
        return buildFile;
    }

    public Map<String, Set<String>> getSourceSetTasks() {
        return sourceSetTasks;
    }

    public Map<String, Set<String>> getSourceSetTasksRaw() {
        return sourceSetTasksRaw;
    }

    public Map<String, QuarkusTaskDescriptor> getTasks() {
        return tasks;
    }

    @Override
    public Set<String> getTasksForSourceSet(String sourceSetName) {
        return sourceSetTasks.getOrDefault(sourceSetName, Collections.emptySet());
    }

    @Override
    public String getTaskSource(String task) {
        return tasks.get(task).getSourceDir().getAbsolutePath();
    }

    @Override
    public String getTaskDestinationDir(String task) {
        return tasks.get(task).getDestinationDir().getAbsolutePath();
    }

    @Override
    public TaskType getTaskType(String task) {
        return tasks.get(task).getTaskType();
    }

    @Override
    public WorkspaceModule.Mutable getWorkspaceModule() {
        return module;
    }

    public void setWorkspaceModule(WorkspaceModule.Mutable module) {
        this.module = module;
    }

    @Override
    public WorkspaceModule.Mutable getWorkspaceModuleOrNull(WorkspaceModuleId moduleId) {
        return module.getId().equals(moduleId) ? module : null;
    }

    @Override
    public String toString() {
        return "DefaultProjectDescriptor{" +
                "\nprojectDir=" + projectDir +
                ",\nbuildDir=" + buildDir +
                ",\nbuildFile=" + buildFile +
                ",\ntasks=" + tasks +
                ",\nsourceSetTasks=" + sourceSetTasks +
                ",\nsourceSetTasksRaw=" + sourceSetTasksRaw +
                "\n}";
    }
}
