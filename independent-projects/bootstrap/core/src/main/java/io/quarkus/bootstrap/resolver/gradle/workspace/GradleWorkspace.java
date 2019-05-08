/**
 *
 */
package io.quarkus.bootstrap.resolver.gradle.workspace;

import java.io.File;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.gradle.GradlePublication;
import org.gradle.tooling.model.gradle.ProjectPublications;

public class GradleWorkspace {

    public static void main(String[] args) throws Exception {

        final ProjectConnection con = GradleConnector.newConnector()
                .forProjectDirectory(new File("/home/aloubyansky/git/quarkus-quickstarts/getting-started"))
                .connect();
        try {
            ProjectPublications publications = con.getModel(ProjectPublications.class);
            System.out.println(publications.getProjectIdentifier());

            for(GradlePublication publication : publications.getPublications().getAll()) {
                System.out.println(publication.getClass());
            }
        } catch(Throwable t) {
            t.printStackTrace();
        } finally {
            con.close();
        }
    }
}
