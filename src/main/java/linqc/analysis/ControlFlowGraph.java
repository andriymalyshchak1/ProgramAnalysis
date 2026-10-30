package linqc.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import linqc.parser.JLinqLexer;
import linqc.parser.JLinqParser.BinaryOperatorExpressionContext;
import linqc.parser.JLinqParser.BlockContext;
import linqc.parser.JLinqParser.BlockStatementContext;
import linqc.parser.JLinqParser.ClassBodyContext;
import linqc.parser.JLinqParser.EnhancedForControlContext;
import linqc.parser.JLinqParser.ExpressionContext;
import linqc.parser.JLinqParser.ForControlContext;
import linqc.parser.JLinqParser.FormalParameterContext;
import linqc.parser.JLinqParser.FormalParametersContext;
import linqc.parser.JLinqParser.LambdaExpressionContext;
import linqc.parser.JLinqParser.LocalTypeDeclarationContext;
import linqc.parser.JLinqParser.LocalVariableDeclarationContext;
import linqc.parser.JLinqParser.PatternContext;
import linqc.parser.JLinqParser.PostIncrementDecrementOperatorExpressionContext;
import linqc.parser.JLinqParser.PrimaryContext;
import linqc.parser.JLinqParser.PrimaryExpressionContext;
import linqc.parser.JLinqParser.QueryExpressionContext;
import linqc.parser.JLinqParser.StatementContext;
import linqc.parser.JLinqParser.SwitchExpressionContext;
import linqc.parser.JLinqParser.TernaryExpressionContext;
import linqc.parser.JLinqParser.UnaryOperatorExpressionContext;
import linqc.parser.JLinqParser.VariableDeclaratorContext;
import linqc.parser.JLinqParser.VariableDeclaratorIdContext;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * Control-flow graph of one method body: a node for each simple statement (local variable
 * declaration, expression statement, return, throw) and for each condition of an if or a loop.
 * An edge from A to B means B can run right after A.
 *
 * <p>Each node also records, for data-flow analyses, the local variables it assigns ("defs"),
 * the local variables it reads ("uses"), and the local variables visible there ("scope").
 *
 * <p>An expression is never split up: a whole expression, including any lambda inside it, is one
 * node. Statements the graph doesn't model (try, switch, labeled break and continue, ...) make
 * {@link #of} throw, so an analysis can refuse rather than guess.
 */
public final class ControlFlowGraph {

    /** The method uses a statement the graph doesn't model. */
    public static final class UnsupportedException extends Exception {
        UnsupportedException(String message) {
            super(message);
        }
    }

    public static final class Node {
        private final int id;
        private final ParserRuleContext ctx;
        private final List<Node> successors = new ArrayList<>();
        private final Map<String, Token> defs = new LinkedHashMap<>();
        private final Set<String> definiteDefs = new HashSet<>();
        private final List<Token> uses = new ArrayList<>();
        private Map<String, Token> scope = Map.of();

        private Node(int id, ParserRuleContext ctx) {
            this.id = id;
            this.ctx = ctx;
        }

        /** The statement or condition, or null for entry, exit and the joins in loops. */
        public ParserRuleContext ctx() {
            return ctx;
        }

        public List<Node> successors() {
            return successors;
        }

        /** Locals this node assigns, each with the token of the assigned name. */
        public Map<String, Token> defs() {
            return defs;
        }

        /**
         * False if the assignment might not happen when the node runs, e.g. {@code c ? (a = 1) : 2}.
         * Only definite assignments replace ("kill") earlier ones.
         */
        public boolean definitelyAssigns(String name) {
            return definiteDefs.contains(name);
        }

        /** Every read of a simple name in this node, lambdas included, as identifier tokens. */
        public List<Token> uses() {
            return uses;
        }

        /** The locals visible at this node: name to the token that declares it. */
        public Map<String, Token> scope() {
            return scope;
        }

        @Override
        public String toString() {
            return ctx != null ? ctx.getText() : id == 0 ? "entry" : id == 1 ? "exit" : "join";
        }
    }

    private static final Set<String> ASSIGNMENT_OPERATORS =
            Set.of("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=");

    private final List<Node> nodes = new ArrayList<>();
    private final Map<ParserRuleContext, Node> byContext = new HashMap<>();
    private final Map<Token, String> declaredTypes = new HashMap<>();
    private final Set<String> patternVariables = new HashSet<>();
    private final Deque<Loop> loops = new ArrayDeque<>();
    private final Node entry;
    private final Node exit;

    // Where break and continue statements in the innermost loop jump; wired up once the loop is built.
    private static final class Loop {
        final List<Node> breaks = new ArrayList<>();
        final List<Node> continues = new ArrayList<>();
    }

    public static ControlFlowGraph of(FormalParametersContext parameters, BlockContext body) throws UnsupportedException {
        return new ControlFlowGraph(parameters, body);
    }

    private ControlFlowGraph(FormalParametersContext parameters, BlockContext body) throws UnsupportedException {
        entry = newNode(null);
        exit = newNode(null);
        // The parameters are assigned when the method is entered.
        Map<String, Token> scope = new HashMap<>();
        for (FormalParameterContext parameter : descendants(parameters, FormalParameterContext.class)) {
            Token name = parameter.variableDeclaratorId().identifier().start;
            entry.defs.put(name.getText(), name);
            entry.definiteDefs.add(name.getText());
            scope.put(name.getText(), name);
            declaredTypes.put(name, parameter.typeType().getText());
        }
        connect(block(body, List.of(entry), scope), exit);
    }

    public List<Node> nodes() {
        return nodes;
    }

    public Node entry() {
        return entry;
    }

    public Node exit() {
        return exit;
    }

    /** The node whose statement or condition contains {@code tree}, or null. */
    public Node nodeContaining(ParseTree tree) {
        for (ParseTree t = tree; t != null; t = t.getParent()) {
            Node node = byContext.get(t);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    /** The declared type of a local, e.g. "var" or "List<Integer>", given its declaring token. */
    public String declaredType(Token declaration) {
        return declaredTypes.getOrDefault(declaration, "");
    }

    /**
     * Names of pattern variables ({@code x instanceof Foo f}). Their scope depends on how the
     * condition is used, which the scope maps don't track, so analyses should avoid these names.
     */
    public Set<String> patternVariables() {
        return patternVariables;
    }

    /** The identifier token if {@code e} is just a name, like {@code people}; otherwise null. */
    public static Token simpleName(ExpressionContext e) {
        return e instanceof PrimaryExpressionContext p && p.primary().identifier() != null ? p.primary().identifier().start : null;
    }

    public static boolean isAssignment(BinaryOperatorExpressionContext e) {
        return e.bop != null && ASSIGNMENT_OPERATORS.contains(e.bop.getText());
    }

    // ---- Building the graph ---------------------------------------------------------------
    // Each method takes the "open" nodes (those that fall through into the code being added),
    // connects them to it, and returns the nodes that fall through out of it.

    private List<Node> block(BlockContext block, List<Node> open, Map<String, Token> outer) throws UnsupportedException {
        Map<String, Token> scope = new HashMap<>(outer);
        for (BlockStatementContext statement : block.blockStatement()) {
            if (statement.localVariableDeclaration() != null) {
                Node node = leaf(statement.localVariableDeclaration(), scope, false);
                connect(open, node);
                declare(statement.localVariableDeclaration(), scope);
                open = List.of(node);
            } else if (statement.localTypeDeclaration() != null) {
                Node node = leaf(statement.localTypeDeclaration(), scope, false);
                connect(open, node);
                open = List.of(node);
            } else {
                open = statement(statement.statement(), open, scope);
            }
        }
        return open;
    }

    private List<Node> statement(StatementContext s, List<Node> open, Map<String, Token> scope) throws UnsupportedException {
        if (s.blockLabel != null) {
            return block(s.blockLabel, open, scope);
        }
        if (s.IF() != null) {
            Node condition = leaf(s.expression(0), scope, false);
            connect(open, condition);
            List<Node> after = new ArrayList<>(statement(s.statement(0), List.of(condition), new HashMap<>(scope)));
            after.addAll(s.ELSE() != null ? statement(s.statement(1), List.of(condition), new HashMap<>(scope)) : List.of(condition));
            return after;
        }
        if (s.DO() != null) {
            Node start = newNode(null); // where the body starts, so the condition can jump back to it
            connect(open, start);
            Loop loop = enterLoop();
            List<Node> bodyEnd = statement(s.statement(0), List.of(start), new HashMap<>(scope));
            Node condition = leaf(s.expression(0), scope, false);
            connect(bodyEnd, condition);
            connect(loop.continues, condition);
            connect(List.of(condition), start);
            return exitLoop(List.of(condition));
        }
        if (s.WHILE() != null) {
            Node condition = leaf(s.expression(0), scope, false);
            connect(open, condition);
            Loop loop = enterLoop();
            List<Node> bodyEnd = statement(s.statement(0), List.of(condition), new HashMap<>(scope));
            connect(bodyEnd, condition);
            connect(loop.continues, condition);
            return exitLoop(List.of(condition));
        }
        if (s.FOR() != null) {
            return forStatement(s, open, scope);
        }
        if (s.SYNCHRONIZED() != null) {
            Node lock = leaf(s.expression(0), scope, false);
            connect(open, lock);
            return block(s.block(), List.of(lock), scope);
        }
        if (s.RETURN() != null || s.THROW() != null) {
            Node node = leaf(s, scope, false);
            connect(open, node);
            connect(List.of(node), exit);
            return List.of();
        }
        if (s.BREAK() != null || s.CONTINUE() != null) {
            String keyword = s.start.getText();
            if (s.identifier() != null) {
                throw new UnsupportedException("labeled '" + keyword + "' isn't supported (line " + s.start.getLine() + ")");
            }
            if (loops.isEmpty()) {
                throw new UnsupportedException("'" + keyword + "' outside a loop isn't supported (line " + s.start.getLine() + ")");
            }
            (s.BREAK() != null ? loops.peek().breaks : loops.peek().continues).addAll(open);
            return List.of();
        }
        if (s.statementExpression != null) {
            Node node = leaf(s.statementExpression, scope, false);
            connect(open, node);
            return List.of(node);
        }
        if (s.ASSERT() != null) {
            // Assertions can be switched off, so assignments inside one might not happen.
            Node node = leaf(s, scope, true);
            connect(open, node);
            return List.of(node);
        }
        if (s.getChildCount() == 1 && s.SEMI() != null) {
            return open; // the empty statement
        }
        String what = s.identifierLabel != null ? "labeled statements" : "'" + s.start.getText() + "' statements";
        throw new UnsupportedException(what + " aren't supported (line " + s.start.getLine() + ")");
    }

    private List<Node> forStatement(StatementContext s, List<Node> open, Map<String, Token> outer) throws UnsupportedException {
        ForControlContext control = s.forControl();
        Map<String, Token> scope = new HashMap<>(outer);
        if (control.enhancedForControl() != null) {
            // for (T x : source) body:  source -> next -> body -> next ..., next -> after the loop
            EnhancedForControlContext each = control.enhancedForControl();
            Node source = leaf(each.expression(), scope, false);
            connect(open, source);
            Token variable = each.variableDeclaratorId().identifier().start;
            Node next = newNode(each.variableDeclaratorId()); // assigns the next element to x
            next.defs.put(variable.getText(), variable);
            next.definiteDefs.add(variable.getText());
            next.scope = Map.copyOf(scope);
            connect(List.of(source), next);
            scope.put(variable.getText(), variable);
            declaredTypes.put(variable, each.typeType() != null ? each.typeType().getText() : "var");

            Loop loop = enterLoop();
            List<Node> bodyEnd = statement(s.statement(0), List.of(next), scope);
            connect(bodyEnd, next);
            connect(loop.continues, next);
            return exitLoop(List.of(next));
        }

        // for (init; condition; update) body
        List<Node> start = open;
        if (control.forInit() != null) {
            Node init = leaf(control.forInit(), scope, false);
            connect(open, init);
            start = List.of(init);
            if (control.forInit().localVariableDeclaration() != null) {
                declare(control.forInit().localVariableDeclaration(), scope);
            }
        }
        Node condition = control.expression() != null ? leaf(control.expression(), scope, false) : newNode(null);
        connect(start, condition);
        Loop loop = enterLoop();
        List<Node> bodyEnd = new ArrayList<>(statement(s.statement(0), List.of(condition), new HashMap<>(scope)));
        bodyEnd.addAll(loop.continues);
        if (control.forUpdate != null) {
            Node update = leaf(control.forUpdate, scope, false);
            connect(bodyEnd, update);
            connect(List.of(update), condition);
        } else {
            connect(bodyEnd, condition);
        }
        // Without a condition (for (;;)) the loop is only left through break.
        return exitLoop(control.expression() != null ? List.of(condition) : List.of());
    }

    private Loop enterLoop() {
        Loop loop = new Loop();
        loops.push(loop);
        return loop;
    }

    private List<Node> exitLoop(List<Node> normalExits) {
        List<Node> exits = new ArrayList<>(normalExits);
        exits.addAll(loops.pop().breaks);
        return exits;
    }

    private void declare(LocalVariableDeclarationContext declaration, Map<String, Token> scope) {
        if (declaration.VAR() != null) {
            Token name = declaration.identifier().start;
            scope.put(name.getText(), name);
            declaredTypes.put(name, "var");
            return;
        }
        for (VariableDeclaratorContext declarator : declaration.variableDeclarators().variableDeclarator()) {
            Token name = declarator.variableDeclaratorId().identifier().start;
            scope.put(name.getText(), name);
            declaredTypes.put(name, declaration.typeType().getText());
        }
    }

    private Node newNode(ParserRuleContext ctx) {
        Node node = new Node(nodes.size(), ctx);
        nodes.add(node);
        return node;
    }

    private Node leaf(ParserRuleContext ctx, Map<String, Token> scope, boolean conditional) {
        Node node = newNode(ctx);
        node.scope = Map.copyOf(scope);
        collect(ctx, node, conditional);
        byContext.put(ctx, node);
        return node;
    }

    private static void connect(List<Node> from, Node to) {
        for (Node node : from) {
            if (!node.successors.contains(to)) {
                node.successors.add(to);
            }
        }
    }

    // ---- Defs and uses --------------------------------------------------------------------

    /**
     * Records what a node assigns and reads. {@code conditional} is true inside parts of an
     * expression that might not run, such as the branches of {@code ?:} or the right side of
     * {@code &&}; assignments there are recorded but don't kill earlier ones.
     */
    private void collect(ParseTree t, Node node, boolean conditional) {
        if (t instanceof LambdaExpressionContext || t instanceof ClassBodyContext || t instanceof LocalTypeDeclarationContext) {
            // This code runs later, or not at all, and can't assign this method's locals (Java
            // only lets it capture effectively final ones). Its reads still count.
            collectUses(t, node, null);
            return;
        }
        if (t instanceof QueryExpressionContext query) {
            // Only the source (from x in SOURCE) runs now; every clause becomes a lambda.
            ExpressionContext source = query.fromClause().expression();
            collect(source, node, conditional);
            collectUses(query, node, source);
            return;
        }
        if (t instanceof PrimaryContext primary && primary.identifier() != null) {
            node.uses.add(primary.identifier().start);
            return;
        }
        if (t instanceof BinaryOperatorExpressionContext e && isAssignment(e) && simpleName(e.expression(0)) != null) {
            Token target = simpleName(e.expression(0));
            def(node, target, conditional);
            if (!e.bop.getText().equals("=")) {
                node.uses.add(target); // a += 1 reads a too
            }
            collect(e.expression(1), node, conditional);
            return;
        }
        ExpressionContext incremented = t instanceof PostIncrementDecrementOperatorExpressionContext e ? e.expression()
                : t instanceof UnaryOperatorExpressionContext e && (e.prefix.getType() == JLinqLexer.INC || e.prefix.getType() == JLinqLexer.DEC)
                        ? e.expression() : null;
        if (incremented != null && simpleName(incremented) != null) {
            def(node, simpleName(incremented), conditional);
            node.uses.add(simpleName(incremented));
            return;
        }
        if (t instanceof TernaryExpressionContext e) {
            collect(e.expression(0), node, conditional);
            collect(e.expression(1), node, true);
            collect(e.expression(2), node, true);
            return;
        }
        if (t instanceof BinaryOperatorExpressionContext e && e.bop != null
                && (e.bop.getType() == JLinqLexer.AND || e.bop.getType() == JLinqLexer.OR)) {
            collect(e.expression(0), node, conditional);
            collect(e.expression(1), node, true);
            return;
        }
        if (t instanceof PatternContext pattern) {
            // o instanceof String s: s is assigned only if the match succeeds.
            for (VariableDeclaratorIdContext id : descendants(pattern, VariableDeclaratorIdContext.class)) {
                patternVariables.add(id.getText());
                def(node, id.identifier().start, true);
            }
            return;
        }
        if (t instanceof VariableDeclaratorContext declarator) {
            if (declarator.variableInitializer() != null) {
                def(node, declarator.variableDeclaratorId().identifier().start, conditional);
                collect(declarator.variableInitializer(), node, conditional);
            }
            return;
        }
        if (t instanceof LocalVariableDeclarationContext declaration && declaration.VAR() != null) {
            def(node, declaration.identifier().start, conditional);
            collect(declaration.expression(), node, conditional);
            return;
        }
        boolean inside = conditional || t instanceof SwitchExpressionContext;
        for (int i = 0; i < t.getChildCount(); i++) {
            collect(t.getChild(i), node, inside);
        }
    }

    private static void collectUses(ParseTree t, Node node, ParseTree skip) {
        if (t == skip) {
            return;
        }
        if (t instanceof PrimaryContext primary && primary.identifier() != null) {
            node.uses.add(primary.identifier().start);
            return;
        }
        for (int i = 0; i < t.getChildCount(); i++) {
            collectUses(t.getChild(i), node, skip);
        }
    }

    private static void def(Node node, Token name, boolean conditional) {
        node.defs.putIfAbsent(name.getText(), name);
        if (!conditional) {
            node.definiteDefs.add(name.getText());
        }
    }

    private static <T extends ParseTree> List<T> descendants(ParseTree tree, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (tree != null) {
            if (type.isInstance(tree)) {
                found.add(type.cast(tree));
            }
            for (int i = 0; i < tree.getChildCount(); i++) {
                found.addAll(descendants(tree.getChild(i), type));
            }
        }
        return found;
    }
}
