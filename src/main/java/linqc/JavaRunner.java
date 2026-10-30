package linqc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import linq.Query;

/** Compiles translated code with javac (in this JVM) and runs its main method. */
final class JavaRunner {

    /** javac rejected the translated code. The message uses the .jlinq file's line numbers. */
    static final class CompileError extends RuntimeException {
        CompileError(String message) {
            super(message);
        }
    }

    private JavaRunner() {
    }

    /**
     * @param className  the public class in {@code javaSource}, e.g. "Adults"
     * @param sourceName the .jlinq file name to show in compile errors
     */
    static void compileAndRun(String className, String javaSource, String sourceName, String... args) throws Exception {
        Path classes = Files.createTempDirectory("linqc");
        try {
            compile(className, javaSource, sourceName, classes);
            run(className, classes, args);
        } finally {
            deleteRecursively(classes);
        }
    }

    private static void compile(String className, String javaSource, String sourceName, Path classes) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("no Java compiler available; run linqc on a JDK, not a JRE");
        }
        Path sourceFile = classes.resolve(className + ".java");
        Files.writeString(sourceFile, javaSource);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            // The generated code only needs the linq library on its classpath.
            List<String> options = List.of("-d", classes.toString(), "-classpath", libraryLocation());
            boolean ok = javac.getTask(null, files, diagnostics, options, null, files.getJavaFileObjects(sourceFile)).call();
            if (!ok) {
                List<String> errors = new ArrayList<>();
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        // The translator keeps line numbers, so javac's line is the .jlinq line.
                        errors.add(sourceName + ":" + d.getLineNumber() + ": error: " + d.getMessage(Locale.ROOT));
                    }
                }
                throw new CompileError(String.join("\n", errors));
            }
        }
    }

    private static void run(String className, Path classes, String[] args) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, JavaRunner.class.getClassLoader())) {
            Method main = loader.loadClass(className).getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                // Rethrow what main itself threw, not the reflection wrapper.
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        }
    }

    // The folder or jar that linq.Query was loaded from.
    private static String libraryLocation() {
        try {
            return Path.of(Query.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
