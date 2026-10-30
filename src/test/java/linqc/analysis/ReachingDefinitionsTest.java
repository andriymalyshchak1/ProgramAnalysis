package linqc.analysis;

import static linqc.analysis.AnalysisTestSupport.graph;
import static linqc.analysis.AnalysisTestSupport.names;
import static linqc.analysis.AnalysisTestSupport.node;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import linqc.analysis.ReachingDefinitions.Definition;
import org.junit.jupiter.api.Test;

class ReachingDefinitionsTest {

    // Where each definition of `name` reaching the node was made.
    private static List<String> reaching(ControlFlowGraph g, String node, String name) {
        return ReachingDefinitions.of(g).reaching(node(g, node), name).stream().map(d -> d.node().toString()).toList();
    }

    @Test
    void aLaterAssignmentReplacesAnEarlierOne() throws Exception {
        ControlFlowGraph g = graph("int a = 1; a = 2; int b = a;");
        assertEquals(List.of("a=2"), reaching(g, "int b = a", "a"));
    }

    @Test
    void assignmentsFromBothBranchesReachTheJoin() throws Exception {
        ControlFlowGraph g = graph("int a; if (p > 0) a = 1; else a = 2; int b = a;");
        assertEquals(List.of("a=1", "a=2"), reaching(g, "int b = a", "a"));
    }

    @Test
    void anAssignmentInALoopReachesTheConditionAgain() throws Exception {
        ControlFlowGraph g = graph("int a = 0; while (a < p) a = a + 1;");
        assertEquals(List.of("inta=0", "a=a+1"), reaching(g, "a < p", "a"));
    }

    @Test
    void anAssignmentThatMightNotRunDoesNotReplaceTheEarlierOne() throws Exception {
        ControlFlowGraph g = graph("int a = 0; boolean b = p > 0 && (a = 1) > 0; int c = a;");
        assertEquals(List.of("inta=0", "booleanb=p>0&&(a=1)>0"), reaching(g, "int c = a", "a"));
    }

    @Test
    void parametersAreDefinedOnEntry() throws Exception {
        ControlFlowGraph g = graph("int b = p;");
        assertEquals(List.of("entry"), reaching(g, "int b = p", "p"));
    }

    @Test
    void reachedByListsWhereTheValueMayStillBeUsed() throws Exception {
        ControlFlowGraph g = graph("int a = 1; int b = a; a = 2; int c = a;");
        ReachingDefinitions rd = ReachingDefinitions.of(g);
        Definition first = rd.reaching(node(g, "int b = a"), "a").get(0);
        assertEquals(Set.of("intb=a", "a=2"), names(rd.reachedBy(first)));
    }
}
