package linqc.analysis;

import static linqc.analysis.AnalysisTestSupport.graph;
import static linqc.analysis.AnalysisTestSupport.names;
import static linqc.analysis.AnalysisTestSupport.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import linqc.analysis.ControlFlowGraph.Node;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

class ControlFlowGraphTest {

    private static List<String> uses(Node node) {
        return node.uses().stream().map(Token::getText).toList();
    }

    @Test
    void straightLineCode() throws Exception {
        ControlFlowGraph g = graph("int a = 1; a += p; int b = a;");
        assertEquals(Set.of("inta=1"), names(g.entry().successors()));
        assertEquals(Set.of("a+=p"), names(node(g, "int a = 1").successors()));
        assertEquals(Set.of("exit"), names(node(g, "int b = a").successors()));

        assertEquals(Set.of("a"), node(g, "a += p").defs().keySet());
        assertEquals(List.of("a", "p"), uses(node(g, "a += p"))); // += reads a as well
        assertEquals(Set.of("b"), node(g, "int b = a").defs().keySet());
        assertEquals(List.of("a"), uses(node(g, "int b = a")));
    }

    @Test
    void theTargetOfAPlainAssignmentIsNotARead() throws Exception {
        ControlFlowGraph g = graph("int a; a = p;");
        assertEquals(List.of("p"), uses(node(g, "a = p")));
        assertTrue(node(g, "a = p").definitelyAssigns("a"));
    }

    @Test
    void ifElseBranchesJoinAfterward() throws Exception {
        ControlFlowGraph g = graph("if (p > 0) p = 1; else p = 2; int c = p;");
        assertEquals(Set.of("p=1", "p=2"), names(node(g, "p > 0").successors()));
        assertEquals(Set.of("intc=p"), names(node(g, "p = 1").successors()));
        assertEquals(Set.of("intc=p"), names(node(g, "p = 2").successors()));
    }

    @Test
    void ifWithoutElseCanSkipTheBranch() throws Exception {
        ControlFlowGraph g = graph("if (p > 0) p = 1; int c = p;");
        assertEquals(Set.of("p=1", "intc=p"), names(node(g, "p > 0").successors()));
    }

    @Test
    void whileLoopsBackAndBreakAndContinueJump() throws Exception {
        ControlFlowGraph g = graph("while (p > 0) { p--; if (p == 5) break; if (p == 7) continue; p = p - 1; } return;");
        assertEquals(Set.of("p--", "return;"), names(node(g, "p > 0").successors()));
        assertEquals(Set.of("return;", "p==7"), names(node(g, "p == 5").successors()));     // break leaves the loop
        assertEquals(Set.of("p>0", "p=p-1"), names(node(g, "p == 7").successors()));        // continue goes to the condition
        assertEquals(Set.of("p>0"), names(node(g, "p = p - 1").successors()));
        assertEquals(Set.of("exit"), names(node(g, "return;").successors()));
    }

    @Test
    void classicForLoop() throws Exception {
        ControlFlowGraph g = graph("for (int i = 0; i < p; i++) p--;");
        assertEquals(Set.of("i<p"), names(node(g, "int i = 0").successors()));
        assertEquals(Set.of("p--", "exit"), names(node(g, "i < p").successors()));
        assertEquals(Set.of("i++"), names(node(g, "p--").successors()));
        assertEquals(Set.of("i<p"), names(node(g, "i++").successors()));
    }

    @Test
    void enhancedForLoopAssignsTheVariableEachTime() throws Exception {
        ControlFlowGraph g = graph("int[] xs = {1}; for (int x : xs) p += x;");
        Node next = node(g, "x");
        assertTrue(next.definitelyAssigns("x"));
        assertEquals(Set.of("p+=x", "exit"), names(next.successors()));
        assertEquals(Set.of("x"), names(node(g, "p += x").successors()));
    }

    @Test
    void anAssignmentThatMightNotRunIsNotDefinite() throws Exception {
        ControlFlowGraph g = graph("int a = 0; int b = p > 0 ? (a = 1) : 2; boolean c = p > 0 && (a = 3) > 0;");
        Node ternary = node(g, "int b = p > 0 ? (a = 1) : 2");
        assertEquals(Set.of("b", "a"), ternary.defs().keySet());
        assertTrue(ternary.definitelyAssigns("b"));
        assertFalse(ternary.definitelyAssigns("a"));
        assertFalse(node(g, "boolean c = p > 0 && (a = 3) > 0").definitelyAssigns("a"));
    }

    @Test
    void lambdaBodiesCanReadButNotAssignTheMethodsLocals() throws Exception {
        ControlFlowGraph g = graph("Runnable r = () -> { int q = p; };");
        Node node = node(g, "Runnable r = () -> { int q = p; }");
        assertEquals(Set.of("r"), node.defs().keySet());
        assertEquals(List.of("p"), uses(node));
    }

    @Test
    void queryClausesAreReadsOnly() throws Exception {
        ControlFlowGraph g = graph("var q = from x in xs where x > p select x;");
        assertEquals(List.of("xs", "x", "p", "x"), uses(node(g, "var q = from x in xs where x > p select x")));
    }

    @Test
    void eachNodeKnowsTheLocalsInScope() throws Exception {
        ControlFlowGraph g = graph("int a = 1; { int b = 2; } int c = 3;");
        assertEquals(Set.of("p"), node(g, "int a = 1").scope().keySet());
        assertEquals(Set.of("p", "a"), node(g, "int b = 2").scope().keySet());
        assertEquals(Set.of("p", "a"), node(g, "int c = 3").scope().keySet()); // b's block has ended
    }

    @Test
    void unsupportedStatementsAreRefused() {
        var tryStatement = assertThrows(ControlFlowGraph.UnsupportedException.class,
                () -> graph("try { p = 1; } finally { p = 2; }"));
        assertEquals("'try' statements aren't supported (line 3)", tryStatement.getMessage());
        assertThrows(ControlFlowGraph.UnsupportedException.class, () -> graph("switch (p) { case 1: p = 2; }"));
        assertThrows(ControlFlowGraph.UnsupportedException.class, () -> graph("outer: while (p > 0) { break outer; }"));
    }
}
