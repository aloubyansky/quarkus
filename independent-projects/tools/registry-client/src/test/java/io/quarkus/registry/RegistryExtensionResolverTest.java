package io.quarkus.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class RegistryExtensionResolverTest {

    @Test
    void mergeWithOverrideScalarValues() {
        var target = newMap("a", "1", "b", "2", "c", "3");
        var source = newMap("b", "override", "d", "4");

        RegistryExtensionResolver.mergeWithOverride(source, target);

        assertThat(target).containsEntry("a", "1")
                .containsEntry("b", "override")
                .containsEntry("c", "3")
                .containsEntry("d", "4");
    }

    @Test
    void mergeWithOverrideNestedMaps() {
        var targetInner = newMap("x", "1", "y", "2");
        var target = new HashMap<String, Object>();
        target.put("nested", targetInner);
        target.put("top", "keep");

        var sourceInner = newMap("y", "override", "z", "3");
        var source = new HashMap<String, Object>();
        source.put("nested", sourceInner);

        RegistryExtensionResolver.mergeWithOverride(source, target);

        assertThat(target).containsEntry("top", "keep");
        @SuppressWarnings("unchecked")
        var merged = (Map<String, Object>) target.get("nested");
        assertThat(merged).containsEntry("x", "1")
                .containsEntry("y", "override")
                .containsEntry("z", "3");
    }

    @Test
    void mergeWithOverrideReplacesNonMapWithMap() {
        var target = newMap("key", "scalar");
        var source = new HashMap<String, Object>();
        source.put("key", newMap("a", "1"));

        RegistryExtensionResolver.mergeWithOverride(source, target);

        assertThat(target.get("key")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) target.get("key");
        assertThat(nested).containsEntry("a", "1");
    }

    @Test
    void mergeWithOverrideReplacesMapWithScalar() {
        var target = new HashMap<String, Object>();
        target.put("key", newMap("a", "1"));

        var source = newMap("key", "scalar");

        RegistryExtensionResolver.mergeWithOverride(source, target);

        assertThat(target).containsEntry("key", "scalar");
    }

    @Test
    void mergeWithOverrideListValues() {
        var target = new HashMap<String, Object>();
        target.put("repos", List.of("repo1"));

        var source = new HashMap<String, Object>();
        source.put("repos", List.of("repo2", "repo3"));

        RegistryExtensionResolver.mergeWithOverride(source, target);

        @SuppressWarnings("unchecked")
        var repos = (List<String>) target.get("repos");
        assertThat(repos).containsExactly("repo2", "repo3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergeWithOverrideDeeplyNested() {
        var targetL3 = newMap("deep", "original", "kept", "yes");
        var targetL2 = new HashMap<String, Object>();
        targetL2.put("level3", targetL3);
        var targetL1 = new HashMap<String, Object>();
        targetL1.put("level2", targetL2);
        var target = new HashMap<String, Object>();
        target.put("level1", targetL1);

        var sourceL3 = newMap("deep", "overridden", "added", "new");
        var sourceL2 = new HashMap<String, Object>();
        sourceL2.put("level3", sourceL3);
        var sourceL1 = new HashMap<String, Object>();
        sourceL1.put("level2", sourceL2);
        var source = new HashMap<String, Object>();
        source.put("level1", sourceL1);

        RegistryExtensionResolver.mergeWithOverride(source, target);

        var mergedL3 = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) target.get("level1"))
                .get("level2")).get("level3");
        assertThat(mergedL3).containsEntry("deep", "overridden")
                .containsEntry("kept", "yes")
                .containsEntry("added", "new");
    }

    @Test
    void mergeWithOverrideEmptySource() {
        var target = newMap("a", "1", "b", "2");
        RegistryExtensionResolver.mergeWithOverride(new HashMap<>(), target);
        assertThat(target).containsEntry("a", "1").containsEntry("b", "2").hasSize(2);
    }

    @Test
    void mergeWithOverrideMavenRepositories() {
        var targetRepos = new ArrayList<>();
        targetRepos.add(Map.of("id", "default-repo", "url", "https://default.example.com"));
        var targetMaven = new HashMap<String, Object>();
        targetMaven.put("repositories", targetRepos);
        var target = new HashMap<String, Object>();
        target.put("maven", targetMaven);
        target.put("project", newMap("prop1", "default"));

        var sourceRepos = new ArrayList<>();
        sourceRepos.add(Map.of("id", "offering-repo", "url", "https://offering.example.com"));
        var sourceMaven = new HashMap<String, Object>();
        sourceMaven.put("repositories", sourceRepos);
        var source = new HashMap<String, Object>();
        source.put("maven", sourceMaven);

        RegistryExtensionResolver.mergeWithOverride(source, target);

        @SuppressWarnings("unchecked")
        var mergedMaven = (Map<String, Object>) target.get("maven");
        @SuppressWarnings("unchecked")
        var repos = (List<Map<String, Object>>) mergedMaven.get("repositories");
        assertThat(repos).hasSize(1);
        assertThat(repos.get(0)).containsEntry("id", "offering-repo");

        @SuppressWarnings("unchecked")
        var project = (Map<String, Object>) target.get("project");
        assertThat(project).containsEntry("prop1", "default");
    }

    private static HashMap<String, Object> newMap(String... keyValues) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
