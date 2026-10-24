package com.rey.modelquery.spike.rfc0022;

import com.rey.modelquery.core.EntityJoinRenderer;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

/** Compiles the plain 3.2 interface calls only inside a 3.2 runtime; the 6.6 lane keeps API 3.1. */
final class PortableRenderer {
    private PortableRenderer() {}

    static EntityJoinRenderer load() throws Exception {
        return (EntityJoinRenderer) instantiate("PlainEntityJoinRenderer");
    }

    static Object instantiate(String name) throws Exception {
        Path output = Path.of("target", "portable-renderer");
        Files.createDirectories(output);
        Path source = output.resolve(name + ".java");
        Files.copy(SpikeHarness.OUT_DIR.resolve(name + ".java.template"), source,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
                "-classpath", System.getProperty("java.class.path"), "-d", output.toString(), source.toString());
        if (result != 0) {
            throw new IOException("Portable renderer compilation failed: " + result);
        }
        try (var loader = new URLClassLoader(new java.net.URL[] {output.toUri().toURL()},
                PortableRenderer.class.getClassLoader())) {
            return Class.forName("com.rey.modelquery.spike.rfc0022." + name, true, loader)
                    .getConstructor().newInstance();
        }
    }
}
