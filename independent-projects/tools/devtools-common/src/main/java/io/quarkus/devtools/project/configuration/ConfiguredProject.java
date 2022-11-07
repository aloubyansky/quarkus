package io.quarkus.devtools.project.configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

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
        Objects.requireNonNull(module);
        modules.add(module);
    }

    public Collection<ConfiguredModule> getModules() {
        return modules;
    }
}
