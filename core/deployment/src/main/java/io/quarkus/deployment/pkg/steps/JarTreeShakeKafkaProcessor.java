package io.quarkus.deployment.pkg.steps;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeRootClassBuildItem;

/**
 * Contributes root classes for Kafka's LZ4 compression support.
 * {@code LZ4Factory} constructs implementation class names dynamically via
 * {@code StringBuilder} and loads them with {@code ClassLoader.loadClass()},
 * which cannot be resolved by static bytecode analysis.
 */
public class JarTreeShakeKafkaProcessor {

    /**
     * LZ4Factory dynamically loads compressor/decompressor implementations
     * by constructing class names from a prefix ({@code "net.jpountz.lz4.LZ4"}
     * or {@code "net.jpountz.lz4.LZ4HC"}) + variant ({@code "JavaSafe"},
     * {@code "JavaUnsafe"}, {@code "JNI"}) + suffix ({@code "Compressor"},
     * {@code "FastDecompressor"}, {@code "SafeDecompressor"}).
     * <p>
     * XXHashFactory dynamically loads hash implementations by constructing
     * class names from a prefix + variant ({@code "JavaSafe"}, {@code "JavaUnsafe"},
     * {@code "JNI"}). Covers {@code XXHash32}, {@code XXHash64}, and their
     * streaming counterparts.
     */
    @BuildStep
    void collectLz4Roots(BuildProducer<JarTreeShakeRootClassBuildItem> roots) {
        for (String variant : new String[] { "JavaSafe", "JavaUnsafe", "JNI" }) {
            roots.produce(new JarTreeShakeRootClassBuildItem("net.jpountz.lz4.LZ4" + variant + "Compressor"));
            roots.produce(new JarTreeShakeRootClassBuildItem("net.jpountz.lz4.LZ4HC" + variant + "Compressor"));
            roots.produce(new JarTreeShakeRootClassBuildItem("net.jpountz.lz4.LZ4" + variant + "FastDecompressor"));
            roots.produce(new JarTreeShakeRootClassBuildItem("net.jpountz.lz4.LZ4" + variant + "SafeDecompressor"));

            roots.produce(new JarTreeShakeRootClassBuildItem("net.jpountz.xxhash.XXHash32" + variant));
            roots.produce(new JarTreeShakeRootClassBuildItem("net.jpountz.xxhash.XXHash64" + variant));
            roots.produce(new JarTreeShakeRootClassBuildItem("net.jpountz.xxhash.StreamingXXHash32" + variant));
            roots.produce(new JarTreeShakeRootClassBuildItem("net.jpountz.xxhash.StreamingXXHash64" + variant));
        }
    }
}
