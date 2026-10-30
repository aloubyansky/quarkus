package io.quarkus.deployment.pkg.builditem;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * A class that isn't on the application classpath, such as a class defined at runtime from bytecode,
 * whose references the jar tree shaker must trace as if it were a root class.
 */
public final class JarTreeShakeRootBytecodeBuildItem extends MultiBuildItem {

    private final String className;
    private final byte[] bytecode;

    /**
     * @param className dot-separated class name
     * @param bytecode class bytecode
     */
    public JarTreeShakeRootBytecodeBuildItem(String className, byte[] bytecode) {
        this.className = className;
        this.bytecode = bytecode;
    }

    public String getClassName() {
        return className;
    }

    public byte[] getBytecode() {
        return bytecode;
    }
}
