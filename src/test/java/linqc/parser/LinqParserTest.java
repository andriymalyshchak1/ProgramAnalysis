package linqc.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

class LinqParserTest {

    private static LinqParser parserFor(String source) {
        LinqLexer lexer = new LinqLexer(CharStreams.fromString(source));
        return new LinqParser(new CommonTokenStream(lexer));
    }

    @Test
    void parsesFromSelect() {
        LinqParser parser = parserFor("from p in people select p");
        LinqParser.QueryContext query = parser.query();

        assertEquals(0, parser.getNumberOfSyntaxErrors());
        assertEquals("p", query.ID(0).getText());
        assertEquals("people", query.ID(1).getText());
        assertEquals("p", query.ID(2).getText());
    }

    @Test
    void reportsMissingSelect() {
        LinqParser parser = parserFor("from p in people");
        parser.removeErrorListeners(); // keep the expected error out of the test output
        parser.query();

        assertTrue(parser.getNumberOfSyntaxErrors() > 0);
    }
}
