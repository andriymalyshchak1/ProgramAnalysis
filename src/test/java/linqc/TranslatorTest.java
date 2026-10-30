package linqc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TranslatorTest {

    // Translates a single query by placing it in a minimal class.
    private static String translateQuery(String query) {
        String before = "class T { Object q = ";
        String after = "; }";
        String java = Translator.translate(before + query + after, "T.jlinq");
        assertTrue(java.startsWith(before) && java.endsWith(after), java);
        return java.substring(before.length(), java.length() - after.length());
    }

    @Test
    void select() {
        assertEquals("linq.Query.from(people).select(p -> p.name())",
                translateQuery("from p in people select p.name()"));
    }

    @Test
    void whereDropsASelectThatChangesNothing() {
        assertEquals("linq.Query.from(people).where(p -> p.age() >= 18)",
                translateQuery("from p in people where p.age() >= 18 select p"));
        assertEquals("linq.Query.from(people)", translateQuery("from p in people select p"));
    }

    @Test
    void orderbyKeysBecomeOrderByThenThenBy() {
        assertEquals("linq.Query.from(people).orderByDescending(p -> p.age()).thenBy(p -> p.name())",
                translateQuery("from p in people orderby p.age() descending, p.name() select p"));
        assertEquals("linq.Query.from(people).orderBy(p -> p.age()).thenByDescending(p -> p.name())",
                translateQuery("from p in people orderby p.age() ascending, p.name() descending select p"));
        // Each orderby clause starts a new sort, as in C#.
        assertEquals("linq.Query.from(people).orderBy(p -> p.age()).orderBy(p -> p.name())",
                translateQuery("from p in people orderby p.age() orderby p.name() select p"));
    }

    @Test
    void groupBy() {
        assertEquals("linq.Query.from(people).groupBy(p -> p.city())",
                translateQuery("from p in people group p by p.city()"));
        assertEquals("linq.Query.from(people).groupBy(p -> p.city(), p -> p.name())",
                translateQuery("from p in people group p.name() by p.city()"));
    }

    @Test
    void intoContinuesWithANewVariable() {
        assertEquals("linq.Query.from(people).groupBy(p -> p.city()).where(g -> g.count() > 1).select(g -> g.key())",
                translateQuery("from p in people group p by p.city() into g where g.count() > 1 select g.key()"));
    }

    @Test
    void nestedQueriesAreTranslatedToo() {
        assertEquals("linq.Query.from(people).select(p -> (linq.Query.from(people).where(q -> q.age() > p.age())).count())",
                translateQuery("from p in people select (from q in people where q.age() > p.age() select q).count()"));
        assertEquals("linq.Query.from((linq.Query.from(ys).where(y -> y > 0))).select(x -> x * 2)",
                translateQuery("from x in (from y in ys where y > 0 select y) select x * 2"));
    }

    @Test
    void expressionsAreCopiedAsWritten() {
        assertEquals("linq.Query.from(people).where(p -> p.age()  >=  18 && p.city().equals(\"Austin\"))",
                translateQuery("from p in people where p.age()  >=  18 && p.city().equals(\"Austin\") select p"));
    }

    @Test
    void codeOutsideQueriesIsCopiedExactly() {
        String java = """
                package demo;

                /* block comment */  import java.util.*;

                class   Odd {   // spacing and comments stay exactly as they are
                    String select = "select", from = "from", in = "in";
                    Object q = linq.Query.from(List.of(1)).where(x -> x > 0);
                    String text = \"""
                        from p in people select p
                        \""";
                }
                """;
        assertEquals(java, Translator.translate(java, "Odd.jlinq"));
    }

    @Test
    void eachClauseStaysOnItsLine() {
        String jlinq = """
                class T {
                    Object q = from p in people
                               where p.age() >= 18 // adults only
                               orderby p.name()
                               select p.name();
                    int after = 1;
                }
                """;
        String java = """
                class T {
                    Object q = linq.Query.from(people)
                               .where(p -> p.age() >= 18) // adults only
                               .orderBy(p -> p.name())
                               .select(p -> p.name());
                    int after = 1;
                }
                """;
        assertEquals(java, Translator.translate(jlinq, "T.jlinq"));
    }

    @Test
    void lineBreaksInsideAClauseAreKeptAtTheEnd() {
        // The break after "where" can't stay where it was, so it moves to the end of the
        // query. Either way, the line after the query is still line 4.
        String java = Translator.translate("class T {\n Object q = from p in ps where\n p.ok() select p;\n int after; }", "T.jlinq");
        assertEquals("class T {\n Object q = linq.Query.from(ps).where(p -> p.ok())\n;\n int after; }", java);
    }

    @Test
    void unsupportedClausesAreReportedWithTheirPosition() {
        TranslationException let = assertThrows(TranslationException.class,
                () -> translateQuery("from p in people let n = p.name() select n"));
        assertEquals("T.jlinq:1:39: 'let' clauses are not supported yet", let.getMessage());

        TranslationException join = assertThrows(TranslationException.class,
                () -> translateQuery("from p in people join c in cities on p.city() equals c.name() select p"));
        assertTrue(join.getMessage().contains("'join' clauses are not supported yet"), join.getMessage());

        TranslationException secondFrom = assertThrows(TranslationException.class,
                () -> translateQuery("from p in people from q in people select q"));
        assertTrue(secondFrom.getMessage().contains("a second 'from' clause is not supported yet"), secondFrom.getMessage());
    }

    @Test
    void syntaxErrorsAreReportedWithTheirPosition() {
        TranslationException error = assertThrows(TranslationException.class,
                () -> Translator.translate("class T {\n  Object q = from p in people where p.ok();\n}", "T.jlinq"));
        assertTrue(error.getMessage().startsWith("T.jlinq:2:"), error.getMessage());
    }
}
