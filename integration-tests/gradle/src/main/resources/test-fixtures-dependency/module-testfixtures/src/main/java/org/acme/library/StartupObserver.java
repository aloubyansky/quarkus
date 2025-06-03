package org.acme.library;


import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class StartupObserver {

    public void onStartup(@Observes StartupEvent event) {
        throw new RuntimeException("Should not be called in GreetingResourceTest");
    }
}
