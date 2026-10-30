package linqc.analysis;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import linqc.analysis.ControlFlowGraph.Node;
import org.antlr.v4.runtime.Token;

/**
 * Reaching definitions, the classic data-flow analysis: for each node, which assignments
 * ("definitions") to each local may still be the variable's value when the node starts.
 *
 * <pre>
 *   gen[n]  = the definitions made at n
 *   kill[n] = the other definitions of every variable n definitely assigns
 *   in[n]   = union of out[p] over the predecessors p of n
 *   out[n]  = gen[n] + (in[n] - kill[n])
 * </pre>
 *
 * The equations are solved by iterating until nothing changes. "May" means along some path:
 * a definition reaches a node if there is a path from it to the node without another definite
 * assignment to the same variable.
 */
public final class ReachingDefinitions {

    /** One assignment: {@code name} is assigned at {@code node}; {@code token} is the assigned name. */
    public record Definition(int index, Node node, String name, Token token) {
        public int line() {
            return token.getLine();
        }
    }

    private final List<Definition> definitions = new ArrayList<>();
    private final Map<Node, BitSet> in = new HashMap<>();

    public static ReachingDefinitions of(ControlFlowGraph graph) {
        return new ReachingDefinitions(graph);
    }

    private ReachingDefinitions(ControlFlowGraph graph) {
        Map<Node, BitSet> gen = new HashMap<>();
        Map<String, BitSet> byName = new HashMap<>();
        for (Node node : graph.nodes()) {
            BitSet made = new BitSet();
            node.defs().forEach((name, token) -> {
                Definition definition = new Definition(definitions.size(), node, name, token);
                definitions.add(definition);
                made.set(definition.index());
                byName.computeIfAbsent(name, unused -> new BitSet()).set(definition.index());
            });
            gen.put(node, made);
        }

        Map<Node, BitSet> kill = new HashMap<>();
        Map<Node, List<Node>> predecessors = new HashMap<>();
        for (Node node : graph.nodes()) {
            BitSet killed = new BitSet();
            for (String name : node.defs().keySet()) {
                if (node.definitelyAssigns(name)) {
                    killed.or(byName.get(name));
                }
            }
            killed.andNot(gen.get(node));
            kill.put(node, killed);
            predecessors.put(node, new ArrayList<>());
        }
        for (Node node : graph.nodes()) {
            for (Node successor : node.successors()) {
                predecessors.get(successor).add(node);
            }
        }

        Map<Node, BitSet> out = new HashMap<>();
        for (Node node : graph.nodes()) {
            in.put(node, new BitSet());
            out.put(node, (BitSet) gen.get(node).clone());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Node node : graph.nodes()) {
                BitSet reachingIn = new BitSet();
                for (Node predecessor : predecessors.get(node)) {
                    reachingIn.or(out.get(predecessor));
                }
                BitSet reachingOut = (BitSet) reachingIn.clone();
                reachingOut.andNot(kill.get(node));
                reachingOut.or(gen.get(node));
                in.put(node, reachingIn);
                if (!reachingOut.equals(out.get(node))) {
                    out.put(node, reachingOut);
                    changed = true;
                }
            }
        }
    }

    /** The definitions of {@code name} that may reach the start of {@code node}. */
    public List<Definition> reaching(Node node, String name) {
        List<Definition> found = new ArrayList<>();
        in.get(node).stream().mapToObj(definitions::get).filter(d -> d.name().equals(name)).forEach(found::add);
        return found;
    }

    /** The nodes that {@code definition} may reach, i.e. where the variable may still hold its value. */
    public List<Node> reachedBy(Definition definition) {
        List<Node> found = new ArrayList<>();
        in.forEach((node, reaching) -> {
            if (reaching.get(definition.index())) {
                found.add(node);
            }
        });
        found.sort((a, b) -> Integer.compare(a.ctx() == null ? -1 : a.ctx().start.getTokenIndex(),
                                             b.ctx() == null ? -1 : b.ctx().start.getTokenIndex()));
        return found;
    }
}
