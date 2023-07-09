package io.quarkus.bootstrap.resolver.maven;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;

public class Playground {

    public static void main(String[] args) throws Exception {

        var mvnCtx = new BootstrapMavenContext(BootstrapMavenContext
                .config()
                .setWorkspaceDiscovery(false));
        final DefaultRepositorySystemSession session = (DefaultRepositorySystemSession) mvnCtx.getRepositorySystemSession();
        session.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, ConflictResolver.Verbosity.FULL);
        //session.setConfigProperty(DependencyManagerUtils.CONFIG_PROP_VERBOSE, true);

        var descr = mvnCtx.getRepositorySystem().readArtifactDescriptor(session, new ArtifactDescriptorRequest()
                .setArtifact(new DefaultArtifact("org.acme", "acme-product-a", "jar", "1.0.0-SNAPSHOT"))
                .setRepositories(mvnCtx.getRemoteRepositories()));

        var node = mvnCtx.getRepositorySystem().resolveDependencies(session, new DependencyRequest()
                .setCollectRequest(new CollectRequest()
                        .setRootArtifact(descr.getArtifact())
                        .setDependencies(descr.getDependencies())
                        .setRepositories(mvnCtx.getRemoteRepositories())))
                .getRoot();
        node.accept(new DependencyGraphDumper(System.out::println));
    }

    public static class DependencyGraphDumper implements DependencyVisitor {

        private final Consumer<String> consumer;

        private final List<ChildInfo> childInfos = new ArrayList<>();

        public DependencyGraphDumper(Consumer<String> consumer) {
            this.consumer = requireNonNull(consumer);
        }

        @Override
        public boolean visitEnter(DependencyNode node) {
            consumer.accept(formatIndentation() + formatNode(node));
            childInfos.add(new ChildInfo(node.getChildren().size()));
            return true;
        }

        private String formatIndentation() {
            StringBuilder buffer = new StringBuilder(128);
            for (Iterator<ChildInfo> it = childInfos.iterator(); it.hasNext();) {
                buffer.append(it.next().formatIndentation(!it.hasNext()));
            }
            return buffer.toString();
        }

        private String formatNode(DependencyNode node) {
            StringBuilder buffer = new StringBuilder(128);
            Artifact a = node.getArtifact();
            Dependency d = node.getDependency();
            buffer.append(a);
            if (d != null && d.getScope().length() > 0) {
                buffer.append(" [").append(d.getScope());
                if (d.isOptional()) {
                    buffer.append(", optional");
                }
                buffer.append("]");
            }
            buffer.append(" ").append(node.hashCode());
            String premanaged = DependencyManagerUtils.getPremanagedVersion(node);
            if (premanaged != null && !premanaged.equals(a.getBaseVersion())) {
                buffer.append(" (version managed from ").append(premanaged).append(")");
            }

            premanaged = DependencyManagerUtils.getPremanagedScope(node);
            if (premanaged != null && !premanaged.equals(d.getScope())) {
                buffer.append(" (scope managed from ").append(premanaged).append(")");
            }
            DependencyNode winner = (DependencyNode) node.getData().get(ConflictResolver.NODE_DATA_WINNER);
            if (winner != null) {
                if (ArtifactIdUtils.equalsId(a, winner.getArtifact())) {
                    buffer.append(" (nearer exists)");
                } else {
                    Artifact w = winner.getArtifact();
                    buffer.append(" (conflicts with ");
                    if (ArtifactIdUtils.toVersionlessId(a).equals(ArtifactIdUtils.toVersionlessId(w))) {
                        buffer.append(w.getVersion());
                        buffer.append(" ").append(winner.hashCode());
                    } else {
                        buffer.append(w);
                    }
                    buffer.append(")");
                }
            }
            return buffer.toString();
        }

        @Override
        public boolean visitLeave(DependencyNode node) {
            if (!childInfos.isEmpty()) {
                childInfos.remove(childInfos.size() - 1);
            }
            if (!childInfos.isEmpty()) {
                childInfos.get(childInfos.size() - 1).index++;
            }
            return true;
        }

        private static class ChildInfo {

            final int count;

            int index;

            ChildInfo(int count) {
                this.count = count;
            }

            public String formatIndentation(boolean end) {
                boolean last = index + 1 >= count;
                if (end) {
                    return last ? "\\- " : "+- ";
                }
                return last ? "   " : "|  ";
            }
        }
    }
}
