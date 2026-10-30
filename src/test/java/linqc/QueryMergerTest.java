package linqc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import linqc.QueryMerger.Finding;
import org.junit.jupiter.api.Test;

class QueryMergerTest {

    // The statements go in m(xs, limit) of a small class, starting on line 4.
    private static String method(String statements) {
        return "import java.util.List;\n"
                + "class T {\n"
                + "    void m(List<Integer> xs, int limit) {\n"
                + statements.indent(8)
                + "    }\n"
                + "}\n";
    }

    private static Finding onlyFinding(String source) {
        List<Finding> findings = QueryMerger.analyze(source, "T.jlinq");
        assertEquals(1, findings.size(), findings.toString());
        return findings.get(0);
    }

    private static void assertRefused(String statements, String reason) {
        Finding finding = onlyFinding(method(statements));
        assertFalse(finding.mergeable(), finding.toString());
        assertEquals(reason, finding.detail());
    }

    private static void assertMergedInto(String statements, String firstQueryBecomes) {
        Finding finding = onlyFinding(method(statements));
        assertTrue(finding.mergeable(), finding.toString());
        assertEquals(firstQueryBecomes, finding.detail());
    }

    // ---- Merges -----------------------------------------------------------------------------

    @Test
    void theFiltersEndUpInOneWhereClause() {
        String merged = QueryMerger.merge(method("""
                var a = from x in xs where x > 0 select x;
                var b = from y in a where y < limit select y;
                System.out.println(b.toList());"""), "T.jlinq");
        assertEquals(method("""
                var a = from x in xs where x > 0 && x < limit select x;
                var b = linq.Query.from(a) /* filter merged into the query on line 4 */;
                System.out.println(b.toList());"""), merged);
    }

    @Test
    void theFilterGoesBeforeAnOrderby() {
        assertMergedInto("""
                var a = from x in xs orderby x descending select x;
                var b = from y in a where y > 1 select y;""",
                "from x in xs where x > 1 orderby x descending select x");
    }

    @Test
    void parenthesesAreAddedWhereNeeded() {
        assertMergedInto("""
                var a = from x in xs where x < 0 || x > 9 select x;
                var b = from y in a where y % 2 == 0 || y == limit select y;""",
                "from x in xs where (x < 0 || x > 9) && (x % 2 == 0 || x == limit) select x");
    }

    @Test
    void intoIsUsedWhenTheFirstQueryChangesTheElements() {
        assertMergedInto("""
                var a = from x in xs select x * 2;
                var b = from y in a where y > 3 select y;""",
                "from x in xs select x * 2 into y where y > 3 select y");
        assertMergedInto("""
                var a = from x in xs group x by x % 3;
                var b = from g in a where g.count() > 1 select g;""",
                "from x in xs group x by x % 3 into g where g.count() > 1 select g");
    }

    @Test
    void aChainOfFiltersBecomesOneQuery() {
        String source = method("""
                var a = from x in xs where x > 0 select x;
                var b = from y in a where y < 100 select y;
                var c = from z in b where z != limit select z;
                System.out.println(c.toList());""");
        assertEquals(2, QueryMerger.analyze(source, "T.jlinq").stream().filter(Finding::mergeable).count());
        assertTrue(QueryMerger.merge(source, "T.jlinq").contains("var a = from x in xs where x > 0 && x < 100 && x != limit select x;"));
    }

    @Test
    void itMergesEvenIfTheFirstQuerysSourceChangesInBetween() {
        // The first query read the old list; the merge leaves the first query where it is.
        assertMergedInto("""
                List<Integer> source = xs;
                var a = from x in source where x > 0 select x;
                source = List.of();
                var b = from y in a where y < limit select y;""",
                "from x in source where x > 0 && x < limit select x");
    }

    @Test
    void itMergesIntoALoopWhenTheFilterOnlyUsesEarlierNames() {
        assertMergedInto("""
                var a = from x in xs where x > 0 select x;
                for (int i = 0; i < 3; i++) {
                    var b = from y in a where y < limit select y;
                }""",
                "from x in xs where x > 0 && x < limit select x");
    }

    // ---- Refusals: one per condition ------------------------------------------------------------

    @Test
    void refusedWhenTheSecondQueryDoesMoreThanFilter() {
        assertRefused("""
                var a = from x in xs select x;
                var b = from y in a where y > 0 orderby y select y;""",
                "it does more than filter (it has a 'orderby' clause)");
        assertRefused("""
                var a = from x in xs select x;
                var b = from y in a where y > 0 select y + 1;""",
                "it does more than filter (its select changes the elements)");
    }

    @Test
    void refusedWhenTheSecondQueryIsInsideALambda() {
        assertRefused("""
                var a = from x in xs select x;
                Runnable r = () -> System.out.println(from y in a where y > 0 select y);""",
                "it is inside a lambda or another query");
    }

