package io.quarkus.gradle.tasks;

import org.gradle.api.tasks.TaskAction;

public class QuarkusJib extends QuarkusTask {

    public QuarkusJib() {
        super("Creates an image with JIB");
    }

    @TaskAction
    public void createImage() {
        System.out.println("JIB");
    }
}
