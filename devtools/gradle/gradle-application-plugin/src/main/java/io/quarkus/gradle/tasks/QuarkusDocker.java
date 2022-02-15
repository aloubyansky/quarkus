package io.quarkus.gradle.tasks;

import org.gradle.api.tasks.TaskAction;

public class QuarkusDocker extends QuarkusTask {

    public QuarkusDocker() {
        super("Creates an image with Docker");
    }

    @TaskAction
    public void createImage() {
        System.out.println("Docker");
    }
}
