package io.quarkus.devtools.project.update;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class QuarkusUpdateRunner {

    public static void applyDirectly(QuarkusPomUpdateRecipe quarkusRecipe, boolean dryRun) {
        System.out.println("Updating " + quarkusRecipe.getPom() + " with:");
        System.out.println(quarkusRecipe.toYaml());

        /* @formatter:off
        final Environment environment;
        try (InputStream is = new ByteArrayInputStream(quarkusRecipe.toYaml().getBytes())) {
            environment = Environment.builder()
                    .load(new YamlResourceLoader(is, quarkusRecipe.getPom().toUri(), System.getProperties()))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed initialize Open Rewrite environment", e);
        }
        Recipe recipe = environment.activateRecipes(quarkusRecipe.getName());

        ExecutionContext ctx = new InMemoryExecutionContext(Throwable::printStackTrace);
        MavenParser mvnParser = MavenParser.builder().build();
        List<Document> poms = mvnParser.parse(List.of(quarkusRecipe.getPom()), null, ctx);
        List<Result> results = recipe.run(poms, ctx).getResults();
        for (Result result : results) {
            if (dryRun) {
                System.out.println(result.diff(null));
            } else {
                try {
                    Files.writeString(result.getAfter().getSourcePath(), result.getAfter().printAll());
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to apply update to " + quarkusRecipe.getPom(), e);
                }
            }
        }
        @formatter:on*/
    }

    public static void apply(QuarkusPomUpdateRecipe quarkusRecipe, boolean dryRun) {

        System.out.println("Updating " + quarkusRecipe.getPom() + " with:");
        System.out.println(quarkusRecipe.toYaml());

        final Path configLocation = quarkusRecipe.getProjectDir().resolve("quarkus-update.yaml");
        try (BufferedWriter writer = Files.newBufferedWriter(configLocation)) {
            writer.write(quarkusRecipe.toYaml());
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist " + configLocation, e);
        }

        Path diffFile = null;
        final List<String> commandLine = new ArrayList<>();
        commandLine.add("mvn");
        commandLine.add("-pl");
        commandLine.add(".");
        if (dryRun) {
            commandLine.add("org.openrewrite.maven:rewrite-maven-plugin:4.39.0:dryRun");
            final Path outputDir = quarkusRecipe.getProjectDir().resolve("target").resolve("rewrite");
            commandLine.add("-DreportOutputDirectory=" + outputDir);
            diffFile = outputDir.resolve("rewrite.patch");
        } else {
            commandLine.add("org.openrewrite.maven:rewrite-maven-plugin:4.39.0:run");
        }
        commandLine.add("-Drewrite.configLocation=" + configLocation);
        commandLine.add("-Drewrite.activeRecipes=" + quarkusRecipe.getName());

        Process process = null;
        try {
            process = new ProcessBuilder()
                    .directory(quarkusRecipe.getProjectDir().toFile())
                    .command(commandLine)
                    .inheritIO()
                    .start();
            if (process.waitFor() != 0) {
                System.out.println("ERROR: failed to apply updates to " + quarkusRecipe.getProjectDir());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (diffFile != null && Files.exists(diffFile)) {
            try {
                System.out.println(Files.readString(diffFile));
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }
}
