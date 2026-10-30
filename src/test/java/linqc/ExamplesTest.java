package linqc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** End to end: every examples/Name.jlinq is translated, compiled, run, and must print Name.out. */
class ExamplesTest {

    static List<Path> examples() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("examples"))) {
            return files.filter(f -> f.toString().endsWith(".jlinq")).sorted().toList();
        }
    }

    private static String runAndCapture(String className, String java, String sourceName) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            JavaRunner.compileAndRun(className, java, sourceName);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void examplePrintsItsExpectedOutput(Path example) throws Exception {
        String fileName = example.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".jlinq".length());
        String java = Translator.translate(Files.readString(example), fileName);
        String expected = Files.readString(example.resolveSibling(className + ".out"));

        assertEquals(expected, runAndCapture(className, java, fileName));
    }

    @Test
    void javacErrorsPointAtTheJlinqLine() {
        String jlinq = """
                import java.util.List;
                class Typo {
                    static Object q = from s in List.of("a")
                                      where s.lenght() > 0
                                      select s;
                }
                """;
        String java = Translator.translate(jlinq, "Typo.jlinq");
        JavaRunner.CompileError error = assertThrows(JavaRunner.CompileError.class,
                () -> JavaRunner.compileAndRun("Typo", java, "Typo.jlinq"));
        assertEquals("Typo.jlinq:4: error: cannot find symbol", error.getMessage().lines().findFirst().orElseThrow());
    }
}
