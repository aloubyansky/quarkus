package io.quarkus.maven;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppArtifactCoords;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;

/**
 * NOTE: this mojo is experimental
 */
@Mojo(name = "info", requiresProject = true)
public class InfoMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}")
    private MavenSession session;

    @Parameter(defaultValue = "${project}")
    protected MavenProject project;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repos;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Component
    private RepositorySystem repoSystem;

    @Component
    RemoteRepositoryManager remoteRepoManager;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().warn(
                "This goal is experimental. Its name, parameters, output and implementation will be evolving without the promise of keeping backward compatibility");

        if (project.getFile() == null) {
            throw new MojoExecutionException("This goal requires a project");
        }

        final AppModel appModel = resolveAppModel();

        final List<AppArtifactCoords> bomImports = getBomImports(appModel);

        final Set<AppArtifactKey> allPlatformExtensions = getPlatformExtensionKeys(bomImports);

        final List<AppArtifactCoords> platformExtensions = new ArrayList<>();
        final List<AppArtifactCoords> nonPlatformExtensions = new ArrayList<>();
        for (AppDependency d : appModel.getFullDeploymentDeps()) {
            if (d.isDirect() && d.isRuntimeExtensionArtifact()) {
                if (allPlatformExtensions.contains(d.getArtifact().getKey())) {
                    platformExtensions.add(d.getArtifact());
                } else {
                    nonPlatformExtensions.add(d.getArtifact());
                }
            }
        }

        ArtifactCoords prevPluginCoords = null;
        for (Plugin p : project.getBuildPlugins()) {
            if (p.getArtifactId().equals("quarkus-maven-plugin")) {
                prevPluginCoords = new ArtifactCoords(p.getGroupId(), p.getArtifactId(), p.getVersion());
                break;
            }
        }

        final StringWriter buf = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(buf)) {
            writer.append("Quarkus project information:");
            writer.newLine();
            writer.newLine();

            if (!bomImports.isEmpty()) {
                writer.append("Platform BOM imports:");
                writer.newLine();
                for (AppArtifactCoords coords : bomImports) {
                    writer.append(" - ").append(coords.getGroupId()).append(":").append(coords.getArtifactId())
                            .append(":").append(coords.getVersion());
                    writer.newLine();
                }
                writer.newLine();
            }

            if (!platformExtensions.isEmpty()) {
                writer.append("Platform extensions:");
                writer.newLine();
                for (AppArtifactCoords coords : platformExtensions) {
                    writer.append(" - ").append(coords.getGroupId()).append(":")
                            .append(coords.getArtifactId()).append(":").append(coords.getVersion());
                    writer.newLine();
                }
                writer.newLine();
            }

            if (!nonPlatformExtensions.isEmpty()) {
                writer.append("Non-platform extensions:");
                writer.newLine();
                for (AppArtifactCoords coords : nonPlatformExtensions) {
                    writer.append(" - ").append(coords.getGroupId()).append(":")
                            .append(coords.getArtifactId()).append(":").append(coords.getVersion());
                    writer.newLine();
                }
                writer.newLine();
            }

            if (prevPluginCoords != null) {
                writer.append("Maven plugin: ").append(prevPluginCoords.getGroupId()).append(":")
                        .append(prevPluginCoords.getArtifactId()).append(":").append(prevPluginCoords.getVersion());
                writer.newLine();
            }

            writer.append("Maven version: ").append(session.getSystemProperties().getProperty("maven.version"));
            writer.newLine();
            writer.append("Java version: ").append(System.getProperty("java.vm.version")).append(" ")
                    .append(System.getProperty("java.vm.name")).append(" ")
                    .append(System.getProperty("java.vm.vendor"));
            writer.newLine();
            writer.append("Operating system: ").append(System.getProperty("os.name")).append(" ")
                    .append(System.getProperty("os.version")).append(" ").append(System.getProperty("os.arch"));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to compose the update report", e);
        }

        getLog().info(buf.toString());
    }

    private Set<AppArtifactKey> getPlatformExtensionKeys(final List<AppArtifactCoords> bomImports)
            throws MojoExecutionException {
        final Set<AppArtifactKey> allPlatformExtensions = new HashSet<>();
        if (!bomImports.isEmpty()) {
            for (AppArtifactCoords bom : bomImports) {
                final List<org.eclipse.aether.graph.Dependency> deps;
                try {
                    deps = repoSystem.readArtifactDescriptor(repoSession,
                            new ArtifactDescriptorRequest()
                                    .setArtifact(new DefaultArtifact(bom.getGroupId(), bom.getArtifactId(),
                                            bom.getClassifier(), bom.getType(), bom.getVersion()))
                                    .setRepositories(repos))
                            .getManagedDependencies();
                } catch (ArtifactDescriptorException e) {
                    throw new MojoExecutionException("Failed to resolve artifact descriptor for " + bom, e);
                }
                deps.forEach(d -> allPlatformExtensions.add(new AppArtifactKey(d.getArtifact().getGroupId(),
                        d.getArtifact().getArtifactId(), d.getArtifact().getClassifier(), d.getArtifact().getExtension())));
            }
        }
        return allPlatformExtensions;
    }

    private AppModel resolveAppModel() throws MojoExecutionException {
        final MavenArtifactResolver resolver;
        try {
            resolver = MavenArtifactResolver.builder()
                    .setRepositorySystem(repoSystem)
                    .setRepositorySystemSession(repoSession)
                    .setRemoteRepositories(repos)
                    .setRemoteRepositoryManager(remoteRepoManager)
                    .setPreferPomsFromWorkspace(true)
                    .build();
        } catch (BootstrapMavenException e) {
            throw new MojoExecutionException("Failed to initialize Maven artifact resolver", e);
        }

        final AppModel appModel;
        try {
            appModel = new BootstrapAppModelResolver(resolver).resolveModel(
                    new AppArtifact(project.getGroupId(), project.getArtifactId(), null, "pom", project.getVersion()));
        } catch (AppModelResolverException e) {
            throw new MojoExecutionException("Failed to resolve Quarkus application model", e);
        }
        return appModel;
    }

    private List<AppArtifactCoords> getBomImports(final AppModel appModel) {
        final PlatformImports platformImports = appModel.getPlatforms();
        AppArtifactCoords rhUniverseBom = null;
        AppArtifactCoords rhProdBom = null;
        final List<AppArtifactCoords> bomImports = new ArrayList<>();
        for (AppArtifactCoords coords : platformImports.getImportedPlatformBoms()) {
            if (coords.getArtifactId().equals("quarkus-universe-bom")) {
                // in pre-2.x quarkus versions, the quarkus-bom descriptor would show up as a
                // parent of the quarkus-universe-bom one
                // even if it was not actually imported, so here we simply remove it, if it was
                // found
                bomImports.remove(new AppArtifactCoords(coords.getGroupId(), "quarkus-bom", "pom", coords.getVersion()));

                if (coords.getVersion().contains("-redhat-")) {
                    rhUniverseBom = coords;
                } else if (rhUniverseBom != null && coords.getVersion().regionMatches(0,
                        rhUniverseBom.getVersion(), 0,
                        rhUniverseBom.getVersion().length() - 1 - rhUniverseBom.getVersion().indexOf("-redhat-"))) {
                    continue;
                }
            } else if (coords.getArtifactId().equals("quarkus-product-bom")) {
                // rhbq 1.x filtering
                rhProdBom = coords;
                if (bomImports.contains(
                        new AppArtifactCoords(coords.getGroupId(), "quarkus-universe-bom", "pom", coords.getVersion()))) {
                    continue;
                }
            } else if (rhProdBom != null && coords.getVersion().equals(rhProdBom.getVersion()) // rhbq 1.x filtering
                    && coords.getArtifactId().equals("quarkus-bom")) {
                continue;
            }
            bomImports.add(coords);
        }
        return bomImports;
    }
}
