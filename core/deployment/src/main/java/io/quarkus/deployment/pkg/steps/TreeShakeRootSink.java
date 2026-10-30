package io.quarkus.deployment.pkg.steps;

/**
 * Receives tree-shake roots collected from a single source (native-image configuration files,
 * substitution classes, etc.). The same root may be reported more than once.
 */
interface TreeShakeRootSink {

    /**
     * @param className dot-separated name of a class that is reachable regardless of other classes
     */
    void root(String className);

    /**
     * @param conditionClassName dot-separated name of the class whose reachability makes {@code className} reachable
     * @param className dot-separated name of the conditionally reachable class
     */
    void conditionalRoot(String conditionClassName, String className);

    /**
     * @param className dot-separated name of a class that isn't on the classpath but whose references must be traced
     * @param bytecode the class bytecode
     */
    void rootBytecode(String className, byte[] bytecode);
}
