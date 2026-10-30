package linqc;

import java.util.ArrayList;
import java.util.List;
import linqc.parser.JLinqLexer;
import linqc.parser.JLinqParser;
import linqc.parser.JLinqParser.CompilationunitContext;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.Interval;

/** A parsed .jlinq file: its tokens (whitespace and comments included) and its parse tree. */
public record SourceFile(String name, CommonTokenStream tokens, CompilationunitContext tree) {

    /** @throws TranslationException listing every syntax error as file:line:column */
    public static SourceFile parse(String source, String name) {
        JLinqLexer lexer = new JLinqLexer(CharStreams.fromString(source, name));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        JLinqParser parser = new JLinqParser(tokens);
        SyntaxErrors errors = new SyntaxErrors(name);
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);

        CompilationunitContext tree = parser.compilationunit();
        // ANTLR still returns a (partial) tree after syntax errors; don't work on that.
        errors.throwIfAny();
        return new SourceFile(name, tokens, tree);
    }

    /** The source text of a node exactly as written, whitespace and comments included. */
    public String text(ParserRuleContext ctx) {
        return tokens.getText(Interval.of(ctx.start.getTokenIndex(), ctx.stop.getTokenIndex()));
    }

    static int newlines(String text) {
        return (int) text.chars().filter(c -> c == '\n').count();
    }

    /** Collects syntax errors as "file:line:column: message", columns counted from 1. */
    private static final class SyntaxErrors extends BaseErrorListener {

        private final String sourceName;
        private final List<String> messages = new ArrayList<>();

        SyntaxErrors(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            messages.add(String.format("%s:%d:%d: %s", sourceName, line, charPositionInLine + 1, msg));
        }

        void throwIfAny() {
            if (!messages.isEmpty()) {
                throw new TranslationException(String.join("\n", messages));
            }
        }
    }
}
