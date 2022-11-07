package io.quarkus.devtools.project.configuration.update;

public enum UpdateInstructionCompatibility {
    COMPATIBLE,
    CONFLICTS,
    MATCHES,
    SUPERSEDED,
    SUPERSEDES
}
