package io.quarkus.devui.deployment.menu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.devui.deployment.InternalPageBuildItem;
import io.quarkus.devui.spi.buildtime.BuildTimeActionBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
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

        Root root = new Root();
        root.rootId = curateOutcomeBuildItem.getApplicationModel().getAppArtifact().toCompactCoords();
        Set<String> allGavs = new TreeSet<>();
        buildTree(curateOutcomeBuildItem.getApplicationModel(), root, Optional.of(allGavs), Optional.empty());

        page.addBuildTimeData("root", root);
        page.addBuildTimeData("allGavs", allGavs);

        menuProducer.produce(page);
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    BuildTimeActionBuildItem createBuildTimeActions(CurateOutcomeBuildItem curateOutcomeBuildItem) {
        BuildTimeActionBuildItem pathToTargetAction = new BuildTimeActionBuildItem(NAMESPACE);
        pathToTargetAction.addAction("pathToTarget", p -> {
            String target = p.get("target");
            Root root = new Root();
            root.rootId = curateOutcomeBuildItem.getApplicationModel().getAppArtifact().toCompactCoords();

            if (target == null || target.isBlank()) {
                buildTree(curateOutcomeBuildItem.getApplicationModel(), root, Optional.empty(), Optional.empty());
            } else {
                buildTree(curateOutcomeBuildItem.getApplicationModel(), root, Optional.empty(), Optional.of(target));
            }

            return root;
        });

        return pathToTargetAction;
    }

    //"io.quarkus:quarkus-vertx-http:999-SNAPSHOT"

    private void buildTree(ApplicationModel model, Root root, Optional<Set<String>> allGavs, Optional<String> toTarget) {
        final Collection<ResolvedDependency> resolvedDeps = model.getDependencies();
        final List<Node> nodes = new ArrayList<>(resolvedDeps.size());
        final List<Link> links = new ArrayList<>();

        if (toTarget.isEmpty()) {

            addDependency(model.getAppArtifact(), nodes, links, allGavs);
            for (ResolvedDependency rd : resolvedDeps) {
                addDependency(rd, nodes, links, allGavs);
            }
        } else {
            var targetDep = getTargetDepNode(model, ArtifactCoords.fromString(toTarget.get()));
            addDependency(targetDep, nodes, links, allGavs, new HashSet<>());
        }

        root.nodes = nodes;
        root.links = links;
    }

    private static void addDependency(ResolvedDependency rd, List<Node> nodes, List<Link> links,
            Optional<Set<String>> allGavs) {
        Node node = new Node();

        if (allGavs.isPresent()) {
            allGavs.get().add(rd.toCompactCoords());
        }

        node.id = rd.toCompactCoords();
        node.name = rd.getArtifactId();
        node.description = rd.toCompactCoords();
        nodes.add(node);

        String type = rd.isRuntimeCp() ? "runtime" : "deployment";

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
            link.direct = rd.isDirect();
            links.add(link);
        }
    }

    private static void addDependency(DepNode dep, List<Node> nodes, List<Link> links, Optional<Set<String>> allGavs,
            Set<String> visited) {
        String id = dep.resolvedDep.toCompactCoords();
        if (!visited.add(id)) {
            return;
        }
        var rd = dep.resolvedDep;

        if (allGavs.isPresent()) {
            System.out.println(">>>>>>>>>>>>>> " + rd.toCompactCoords());
            allGavs.get().add(rd.toCompactCoords());
        }

        Node node = new Node();
        node.id = id;
        node.name = rd.getArtifactId();
        node.description = id;
        nodes.add(node);

        for (DepNode dependent : dep.dependents) {
            addDependency(dependent, nodes, links, allGavs, visited);
            Link link = new Link();
            link.source = dependent.resolvedDep.toCompactCoords();
            link.target = node.id;
            link.type = dependent.resolvedDep.isRuntimeCp() ? "runtime" : "deployment";
            links.add(link);
        }
    }

    static class Root {
        public String rootId;
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
        public boolean direct = false;

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

    private static DepNode getTargetDepNode(ApplicationModel model, ArtifactCoords targetCoords) {
        var all = new HashMap<ArtifactKey, DepNode>();
        var root = new DepNode(model.getAppArtifact());
        all.put(model.getAppArtifact().getKey(), root);
        for (var d : model.getDependencies()) {
            all.put(d.getKey(), new DepNode(d));
        }
        DepNode targetDep = null;
        for (var d : all.values()) {
            d.initDependents(all);
            if (targetDep == null
                    && d.resolvedDep.getArtifactId().equals(targetCoords.getArtifactId())
                    && d.resolvedDep.getGroupId().equals(targetCoords.getGroupId())
                    && d.resolvedDep.getClassifier().equals(targetCoords.getClassifier())
                    && d.resolvedDep.getType().equals(targetCoords.getType())
                    && d.resolvedDep.getVersion().equals(targetCoords.getVersion())) {
                targetDep = d;
            }
        }
        if (targetDep == null) {
            throw new IllegalArgumentException(
                    "Failed to locate " + targetCoords.toCompactCoords() + " among the dependencies");
        }
        return targetDep;
    }

    private static class DepNode {
        final ResolvedDependency resolvedDep;
        List<DepNode> dependents = List.of();

        private DepNode(ResolvedDependency resolvedDep) {
            this.resolvedDep = resolvedDep;
        }

        void initDependents(Map<ArtifactKey, DepNode> allDeps) {
            for (var depCoords : resolvedDep.getDependencies()) {
                var dep = allDeps.get(depCoords.getKey());
                if (dep == null) {
                    // TODO error/warning
                } else {
                    dep.addDependent(this);
                }
            }
        }

        private void addDependent(DepNode dependent) {
            if (dependents.isEmpty()) {
                dependents = new ArrayList<>();
            }
            dependents.add(dependent);
        }
    }
}
