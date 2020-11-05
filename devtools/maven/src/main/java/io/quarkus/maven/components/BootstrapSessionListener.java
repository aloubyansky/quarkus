package io.quarkus.maven.components;

import java.io.IOException;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

import io.quarkus.maven.QuarkusBootstrapProvider;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "quarkus-bootstrap")
public class BootstrapSessionListener extends AbstractMavenLifecycleParticipant {

    @Requirement(optional = false)
    protected QuarkusBootstrapProvider bootstrapProvider;

    public void afterProjectsRead(MavenSession session)
            throws MavenExecutionException {
        System.out.println("BootstrapSessionListener.afterProjectsRead " + session.getCurrentProject().getArtifact());

        //session.getCurrentProject().getDependencies().add(dep);
    }

    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        try {
            bootstrapProvider.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
