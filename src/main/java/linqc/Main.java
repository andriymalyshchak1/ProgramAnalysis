package linqc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command line: translate .jlinq files to Java, run one, or report which queries can be merged. */
public final class Main {

    private static final String USAGE = """
            usage: linqc FILE.jlinq...          print the translated Java
                   linqc -d DIR FILE.jlinq...   write DIR/Name.java for each file
                   linqc --run FILE.jlinq       translate, compile and run its main method
                   linqc --report FILE.jlinq... list queries that filter another query, and whether they can be merged
            Add --merge to merge those queries before translating (or before running).""";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int status = run(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args) throws Exception {
        boolean execute = false;
        boolean report = false;
        boolean merge = false;
        Path outDir = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run" -> execute = true;
                case "--report" -> report = true;
                case "--merge" -> merge = true;
                case "-d" -> {
                    if (++i == args.length) {
                        return usage();
                    }
                    outDir = Path.of(args[i]);
                }
                default -> files.add(Path.of(args[i]));
            }
        }
        if (files.isEmpty() || (execute && (files.size() > 1 || outDir != null)) || (report && (execute || merge || outDir != null))) {
            return usage();
        }

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String className = fileName.endsWith(".jlinq") ? fileName.substring(0, fileName.length() - ".jlinq".length()) : fileName;
            try {
                String source = Files.readString(file);
                if (report) {
                    List<QueryMerger.Finding> findings = QueryMerger.analyze(source, fileName);
                    if (findings.isEmpty()) {
                        System.out.println(fileName + ": no query reads a variable that holds a query");
                    }
                    findings.forEach(System.out::println);
                    continue;
                }
                if (merge) {
                    source = QueryMerger.merge(source, fileName);
                }
                String java = Translator.translate(source, fileName);
                if (execute) {
                    JavaRunner.compileAndRun(className, java, fileName);
                } else if (outDir != null) {
                    Files.createDirectories(outDir);
                    Files.writeString(outDir.resolve(className + ".java"), java);
                } else {
                    System.out.print(java);
                }
            } catch (TranslationException | JavaRunner.CompileError e) {
                System.err.println(e.getMessage());
                return 1;
            } catch (NoSuchFileException e) {
                System.err.println(file + ": no such file");
                return 1;
            } catch (IOException e) {
                System.err.println(file + ": " + e.getMessage());
                return 1;
            }
        }
        return 0;
    }

    private static int usage() {
        System.err.println(USAGE);
        return 2;
    }
}
