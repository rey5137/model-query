package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles one probe expression against {@code core}, for tests that a call does or does not compile. */
final class CompileProbe {

    private CompileProbe() {}

    /**
     * Compiles {@code static final Object PROBE = call;} in a class holding {@code members}, with {@code options}
     * added to javac's, and returns the errors and warnings on the probe's line. One on any other line fails the test,
     * since it means the probe itself is broken rather than the call under test.
     */
    static List<String> problems(Path out, List<String> members, String call, String... options) throws IOException {
        List<String> header = List.of("package probe;", "import com.rey.modelquery.core.*;", "import java.util.List;",
                "class Probe {");
        List<String> lines = Stream.of(header, members, List.of("    static final Object PROBE = " + call + ";", "}"))
                .flatMap(List::stream)
                .toList();
        long probeLine = lines.size() - 1;
        String source = String.join("\n", lines);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///probe/Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, null)) {
            List<String> javacOptions = new ArrayList<>(List.of("-proc:none", "-d", out.toString(),
                    "-classpath", System.getProperty("java.class.path")));
            javacOptions.addAll(List.of(options));
            boolean compiled = javac.getTask(null, files, diagnostics, javacOptions, null, List.of(file)).call();
            List<String> problems = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                boolean reported = d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING
                        || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING;
                if (reported && d.getLineNumber() != Diagnostic.NOPOS) {
                    assertThat(d.getLineNumber()).as(d.toString()).isEqualTo(probeLine);
                    problems.add(d.getMessage(null));
                }
            }
            if (problems.isEmpty()) {
                assertThat(compiled).as(diagnostics.getDiagnostics().toString()).isTrue();
            }
            return problems;
        }
    }
}