    @Test
    void refusedWhenTheMethodHasAStatementTheGraphDoesNotModel() {
        assertRefused("""
                var a = from x in xs select x;
                var b = from y in a where y > 0 select y;
                try { System.out.println(b); } finally { }""",
                "its method can't be analyzed: 'try' statements aren't supported (line 6)");
    }

    @Test
    void refusedWhenEitherOfTwoQueriesCanReachIt() {
        assertRefused("""
                linq.Query<Integer> a;
                if (limit > 0) a = from x in xs where x > 0 select x;
                else a = from x in xs where x < 0 select x;
                var b = from y in a where y != 0 select y;""",
                "'a' may hold the value assigned on line 5 or line 6 here");
    }

    @Test
    void refusedWhenTheFirstQueryHasAlreadyRun() {
        assertRefused("""
                var a = (from x in xs where x > 0 select x).toList();
                var b = from y in a where y < limit select y;""",
                "'a' holds the result of toList() on the query on line 4, not the query itself");
    }

    @Test
    void refusedWhenTheVariableIsUsedAgain() {
        assertRefused("""
                var a = from x in xs where x > 0 select x;
                var b = from y in a where y < limit select y;
                System.out.println(a.count());""",
                "'a' is also used on line 6");
    }

    @Test
    void refusedWhenTheVariableIsPassedToAMethod() {
        assertRefused("""
                var a = from x in xs where x > 0 select x;
                System.out.println(a);
                var b = from y in a where y < limit select y;""",
                "'a' is also used on line 5");
    }

    @Test
    void refusedWhenTheFilterUsesALoopVariable() {
        assertRefused("""
                var a = from x in xs where x > 0 select x;
                for (int t : xs) {
                    var b = from y in a where y > t select y;
                }""",
                "the filter uses 't', which isn't declared yet on line 4");
    }

    @Test
    void refusedWhenTheFilterUsesALocalDeclaredInBetween() {
        assertRefused("""
                var a = from x in xs where x > 0 select x;
                int min = 2;
                var b = from y in a where y > min select y;""",
                "the filter uses 'min', which isn't declared yet on line 4");
    }

    @Test
    void refusedWhenANameMeansSomethingElseAtTheFirstQuery() {
        // At the first query, n is the local; at the second it is the field.
        Finding finding = onlyFinding("""
                class T {
                    int n = 5;
                    void m(java.util.List<Integer> xs) {
                        linq.Query<Integer> a;
                        { int n = 1; a = from x in xs where x > n select x; }
                        var b = from y in a where y < n select y;
                    }
                }
                """);
        assertFalse(finding.mergeable());
        assertEquals("'n' means a different variable on line 5", finding.detail());
    }

    @Test
    void refusedWhenTheFilterUsesAPatternVariable() {
        assertRefused("""
                Object o = limit;
                var a = from x in xs select x;
                if (o instanceof Integer k) {
                    var b = from y in a where y > k select y;
                }""",
                "'k' is a pattern variable, whose scope the analysis doesn't track");
    }

    @Test
    void refusedWhenIntoWouldReuseAVariableName() {
        assertRefused("""
                linq.Query<Integer> a;
                { int y = 0; a = from x in xs select x * 2; }
                var b = from y in a where y > 1 select y;""",
                "'y' is already a variable on line 5");
    }

    @Test
    void renamingIsAvoidedWhenItWouldCaptureAName() {
        // Renaming y to x would make the field x refer to the query variable, so "into" is
        // used instead; but into would turn an OrderedQuery into a plain Query.
        String source = """
                class T {
                    int x = 1;
                    Object m(java.util.List<Integer> xs) {
                        var a = from x in xs select x;
                        var b = from y in a where y > x select y;
                        linq.OrderedQuery<Integer> c = from x in xs orderby x select x;
                        var d = from y in c where y > x select y;
                        return b.count() + d.count();
                    }
                }
                """;
        List<Finding> findings = QueryMerger.analyze(source, "T.jlinq");
        assertEquals("from x in xs select x into y where y > x select y", findings.get(0).detail());
        assertEquals("'c' is declared as an OrderedQuery, and a filter after the sort makes a plain Query", findings.get(1).detail());
    }

    @Test
    void noFindingsWhenNoVariableHoldsAQuery() {
        assertEquals(List.of(), QueryMerger.analyze(method("var b = from y in xs where y > 0 select y;"), "T.jlinq"));
    }

    @Test
    void theMergingExampleHasFiveMergesAndTwoRefusals() throws Exception {
        List<Finding> findings = QueryMerger.analyze(Files.readString(Path.of("examples/Merging.jlinq")), "Merging.jlinq");
        assertEquals(List.of("adults", "byAge", "notTeens", "young", "seniors"),
                findings.stream().filter(Finding::mergeable).map(Finding::variable).toList());
        assertEquals(List.of("dallas", "everyone"),
                findings.stream().filter(f -> !f.mergeable()).map(Finding::variable).toList());
    }
}
