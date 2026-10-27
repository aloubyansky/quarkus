package io.quarkus.deployment.pkg.steps;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Tests for the collection of service providers declared in module descriptors.
 */
class JarTreeShakeInputModuleInfoTest {

    @Test
    void moduleInfoEntries() {
        assertThat(JarTreeShakeInput.isModuleInfoEntry("module-info.class")).isTrue();
        assertThat(JarTreeShakeInput.isModuleInfoEntry("META-INF/versions/9/module-info.class")).isTrue();
        assertThat(JarTreeShakeInput.isModuleInfoEntry("META-INF/versions/9/org/acme/module-info.class")).isFalse();
        assertThat(JarTreeShakeInput.isModuleInfoEntry("org/acme/module-info.class")).isFalse();
        assertThat(JarTreeShakeInput.isModuleInfoEntry("org/acme/Foo.class")).isFalse();
    }

    @Test
    void providesAreCollected(@TempDir Path dir) throws IOException {
        Path moduleInfo = dir.resolve("module-info.class");
        Files.write(moduleInfo, generateModuleInfo("org.acme.natives",
                "org/acme/NativeProvider", "org/acme/natives/LinuxNativeProvider"));
        Map<String, Set<String>> serviceProviders = new HashMap<>();
        // a provider of the same service already collected from a META-INF/services file
        serviceProviders.put("org.acme.NativeProvider", new HashSet<>(Set.of("org.acme.FromServicesFile")));

        JarTreeShakeInput.parseModuleInfoProviders(moduleInfo, serviceProviders);

        assertThat(serviceProviders).containsOnly(Map.entry("org.acme.NativeProvider",
                Set.of("org.acme.FromServicesFile", "org.acme.natives.LinuxNativeProvider")));
    }

    private static byte[] generateModuleInfo(String moduleName, String service, String provider) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor mv = cw.visitModule(moduleName, 0, null);
        mv.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        mv.visitProvide(service, provider);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
