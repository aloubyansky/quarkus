package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

public interface UpdateStep<I, K> {

    /**
     * An identifier of an update step. Typically, an ID would be derived from what
     * needs to be updated and the target value.
     *
     * @return identifier of an update step
     */
    I getId();

    /**
     * A key of an update step. A key is meant to represent what needs to be updated but not
     * the target value. Key are meant to be used to detect conflicting update steps. For example,
     * when there are multiple steps that would be updating the same thing with different values.
     *
     * @return key of an update step
     */
    K getKey();

    /**
     * The file a step will be updating.
     *
     * @return the file a step will be updating
     */
    Path getFile();

}
