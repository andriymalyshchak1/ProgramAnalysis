package linqc.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import linqc.parser.JLinqParser.QueryBodyClauseContext;
import linqc.parser.JLinqParser.QueryExpressionContext;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

class QueryParsingTest {

    private static JLinqParser parserFor(String source) {
        JLinqParser parser = new JLinqParser(new CommonTokenStream(new JLinqLexer(CharStreams.fromString(source))));
        parser.removeErrorListeners(); // tests check getNumberOfSyntaxErrors() instead of printing
        return parser;
    }

    // Parses a query on its own by starting at the queryExpression rule.
    private static QueryExpressionContext parseQuery(String query) {
        JLinqParser parser = parserFor(query);
        QueryExpressionContext ctx = parser.queryExpression();
        assertEquals(0, parser.getNumberOfSyntaxErrors(), query);
        assertEquals(Token.EOF, parser.getTokenStream().LA(1), "the whole query should be consumed: " + query);
        return ctx;
    }

    private static int countQueries(ParseTree tree) {
        int count = tree instanceof QueryExpressionContext ? 1 : 0;
        for (int i = 0; i < tree.getChildCount(); i++) {
            count += countQueries(tree.getChild(i));
        }
        return count;
    }

    @Test
    void parsesAQueryIntoItsClauses() {
        QueryExpressionContext query = parseQuery(
                "from p in people where p.age() >= 18 orderby p.name() descending select p.name()");

        assertEquals("p", query.fromClause().identifier().getText());
        assertEquals("people", query.fromClause().expression().getText());
        List<QueryBodyClauseContext> clauses = query.queryBody().queryBodyClause();
        assertEquals(2, clauses.size());
        assertEquals("p.age()>=18", clauses.get(0).whereClause().expression().getText());
        assertEquals(JLinqLexer.DESCENDING, clauses.get(1).orderbyClause().ordering(0).direction.getType());
        assertInstanceOf(JLinqParser.SelectClauseContext.class, query.queryBody().selectOrGroupClause());
    }

    @Test
    void theGrammarCoversEveryClauseKind() {
        // The translator doesn't support let, join or a second from yet, but they all parse.
        QueryExpressionContext query = parseQuery("""
                from p in people
                from pet in p.pets()
                let n = p.name()
                join c in cities on p.city() equals c.name()
                where c.big()
                orderby n ascending, c.name() descending
                group n by c.state() into g
                select g.key()""");
        assertNotNull(query.queryBody().queryContinuation());
        assertInstanceOf(JLinqParser.GroupClauseContext.class, query.queryBody().selectOrGroupClause());
    }

    @Test
    void selectExtendsAsFarRightAsItCan() {
        // Like a lambda body: this is select (p.a() + 1), not (query) + 1.
        QueryExpressionContext query = parseQuery("from p in people select p.a() + 1");
        JLinqParser.SelectClauseContext select = (JLinqParser.SelectClauseContext) query.queryBody().selectOrGroupClause();
        assertEquals("p.a()+1", select.expression().getText());
    }

    @Test
    void queryKeywordsAreStillOrdinaryNamesInJava() {
        JLinqParser parser = parserFor("""
                class K {
                    String select = "s", where = "w", from = "f", in = "i", group = "g";
                    void by(int into) {
                        Object q = linq.Query.from(java.util.List.of(1));
                        java.io.InputStream input = System.in;
                        boolean same = select.equals(where);
                        group(from, in);
                    }
                    void group(String a, String b) {}
                }""");
        ParseTree tree = parser.compilationunit();
        assertEquals(0, parser.getNumberOfSyntaxErrors());
        assertEquals(0, countQueries(tree));
    }

    @Test
    void queriesParseInsideJava() {
        JLinqParser parser = parserFor("""
                class K {
                    void m() {
                        var a = from x in xs select x;
                        foo(from y in ys where y > 0 select y, 2);
                        var b = (from z in zs select z).count();
                    }
                }""");
        ParseTree tree = parser.compilationunit();
        assertEquals(0, parser.getNumberOfSyntaxErrors());
        assertEquals(3, countQueries(tree));
    }

    @Test
    void aQueryWithoutSelectIsASyntaxError() {
        JLinqParser parser = parserFor("from p in people where p.age() > 3");
        parser.queryExpression();
        assertTrue(parser.getNumberOfSyntaxErrors() > 0);
    }
}
