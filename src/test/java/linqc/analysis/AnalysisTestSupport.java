package linqc.analysis;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import linqc.analysis.ControlFlowGraph.Node;
import linqc.parser.JLinqLexer;
import linqc.parser.JLinqParser;
import linqc.parser.JLinqParser.MethodDeclarationContext;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

final class AnalysisTestSupport {

    private AnalysisTestSupport() {
    }

    /** The graph for {@code void m(int p) { statements }}. */
    static ControlFlowGraph graph(String statements) throws ControlFlowGraph.UnsupportedException {
        String source = "class T {\n    void m(int p) {\n" + statements + "\n    }\n}\n";
        JLinqParser parser = new JLinqParser(new CommonTokenStream(new JLinqLexer(CharStreams.fromString(source))));
        MethodDeclarationContext method = find(parser.compilationunit(), MethodDeclarationContext.class);
        return ControlFlowGraph.of(method.formalParameters(), method.methodBody().block());
    }

    /** The node for a statement or condition, written as in the source; spaces are ignored. */
    static Node node(ControlFlowGraph graph, String code) {
        String wanted = code.replace(" ", "");
        for (Node node : graph.nodes()) {
            if (node.ctx() != null && node.ctx().getText().equals(wanted)) {
                return node;
            }
        }
        return fail("no node for " + code + " in " + graph.nodes());
    }

    // As a set: the order of a node's successors means nothing.
    static Set<String> names(List<Node> nodes) {
        return nodes.stream().map(Node::toString).collect(Collectors.toSet());
    }

    private static <T extends ParseTree> T find(ParseTree tree, Class<T> type) {
        if (type.isInstance(tree)) {
            return type.cast(tree);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            T found = find(tree.getChild(i), type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
