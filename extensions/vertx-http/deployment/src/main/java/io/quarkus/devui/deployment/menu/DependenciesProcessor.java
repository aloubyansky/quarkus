package io.quarkus.devui.deployment.menu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.devui.deployment.InternalPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ResolvedDependency;

public class DependenciesProcessor {

    private static final String NAMESPACE = "devui-dependencies";

    @BuildStep(onlyIf = IsDevelopment.class)
    void createAppDeps(BuildProducer<InternalPageBuildItem> menuProducer,
            CurateOutcomeBuildItem curateOutcomeBuildItem) {

        // Menu

        InternalPageBuildItem page = new InternalPageBuildItem("Dependencies", 70);

        page.addPage(Page.webComponentPageBuilder()
                .namespace(NAMESPACE)
                .icon("font-awesome-solid:diagram-project")
                .title("Application Dependencies")
                .componentLink("qwc-dependencies.js"));

        page.addBuildTimeData("root", buildTree(curateOutcomeBuildItem.getApplicationModel()));
        menuProducer.produce(page);
    }

    private Root buildTree(ApplicationModel model) {
        final Collection<ResolvedDependency> resolvedDeps = model.getDependencies();
        final List<Node> nodes = new ArrayList<>(resolvedDeps.size());
        final List<Link> links = new ArrayList<>();

        addDependency(model.getAppArtifact(), nodes, links);
        for (ResolvedDependency rd : resolvedDeps) {
            addDependency(rd, nodes, links);
        }

        Root r = new Root();
        r.nodes = nodes;
        r.links = links;

        return r;
    }

    private static void addDependency(ResolvedDependency rd, List<Node> nodes, List<Link> links) {
        Node node = new Node();
        node.id = rd.toCompactCoords();
        node.name = rd.toCompactCoords();
        node.description = rd.toGACTVString();
        nodes.add(node);

        String type = "deployment";
        if (rd.isRuntimeCp()) {
            type = "runtime";
        }
        for (ArtifactCoords dep : rd.getDependencies()) {
            // this needs to be improved, these artifacts shouldn't even be mentioned among the dependencies
            if ("quarkus-ide-launcher".equals(dep.getArtifactId())
                    || "javax.annotation-api".equals(dep.getArtifactId())) {
                continue;
            }
            Link link = new Link();
            link.source = node.id;
            link.target = dep.toCompactCoords();
            link.type = type;
            links.add(link);
        }
    }

    static class Root {
        public List<Node> nodes;
        public List<Link> links;
    }

    static class Node {
        public String id;
        public String name;
        public int value = 1;
        public String description;

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 23 * hash + Objects.hashCode(this.id);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Node other = (Node) obj;
            return Objects.equals(this.id, other.id);
        }
    }

    static class Link {
        public String source;
        public String target;
        public String type;

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 97 * hash + Objects.hashCode(this.source);
            hash = 97 * hash + Objects.hashCode(this.target);
            hash = 97 * hash + Objects.hashCode(this.type);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Link other = (Link) obj;
            if (!Objects.equals(this.source, other.source)) {
                return false;
            }
            if (!Objects.equals(this.target, other.target)) {
                return false;
            }
            return Objects.equals(this.type, other.type);
        }
    }
}
