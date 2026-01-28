package io.quarkus.bootstrap.resolver.maven.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;

public class WorkspaceModulePom {

    private static int STATUS_NEW = 0;
    private static int STATUS_SCHEDULED = 1;
    private static int STATUS_PROCESSED = 2;

    final Path pom;
    Model model;
    Model effectiveModel;
    WorkspaceModulePom parent;
    private int state = STATUS_NEW;
    final ConcurrentLinkedDeque<WorkspaceModulePom> thenSchedule = new ConcurrentLinkedDeque<>();

    WorkspaceModulePom(Path pom) {
        this(pom, null, null);
    }

    public WorkspaceModulePom(Path pom, Model model, Model effectiveModel) {
        this.pom = pom.normalize().toAbsolutePath();
        this.model = model;
        this.effectiveModel = effectiveModel;
    }

    public Path getPom() {
        return pom;
    }

    Path getModuleDir() {
        var moduleDir = pom.getParent();
        return moduleDir == null ? WorkspaceLoader.getFsRootDir() : moduleDir;
    }

    Model getModel() {
        return model == null ? model = WorkspaceLoader.readModel(pom) : model;
    }

    boolean isParentConfigured() {
        return getModel().getParent() != null;
    }

    public Path getParentPom() {
        if (model == null) {
            return null;
        }
        Path parentPom = null;
        final Parent parent = model.getParent();
        if (parent != null && parent.getRelativePath() != null && !parent.getRelativePath().isEmpty()) {
            parentPom = pom.getParent().resolve(parent.getRelativePath()).normalize();
            if (Files.isDirectory(parentPom)) {
                parentPom = parentPom.resolve(WorkspaceLoader.POM_XML);
            }
        } else {
            final Path parentDir = pom.getParent().getParent();
            if (parentDir != null) {
                parentPom = parentDir.resolve(WorkspaceLoader.POM_XML);
            }
        }
        return parentPom != null && Files.exists(parentPom) ? parentPom.normalize().toAbsolutePath() : null;
    }

    boolean isNew() {
        return state == STATUS_NEW;
    }

    void setScheduled() {
        state = STATUS_SCHEDULED;
    }

    void process(Consumer<WorkspaceModulePom> consumer) {
        if (state == STATUS_PROCESSED) {
            return;
        }
        state = STATUS_PROCESSED;
        if (parent != null) {
            parent.process(consumer);
        }
        if (model != null && model != WorkspaceLoader.MISSING_MODEL) {
            consumer.accept(this);
        }
    }

    String getResolvedGroupId() {
        if (effectiveModel != null) {
            return effectiveModel.getGroupId();
        }
        final Model model = getModel();
        if (model != null) {
            String groupId = model.getGroupId();
            if (groupId != null) {
                return groupId;
            }
            Parent parent = model.getParent();
            if (parent != null) {
                groupId = parent.getGroupId();
                if (groupId != null) {
                    return groupId;
                }
            }
        }
        if (parent != null) {
            return parent.getResolvedGroupId();
        }
        throw new RuntimeException("Failed to determine the groupId of module " + pom);
    }

    String getResolvedVersion() {
        if (effectiveModel != null) {
            return effectiveModel.getVersion();
        }
        final Model model = getModel();
        if (model != null) {
            String version = ModelUtils.getRawVersionOrNull(model);
            if (version != null && ModelUtils.isUnresolvedVersion(version)) {
                version = ModelUtils.resolveVersion(version, model);
            }
            if (version != null) {
                return version;
            }
        }
        if (parent != null) {
            return parent.getResolvedVersion();
        }
        throw new RuntimeException("Failed to determine the version of module " + pom);
    }

    @Override
    public String toString() {
        return String.valueOf(pom);
    }

    void thenSchedule(WorkspaceModulePom module) {
        thenSchedule.add(module);
    }

    Deque<WorkspaceModulePom> getThenSchedule() {
        return thenSchedule;
    }
}
