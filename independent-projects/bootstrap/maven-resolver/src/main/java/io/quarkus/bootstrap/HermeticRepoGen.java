package io.quarkus.bootstrap;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import org.eclipse.aether.artifact.*;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.resolution.*;

import io.quarkus.bootstrap.resolver.maven.*;
import io.quarkus.bootstrap.util.*;
import io.quarkus.maven.dependency.*;

public class HermeticRepoGen {

    public static void main(String[] args) throws Exception {

        final Path localRepo = Path.of("/home/aloubyansky/playground/test-repo");
        Path output = localRepo.resolve("artifacts.txt");
        if (!Files.exists(output)) {
            throw new IllegalArgumentException(output + " does not exist");
        }
        var lines = Files.readAllLines(output);
        IoUtils.recursiveDelete(localRepo);
        Files.createDirectories(localRepo);

        final BootstrapMavenContext ctx = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setWorkspaceDiscovery(false)
                .setLocalRepository(localRepo.toString()));
        var system = ctx.getRepositorySystem();
        var session = ctx.getRepositorySystemSession();
        var repos = ctx.getRemoteRepositories();
        var remoteRepoManager = ctx.getRemoteRepositoryManager();

        Phaser phaser = new Phaser(1);
        for (var line : lines) {
            CompletableFuture.runAsync(() -> {
                phaser.register();
                try {
                    ArtifactCoords coords;
                    List<RemoteRepository> repoList = repos;
                    if (line.charAt(line.length() - 1) == ')') {
                        var i = line.indexOf('(');
                        if (i > 0) {
                            var repoUrl = line.substring(i + 1, line.length() - 1);
                            int idStart = repoUrl.indexOf("//") + 1;
                            var id = repoUrl.substring(idStart, repoUrl.indexOf('/', idStart));
                            repoList = remoteRepoManager.aggregateRepositories(session, repos,
                                    List.of(new RemoteRepository.Builder(id, "default", repoUrl).build()), true);
                        }
                        coords = ArtifactCoords.fromString(line.substring(0, i));
                    } else {
                        coords = ArtifactCoords.fromString(line);
                    }
                    system.resolveArtifact(session, new ArtifactRequest()
                            .setArtifact(new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(),
                                    coords.getClassifier(), coords.getType(), coords.getVersion()))
                            .setRepositories(repoList));
                } catch (ArtifactResolutionException e) {
                    e.printStackTrace();
                } finally {
                    phaser.arriveAndDeregister();
                }
            });
        }
        phaser.arriveAndAwaitAdvance();
    }
}
