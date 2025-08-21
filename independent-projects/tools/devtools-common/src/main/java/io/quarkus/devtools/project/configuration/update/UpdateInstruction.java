package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;

public interface UpdateInstruction {

    UpdateInstructionKey getKey();

    Path getFile();

    UpdateInstructionCompatibility compareTo(UpdateInstruction action);
}
