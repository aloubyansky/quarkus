package org.jboss.resteasy.reactive.client.impl;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;

import org.jboss.resteasy.reactive.client.handlers.VertxClientInputStream;
import org.jboss.resteasy.reactive.common.jaxrs.ResponseImpl;
import org.jboss.resteasy.reactive.common.util.EmptyInputStream;

/**
 * This is the Response class client response
 * object with more deserialising powers than user-created responses @{link {@link ResponseImpl}.
 */
public class ClientResponseImpl extends ResponseImpl {

    RestClientRequestContext restClientRequestContext;

    @Override
    public boolean bufferEntity() {
        // we don't want to buffer these as they could lead to blocking the event loop
        InputStream entityStream = getEntityStream();
        if (entityStream == null) {
            return false;
        }
        if (VertxClientInputStream.class.equals(entityStream.getClass())) {
            return true;
        }
        return super.bufferEntity();
    }

    @SuppressWarnings({ "unchecked" })
    protected <T> T readEntity(Class<T> entityType, Type genericType, Annotation[] annotations) {
        // TODO: we probably need better state handling
        if (entity != null && entityType.isInstance(entity)) {
            // Note that this works if entityType is InputStream where we return it without closing it, as per spec
            return (T) entity;
        }

        checkClosed();

        // apparently we're trying to re-read it here, even if we already have an entity, as long as it's not the right
        // type
        // Note that this will get us the entity if it's an InputStream because setEntity checks that
        InputStream entityStream = getEntityStream();
        if (entityStream == null) {
            entityStream = new EmptyInputStream();
        }

        // it's possible we already read it for a different type, so try to reset it
        try {
            if (buffered) {
                entityStream.reset();
            }
        } catch (IOException e) {
            throw new ProcessingException(e);
        }

        // Spec says to return the input stream as-is, without closing it, if that's what we want
        if (InputStream.class.isAssignableFrom(entityType)) {
            return (T) entityStream;
        }

        MediaType mediaType = getMediaType();
        try {
            entity = ClientSerialisers.invokeClientReader(annotations, entityType, genericType, mediaType,
                    restClientRequestContext.properties, restClientRequestContext, getStringHeaders(),
                    restClientRequestContext.getRestClient().getClientContext().getSerialisers(),
                    entityStream, restClientRequestContext.getReaderInterceptors(), restClientRequestContext.configuration);
            consumed = true;
            close();
            return (T) entity;
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
    }

    public String getHttpVersion() {
        return restClientRequestContext.getVertxClientResponse().version().toString();
    }

    public StackTraceElement[] getCallerStackTrace() {
        return restClientRequestContext.getCallerStackTrace();
    }
}
