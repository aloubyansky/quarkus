package io.quarkus.maven;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
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
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.registry.util.PlatformArtifacts;

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

        ArtifactCoords rhUniverseBom = null;
        ArtifactCoords rhProdBom = null;
        final List<ArtifactCoords> previousBomImports = new ArrayList<>();
        for (Dependency d : project.getDependencyManagement().getDependencies()) {
            if (!PlatformArtifacts.isCatalogArtifactId(d.getArtifactId())) {
                continue;
            }
            final ArtifactCoords platformBomCoords = new ArtifactCoords(d.getGroupId(),
                    PlatformArtifacts.ensureBomArtifactId(d.getArtifactId()), "pom", d.getVersion());
            if (d.getArtifactId().startsWith("quarkus-universe-bom-")) {
                // in pre-2.x quarkus versions, the quarkus-bom descriptor would show up as a
                // parent of the quarkus-universe-bom one
                // even if it was not actually imported, so here we simply remove it, if it was
                // found
                previousBomImports.remove(new ArtifactCoords(platformBomCoords.getGroupId(), "quarkus-bom", "pom",
                        platformBomCoords.getVersion()));

                if (platformBomCoords.getVersion().contains("-redhat-")) {
                    rhUniverseBom = platformBomCoords;
                } else if (rhUniverseBom != null && platformBomCoords.getVersion().regionMatches(0,
                        rhUniverseBom.getVersion(), 0,
                        rhUniverseBom.getVersion().length() - 1 - rhUniverseBom.getVersion().indexOf("-redhat-"))) {
                    continue;
                }
            } else if (d.getArtifactId().startsWith("quarkus-product-bom-")) {
                // rhbq 1.x filtering
                rhProdBom = platformBomCoords;
                if (previousBomImports.contains(new ArtifactCoords(platformBomCoords.getGroupId(),
                        "quarkus-universe-bom", "pom", platformBomCoords.getVersion()))) {
                    continue;
                }
            } else if (rhProdBom != null && platformBomCoords.getVersion().equals(rhProdBom.getVersion()) // rhbq 1.x
                                                                                                          // filtering
                    && platformBomCoords.getArtifactId().equals("quarkus-bom")) {
                continue;
            }
            previousBomImports.add(platformBomCoords);
        }

        final Set<ArtifactKey> allPlatformExtensions = new HashSet<>();
        if (!previousBomImports.isEmpty()) {
            for (ArtifactCoords bom : previousBomImports) {
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
                deps.forEach(d -> allPlatformExtensions.add(new ArtifactKey(d.getArtifact().getGroupId(),
                        d.getArtifact().getArtifactId(), d.getArtifact().getClassifier(), d.getArtifact().getExtension())));
            }
        }

        final Map<ArtifactKey, String> allProjectExtensions = getDirectExtensionDependencies();
        final List<ArtifactCoords> platformExtensions = new ArrayList<>();
        final List<ArtifactCoords> nonPlatformExtensions = new ArrayList<>();
        for (Map.Entry<ArtifactKey, String> e : allProjectExtensions.entrySet()) {
            if (allPlatformExtensions.contains(e.getKey())) {
                platformExtensions.add(new ArtifactCoords(e.getKey().getGroupId(), e.getKey().getArtifactId(),
                        e.getKey().getClassifier(), e.getKey().getType(), e.getValue()));
            } else {
                nonPlatformExtensions.add(new ArtifactCoords(e.getKey().getGroupId(), e.getKey().getArtifactId(),
                        e.getKey().getClassifier(), e.getKey().getType(), e.getValue()));
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

            if (!previousBomImports.isEmpty()) {
                writer.append("Platform BOM imports:");
                writer.newLine();
                for (ArtifactCoords coords : previousBomImports) {
                    writer.append(" - ").append(coords.getGroupId()).append(":").append(coords.getArtifactId())
                            .append(":").append(coords.getVersion());
                    writer.newLine();
                }
                writer.newLine();
            }

            if (!platformExtensions.isEmpty()) {
                writer.append("Platform extensions:");
                writer.newLine();
                for (ArtifactCoords coords : platformExtensions) {
                    writer.append(" - ").append(coords.getGroupId()).append(":")
                            .append(coords.getArtifactId()).append(":").append(coords.getVersion());
                    writer.newLine();
                }
                writer.newLine();
            }

            if (!nonPlatformExtensions.isEmpty()) {
                writer.append("Non-platform extensions:");
                writer.newLine();
                for (ArtifactCoords coords : nonPlatformExtensions) {
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

    private Map<ArtifactKey, String> getDirectExtensionDependencies() throws MojoExecutionException {
        final List<Dependency> modelDeps = project.getModel().getDependencies();
        final List<ArtifactRequest> requests = new ArrayList<>(modelDeps.size());
        for (Dependency d : modelDeps) {
            if ("jar".equals(d.getType())) {
                requests.add(new ArtifactRequest().setArtifact(new DefaultArtifact(d.getGroupId(), d.getArtifactId(),
                        d.getClassifier(), d.getType(), d.getVersion())).setRepositories(repos));
            }
        }
        final List<ArtifactResult> artifactResults;
        try {
            artifactResults = repoSystem.resolveArtifacts(repoSession, requests);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Failed to resolve project dependencies", e);
        }
        final Map<ArtifactKey, String> extensions = new LinkedHashMap<>(artifactResults.size());
        for (ArtifactResult ar : artifactResults) {
            final Artifact a = ar.getArtifact();
            if (isExtension(a.getFile().toPath())) {
                extensions.put(new ArtifactKey(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getExtension()),
                        a.getVersion());
            }
        }
        return extensions;
    }

    private static boolean isExtension(Path p) throws MojoExecutionException {
        if (!Files.exists(p)) {
            throw new MojoExecutionException("Extension artifact " + p + " does not exist");
        }
        if (Files.isDirectory(p)) {
            return Files.exists(p.resolve(BootstrapConstants.DESCRIPTOR_PATH));
        } else {
            try (FileSystem fs = FileSystems.newFileSystem(p, (ClassLoader) null)) {
                return Files.exists(fs.getPath(BootstrapConstants.DESCRIPTOR_PATH));
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read archive " + p, e);
            }
        }
    }
}
