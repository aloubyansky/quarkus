package io.quarkus.bootstrap.resolver.maven.workspace;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.BootstrapModelBuilderFactory;
import io.quarkus.bootstrap.resolver.maven.BootstrapModelResolver;
import io.quarkus.bootstrap.resolver.maven.options.BootstrapMavenOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelCache;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.resolution.WorkspaceModelResolver;
import org.jboss.logging.Logger;

public class WorkspaceLoader implements WorkspaceModelResolver {

    private static final Logger log = Logger.getLogger(WorkspaceLoader.class);

    public static final String POM_XML = "pom.xml";

    static final Model readModel(Path pom) throws BootstrapMavenException {
        try {
            final Model model = ModelUtils.readModel(pom);
            model.setPomFile(pom.toFile());
            return model;
        } catch (NoSuchFileException e) {
            // some projects may be missing pom.xml relying on Maven extensions (e.g. tycho-maven-plugin) to build them,
            // which we don't support in this workspace loader
            log.warn("Module(s) under " + pom.getParent() + " will be handled as thirdparty dependencies because " + pom
                    + " does not exist");
            return null;
        } catch (IOException e) {
            throw new BootstrapMavenException("Failed to read " + pom, e);
        }
    }

    static Path locateCurrentProjectPom(Path path, boolean required) throws BootstrapMavenException {
        Path p = path;
        while (p != null) {
            final Path pom = p.resolve(POM_XML);
            if (Files.exists(pom)) {
                return pom;
            }
            p = p.getParent();
        }
        if (required) {
            throw new BootstrapMavenException("Failed to locate project pom.xml for " + path);
        }
        return null;
    }

    private static class ProjectModel {

        /* @formatter:off */
        public static final int SCHEDULED = 0b001;
        /* @formatter:on */

        Model raw;
        Model effective;
        byte flags;
        LocalProject project;

        boolean isScheduled() {
            return (flags & SCHEDULED) > 0;
        }

        void schedule() {
            flags |= SCHEDULED;
        }
    }

    private final LocalWorkspace workspace;
    private final Map<Path, ProjectModel> rawModelCache = new HashMap<>();
    private final Path currentProjectPom;
    private Path workspaceRootPom;

    private ModelBuilder modelBuilder;
    private ModelResolver modelResolver;
    private ModelCache modelCache;
    private List<String> activeProfileIds;
    private List<String> inactiveProfileIds;
    private List<Profile> profiles;

    public WorkspaceLoader(BootstrapMavenContext ctx, Path currentProjectPom, Collection<Model[]> workspaceModules)
            throws BootstrapMavenException {

        for (Model[] model : workspaceModules) {
            final ProjectModel pm = new ProjectModel();
            pm.raw = model[0];
            pm.effective = model[1];
            rawModelCache.put(pm.raw.getProjectDirectory().toPath(), pm);
        }

        workspace = ctx == null ? new LocalWorkspace() : ctx.getWorkspace();
        if (ctx != null && ctx.isEffectiveModelBuilder()) {
            modelBuilder = BootstrapModelBuilderFactory.getDefaultModelBuilder();
            modelResolver = BootstrapModelResolver.newInstance(ctx, workspace);
            modelCache = new BootstrapModelCache(ctx.getRepositorySystemSession());

            profiles = ctx.getActiveSettingsProfiles();
            final BootstrapMavenOptions cliOptions = ctx.getCliOptions();
            activeProfileIds = new ArrayList<>(profiles.size() + cliOptions.getActiveProfileIds().size());
            for (Profile p : profiles) {
                activeProfileIds.add(p.getId());
            }
            activeProfileIds.addAll(cliOptions.getActiveProfileIds());
            inactiveProfileIds = cliOptions.getInactiveProfileIds();
        }
        this.currentProjectPom = isPom(currentProjectPom) ? currentProjectPom
                : locateCurrentProjectPom(currentProjectPom, true);
    }

    private boolean isPom(Path p) {
        if (Files.exists(p) && !Files.isDirectory(p)) {
            try {
                loadAndCacheRawModel(p);
                return true;
            } catch (BootstrapMavenException e) {
                // not a POM file
            }
        }
        return false;
    }

    private LocalProject project(ProjectModel pm) throws BootstrapMavenException {
        if (pm.project == null) {
            if (modelBuilder != null) {
                ModelBuildingRequest req = new DefaultModelBuildingRequest();
                req.setPomFile(pm.raw.getPomFile());
                req.setModelResolver(modelResolver);
                req.setSystemProperties(System.getProperties());
                req.setUserProperties(System.getProperties());
                req.setModelCache(modelCache);
                req.setActiveProfileIds(activeProfileIds);
                req.setInactiveProfileIds(inactiveProfileIds);
                req.setProfiles(profiles);
                req.setRawModel(pm.raw);
                req.setWorkspaceModelResolver(this);
                try {
                    pm.project = new LocalProject(modelBuilder.build(req), workspace);
                } catch (Exception e) {
                    throw new BootstrapMavenException("Failed to resolve the effective model for " + pm.raw.getPomFile(), e);
                }
            } else {
                pm.project = new LocalProject(pm.raw, pm.effective, workspace);
            }
        }
        return pm.project;
    }

