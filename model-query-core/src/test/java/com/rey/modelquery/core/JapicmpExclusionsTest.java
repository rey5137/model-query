package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.annotations.Incubating;
import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.model.JApiClass;
import japicmp.model.JApiMethod;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * AC-REL-06: the {@code japicmp} configuration in the parent pom, whose exclusions this test reads, fails on a removed
 * public method of a frozen type and ignores the same removal from {@code @Incubating}, {@code @EngineFacing} and
 * {@code jpa.vendor} API (R-REL-10, R-REL-11).
 */
class JapicmpExclusionsTest {

    private static final String PKG = "com.rey.modelquery.fixture";

    private static final String INC = "@com.rey.modelquery.annotations.Incubating";

    private static final String ENG = "@com.rey.modelquery.core.EngineFacing";

    @TempDir
    Path dir;

    @Test
    void ac_rel_06_a_removed_method_of_a_frozen_type_breaks_the_check() throws Exception {
        Path before = jar("before", sources(true));
        Path after = jar("after", sources(false));

        assertThat(removedMethods(before, after)).containsExactlyInAnyOrder("Frozen.gone");
    }

    @Test
    void ac_rel_06_the_same_removal_is_ignored_on_non_api() throws Exception {
        Path before = jar("before", sources(true));
        Path after = jar("after", sources(false));

        // The fixture removes a method from every kind of non-API too; none of those shows up above.
        assertThat(removedMethods(before, after)).doesNotContain("IncubatingType.gone", "Mixed.incubating",
                "Mixed.engine", "EngineType.gone");
        assertThat(removedMethods(before, after, List.of())).contains("Frozen.gone", "IncubatingType.gone",
                "Mixed.incubating", "Mixed.engine", "EngineType.gone", "Vendor.gone");
    }

    private List<String> removedMethods(Path before, Path after) throws Exception {
        return removedMethods(before, after, parentExcludes());
    }

    private static List<String> removedMethods(Path before, Path after, List<String> excludes) throws Exception {
        Options options = Options.newDefault();
        for (String exclude : excludes) {
            options.addExcludeFromArgument(Optional.of(exclude), false);
        }
        JarArchiveComparator comparator = new JarArchiveComparator(JarArchiveComparatorOptions.of(options));
        List<JApiClass> classes = comparator.compare(new JApiCmpArchive(before.toFile(), "1"),
                new JApiCmpArchive(after.toFile(), "2"));
        List<String> removed = new ArrayList<>();
        for (JApiClass type : classes) {
            for (JApiMethod method : type.getMethods()) {
                if (!method.isBinaryCompatible()) {
                    String name = type.getFullyQualifiedName();
                    removed.add(name.substring(name.lastIndexOf('.') + 1) + "." + method.getName());
                }
            }
        }
        return removed;
    }

    /** The exclusions of the {@code japicmp-maven-plugin} in the parent pom: the one place they are listed. */
    private static List<String> parentExcludes() throws Exception {
        Path pom = Path.of("..", "pom.xml");
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
                .getDocumentElement();
        NodeList plugins = root.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if (plugin.getElementsByTagName("artifactId").item(0).getTextContent().equals("japicmp-maven-plugin")) {
                NodeList excludes = plugin.getElementsByTagName("exclude");
                List<String> result = new ArrayList<>();
                for (int j = 0; j < excludes.getLength(); j++) {
                    result.add(excludes.item(j).getTextContent().trim());
                }
                assertThat(result).isNotEmpty();
                return result;
            }
        }
        throw new AssertionError("no japicmp-maven-plugin in " + pom);
    }

    private static Map<String, String> sources(boolean withRemovable) {
        String m = withRemovable ? "public void gone() {}" : "";
        return Map.of(
                "Frozen", "public class Frozen { public void kept() {} " + m + " }",
                "IncubatingType", INC + " public class IncubatingType { public void kept() {} " + m + " }",
                "EngineType", ENG + " public class EngineType { public void kept() {} " + m + " }",
                "Mixed", "public class Mixed { public void kept() {} "
                        + (withRemovable ? INC + " public void incubating() {} " + ENG + " public void engine() {}"
                                : "") + " }");
    }

    private Path jar(String name, Map<String, String> types) throws IOException {
        Path classes = Files.createDirectories(dir.resolve(name + "-classes"));
        List<JavaFileObject> units = new ArrayList<>();
        types.forEach((type, body) -> units.add(source(PKG, type, body)));
        // the vendor package: same removal, excluded by package
        String vendor = "public class Vendor { public void kept() {} "
                + (name.equals("before") ? "public void gone() {}" : "") + " }";
        units.add(source("com.rey.modelquery.jpa.vendor", "Vendor", vendor));
        String classpath = codeSource(Incubating.class) + java.io.File.pathSeparator + codeSource(EngineFacing.class);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        boolean ok = compiler.getTask(null, null, null, List.of("-d", classes.toString(), "-classpath", classpath),
                null, units).call();
        assertThat(ok).isTrue();
        Path jar = dir.resolve(name + ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out);
                Stream<Path> files = Files.walk(classes)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                jos.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jos);
                jos.closeEntry();
            }
        }
        return jar;
    }

    private static String codeSource(Class<?> type) throws IOException {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static JavaFileObject source(String pkg, String type, String body) {
        String text = "package " + pkg + "; " + body;
        return new SimpleJavaFileObject(URI.create("string:///" + pkg.replace('.', '/') + "/" + type + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }
}
