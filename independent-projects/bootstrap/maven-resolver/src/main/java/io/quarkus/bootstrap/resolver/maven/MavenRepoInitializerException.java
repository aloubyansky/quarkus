package io.quarkus.bootstrap.resolver.maven;

public class MavenRepoInitializerException extends Exception {

    private static final long serialVersionUID = 1L;

    public MavenRepoInitializerException(String message, Throwable cause) {
        super(message, cause);
    }

    public MavenRepoInitializerException(String message) {
        super(message);
    }
}
