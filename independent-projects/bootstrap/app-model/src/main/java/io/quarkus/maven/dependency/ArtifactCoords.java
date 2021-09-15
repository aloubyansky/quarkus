package io.quarkus.maven.dependency;

public interface ArtifactCoords {

    String TYPE_JAR = "jar";
    String TYPE_POM = "pom";

    String getGroupId();

    String getArtifactId();

    String getClassifier();

    String getType();

    String getVersion();

    ArtifactKey getKey();
}