    private ProjectModel schedule(Path pomFile) throws BootstrapMavenException {
        ProjectModel pm = rawModelCache.get(pomFile.getParent());
        if (pm == null) {
            pm = loadAndCacheRawModel(pomFile);
        }
        pm.schedule();
        return pm;
    }

    private ProjectModel loadAndCacheRawModel(Path pomFile) throws BootstrapMavenException {
        final ProjectModel pm = new ProjectModel();
        pm.raw = readModel(pomFile);
        rawModelCache.put(pomFile.getParent(), pm);
        return pm;
    }

    public void setWorkspaceRootPom(Path rootPom) {
        this.workspaceRootPom = rootPom;
    }

    private ProjectModel loadProject(Path projectPom, String skipModule) throws BootstrapMavenException {
        final ProjectModel pm = schedule(projectPom);
        if (pm == null) {
            return null;
        }

        final Path parentPom = getParentPom(projectPom, pm.raw);
        if (parentPom != null) {
            final ProjectModel parentModel = rawModelCache.get(parentPom.getParent());
            if (parentModel == null || !parentModel.isScheduled()) {
                loadProject(parentPom, parentPom.getParent().relativize(projectPom.getParent()).toString());
            }
        }
        loadProjectModules(project(pm), skipModule);
        return pm;
    }

    private Path getParentPom(Path projectPom, Model rawModel) {
        Path parentPom = null;
        final Path projectDir = projectPom.getParent();
        final Parent parent = rawModel.getParent();
        if (parent != null && parent.getRelativePath() != null && !parent.getRelativePath().isEmpty()) {
            parentPom = projectDir.resolve(parent.getRelativePath()).normalize();
            if (Files.isDirectory(parentPom)) {
                parentPom = parentPom.resolve(POM_XML);
            }
        } else {
            final Path parentDir = projectDir.getParent();
            if (parentDir != null) {
                parentPom = parentDir.resolve(POM_XML);
            }
        }
        return parentPom != null && Files.exists(parentPom) ? parentPom : null;
    }

    private void loadProjectModules(LocalProject project, String skipModule) throws BootstrapMavenException {
        final List<String> modules = project.getEffectiveModel() == null ? project.getRawModel().getModules()
                : project.getEffectiveModel().getModules();
        if (!modules.isEmpty()) {
            for (String module : modules) {
                if (module.equals(skipModule)) {
                    continue;
                }
                final ProjectModel child = schedule(project.getDir().resolve(module).resolve(POM_XML));
                if (child != null) {
                    loadProjectModules(project(child), null);
                }
            }
        }
    }

    public LocalProject load() throws BootstrapMavenException {
        if (workspace != null) {
            System.out.println("WorkspaceLoader.load");
            System.out.println("  " + currentProjectPom);
        }
        long time = System.currentTimeMillis();

        if (workspaceRootPom != null) {
            loadProject(workspaceRootPom, null);
        }
        ProjectModel currentProject = rawModelCache.get(currentProjectPom.getParent());
        if (currentProject == null || currentProject.project == null) {
            currentProject = loadProject(currentProjectPom, null);
        }
        if (workspace != null) {
            workspace.setCurrentProject(currentProject.project);

            for (ProjectModel pm : rawModelCache.values()) {
                if (pm.project == null) {
                    new LocalProject(pm.raw, pm.effective, workspace);
                }
            }

            System.out.println("  loaded " + workspace.getProjects().size() + " modules in "
                    + (System.currentTimeMillis() - time) + " ms");
        }
        return currentProject.project;
    }

    @Override
    public Model resolveRawModel(String groupId, String artifactId, String versionConstraint)
            throws UnresolvableModelException {
        final LocalProject project = workspace.getProject(groupId, artifactId);
        // we are comparing the raw version here because in case of a CI-friendly version (e.g. ${revision}) the versionConstraint will be an expression
        return project != null && ModelUtils.getRawVersion(project.getRawModel()).equals(versionConstraint)
                ? project.getRawModel()
                : null;
    }

    @Override
    public Model resolveEffectiveModel(String groupId, String artifactId, String versionConstraint)
            throws UnresolvableModelException {
        final LocalProject project = workspace.getProject(groupId, artifactId);
        return project != null && project.getVersion().equals(versionConstraint)
                ? project.getEffectiveModel()
                : null;
    }
}
