package io.quarkus.devtools.project.state;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ConfiguredProject {

    private final Path projectDir;
    private final List<ConfiguredModule> modules = new ArrayList<>();

    public ConfiguredProject(Path projectDir) {
        this.projectDir = projectDir;
    }

    public Path getProjectDir() {
        return projectDir;
    }

    public void addModule(ConfiguredModule module) {
        modules.add(module);
    }

    public Collection<ConfiguredModule> getModules() {
        return modules;
    }
}
