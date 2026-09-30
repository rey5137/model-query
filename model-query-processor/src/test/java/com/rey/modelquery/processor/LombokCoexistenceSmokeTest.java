package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

/**
 * The test classpath can run this processor beside Lombok, Lombok first (D-40). The ordering behaviour itself is
 * {@code processor/31} R-GEN-10's.
 */
class LombokCoexistenceSmokeTest {

    @Test
    void aLombokClassCompilesBesideTheProcessorAndGetsItsAccessors() {
        Compilation compilation = javac()
                .withProcessors(ProcessorHarness.lombok(), new ModelQueryProcessor())
                .compile(
                        JavaFileObjects.forSourceLines(
                                "smoke.Person",
                                "package smoke;",
                                "",
                                "@lombok.Getter",
                                "@lombok.Setter",
                                "public class Person {",
                                "    private String name;",
                                "}"),
                        // Compiles only if Lombok generated the accessors.
                        JavaFileObjects.forSourceLines(
                                "smoke.PersonUser",
                                "package smoke;",
                                "",
                                "class PersonUser {",
                                "    String rename(Person person) {",
                                "        person.setName(\"renamed\");",
                                "        return person.getName();",
                                "    }",
                                "}"));

        assertThat(compilation).succeededWithoutWarnings();
    }
}
