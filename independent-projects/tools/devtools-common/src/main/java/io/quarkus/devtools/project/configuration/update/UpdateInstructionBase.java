package io.quarkus.devtools.project.configuration.update;

public abstract class UpdateInstructionBase implements UpdateInstruction {

    @Override
    public UpdateInstructionCompatibility compareTo(UpdateInstruction other) {
        return getKey().equals(other.getKey()) ? compareToActionWithSameKey(other) : UpdateInstructionCompatibility.COMPATIBLE;
    }

    protected abstract UpdateInstructionCompatibility compareToActionWithSameKey(UpdateInstruction other);
}
