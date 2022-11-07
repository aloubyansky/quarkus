package io.quarkus.devtools.project.configuration.update;

public interface UpdateInstruction {

    UpdateInstructionKey getKey();

    UpdateInstructionCompatibility compareTo(UpdateInstruction action);
}
