package linqc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import linqc.analysis.ControlFlowGraph;
import linqc.analysis.ControlFlowGraph.Node;
import linqc.analysis.ReachingDefinitions;
import linqc.analysis.ReachingDefinitions.Definition;
import linqc.parser.JLinqLexer;
import linqc.parser.JLinqParser.BinaryOperatorExpressionContext;
import linqc.parser.JLinqParser.BlockContext;
import linqc.parser.JLinqParser.ClassBodyContext;
import linqc.parser.JLinqParser.ConstructorDeclarationContext;
import linqc.parser.JLinqParser.ExpressionContext;
import linqc.parser.JLinqParser.ExpressionLambdaContext;
import linqc.parser.JLinqParser.ExpressionQueryContext;
import linqc.parser.JLinqParser.ForInitContext;
import linqc.parser.JLinqParser.FormalParametersContext;
import linqc.parser.JLinqParser.InterfaceCommonBodyDeclarationContext;
import linqc.parser.JLinqParser.LambdaExpressionContext;
import linqc.parser.JLinqParser.LocalTypeDeclarationContext;
import linqc.parser.JLinqParser.LocalVariableDeclarationContext;
import linqc.parser.JLinqParser.MemberReferenceExpressionContext;
import linqc.parser.JLinqParser.MethodDeclarationContext;
import linqc.parser.JLinqParser.PrimaryContext;
import linqc.parser.JLinqParser.PrimaryExpressionContext;
import linqc.parser.JLinqParser.QueryBodyClauseContext;
import linqc.parser.JLinqParser.QueryBodyContext;
import linqc.parser.JLinqParser.QueryExpressionContext;
import linqc.parser.JLinqParser.SelectClauseContext;
import linqc.parser.JLinqParser.TernaryExpressionContext;
import linqc.parser.JLinqParser.VariableDeclaratorContext;
import linqc.parser.JLinqParserBaseVisitor;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * Finds queries that only filter another query's result, and merges each such filter into the
 * first query:
 *
 * <pre>
 *   var adults = from p in people where p.age() >= 18 select p;
 *   var locals = from a in adults where a.city().equals("Austin") select a;
 * becomes
 *   var adults = from p in people where p.age() >= 18 &amp;&amp; p.city().equals("Austin") select p;
 *   var locals = linq.Query.from(adults);
 * </pre>
 *
 * A merge is only made when the analysis shows it changes nothing the program can see:
 * <ol>
 *   <li>The second query only filters: {@code from x in a}, where clauses, {@code select x}.
 *   <li>It isn't inside a lambda or another query, and its method has no statement the
 *       control-flow graph can't model.
 *   <li>Reaching definitions: the only assignment to {@code a} that can reach the second query
 *       is {@code a = <first query>} (the query itself, not e.g. its {@code toList()}).
 *   <li>That assignment reaches no other use of {@code a}, so nothing else sees the extra filter.
 *   <li>Every name the filter uses means the same variable at the first query as at the second.
 * </ol>
 *
 * Queries are deferred, so both filters run when the result is iterated, before and after the
 * merge. The first query stays where it is, so its source is still read at the same point.
 */
public final class QueryMerger {

    /** The verdict for one query that reads a variable holding a query. */
    public record Finding(String sourceName, int line, String variable, boolean mergeable, int firstQueryLine, String detail) {
        @Override
        public String toString() {
            return mergeable
                    ? sourceName + ":" + line + ": the query over '" + variable + "' can be merged into the query on line "
                      + firstQueryLine + ", giving:\n    " + detail
                    : sourceName + ":" + line + ": the query over '" + variable + "' can't be merged: " + detail;
        }
    }

    // calledMethod is set for "(query).toList()" and the like: the variable holds that call's result.
    private record QueryValue(QueryExpressionContext query, String calledMethod) {
    }

    // Replace tokens start..stop (inclusive) with text.
    private record Edit(int start, int stop, String text) {
    }

    private record Candidate(Finding finding, QueryExpressionContext first, List<Edit> edits) {
    }

    private record Method(FormalParametersContext parameters, BlockContext body) {
    }

    private QueryMerger() {
    }

    /** One finding per query whose source is a local variable that some query is assigned to. */
    public static List<Finding> analyze(String source, String sourceName) {
        return candidates(SourceFile.parse(source, sourceName)).stream().map(Candidate::finding).toList();
    }

    /** The source with every mergeable query merged. */
    public static String merge(String source, String sourceName) {
        String current = source;
        while (true) {
            SourceFile file = SourceFile.parse(current, sourceName);
            // In a chain a -> b -> c, merge c's filter into b first; b (now with both filters)
            // can then be merged into a on the next round.
            Candidate next = candidates(file).stream()
                    .filter(c -> c.finding().mergeable())
                    .max(Comparator.comparingInt(c -> c.first().start.getTokenIndex()))
                    .orElse(null);
            if (next == null) {
                return current;
            }
            TokenStreamRewriter rewriter = new TokenStreamRewriter(file.tokens());
            for (Edit edit : next.edits()) {
                rewriter.replace(edit.start(), edit.stop(), edit.text());
            }
            current = rewriter.getText();
        }
    }

    private static List<Candidate> candidates(SourceFile file) {
        List<Candidate> all = new ArrayList<>();
        for (Method method : methods(file.tree())) {
            all.addAll(new MethodAnalysis(file, method).candidates());
        }
        all.sort(Comparator.comparingInt(c -> c.finding().line()));
        return all;
    }

    private static List<Method> methods(ParseTree tree) {
        List<Method> methods = new ArrayList<>();
        tree.accept(new JLinqParserBaseVisitor<Void>() {
            @Override
            public Void visitMethodDeclaration(MethodDeclarationContext ctx) {
                if (ctx.methodBody().block() != null) {
                    methods.add(new Method(ctx.formalParameters(), ctx.methodBody().block()));
                }
                return visitChildren(ctx);
            }

            @Override
            public Void visitInterfaceCommonBodyDeclaration(InterfaceCommonBodyDeclarationContext ctx) {
                if (ctx.methodBody().block() != null) {
                    methods.add(new Method(ctx.formalParameters(), ctx.methodBody().block()));
                }
                return visitChildren(ctx);
            }

            @Override
            public Void visitConstructorDeclaration(ConstructorDeclarationContext ctx) {
                methods.add(new Method(ctx.formalParameters(), ctx.constructorBody));
                return visitChildren(ctx);
            }
        });
        return methods;
    }

    /** The analysis of one method body. */
    private static final class MethodAnalysis {

        private final SourceFile file;
        private final BlockContext body;
        private ControlFlowGraph graph;
        private ReachingDefinitions reaching;
        private String unsupported;

        MethodAnalysis(SourceFile file, Method method) {
            this.file = file;
            this.body = method.body();
            try {
                graph = ControlFlowGraph.of(method.parameters(), method.body());
                reaching = ReachingDefinitions.of(graph);
            } catch (ControlFlowGraph.UnsupportedException e) {
                unsupported = e.getMessage();
            }
        }

        List<Candidate> candidates() {
            Set<String> holdQueries = new HashSet<>();
            List<QueryExpressionContext> queries = new ArrayList<>();
            scan(body, holdQueries, queries);
            List<Candidate> found = new ArrayList<>();
            for (QueryExpressionContext query : queries) {
                Token source = ControlFlowGraph.simpleName(query.fromClause().expression());
                if (source != null && holdQueries.contains(source.getText())) {
                    found.add(check(query, source.getText()));
                }
            }
            return found;
        }

        private Candidate check(QueryExpressionContext second, String a) {
            String x = second.fromClause().identifier().getText();

            // 1. The second query only filters.
            String notAFilter = notAFilter(second, x);
            if (notAFilter != null) {
                return refuse(second, a, 0, notAFilter);
            }
            // 2. It's a statement-level query in a method the graph can model.
            if (isNested(second)) {
                return refuse(second, a, 0, "it is inside a lambda or another query");
            }
            if (graph == null) {
                return refuse(second, a, 0, "its method can't be analyzed: " + unsupported);
            }

            // 3. Exactly one assignment to a reaches the second query, and it assigns a query.
            Node use = graph.nodeContaining(second);
            List<Definition> definitions = reaching.reaching(use, a);
            if (definitions.size() != 1) {
                return refuse(second, a, 0, definitions.isEmpty()
                        ? "no single assignment to '" + a + "' reaches it"
                        : "'" + a + "' may hold the value assigned on " + lines(definitions) + " here");
            }
            Definition definition = definitions.get(0);
            QueryValue first = queryAssignedIn(definition.node().ctx(), a);
            if (first == null) {
                return refuse(second, a, 0, "'" + a + "' doesn't hold a query here (it is assigned on line " + definition.line() + ")");
            }
            int firstLine = first.query().start.getLine();
            if (first.calledMethod() != null) {
                return refuse(second, a, firstLine, "'" + a + "' holds the result of " + first.calledMethod()
                        + "() on the query on line " + firstLine + ", not the query itself");
            }

            // 4. That assignment reaches no other use of a.
            Token source = ControlFlowGraph.simpleName(second.fromClause().expression());
            for (Node node : reaching.reachedBy(definition)) {
                for (Token read : node.uses()) {
                    if (read.getText().equals(a) && read != source) {
                        return refuse(second, a, firstLine, "'" + a + "' is also used on line " + read.getLine());
                    }
                }
            }

            // 5. The filter's names mean the same thing at the first query.
            Map<String, Token> atFirst = definition.node().scope();
            Map<String, Token> atSecond = use.scope();
            List<ExpressionContext> filters = second.queryBody().queryBodyClause().stream()
                    .map(clause -> clause.whereClause().expression()).toList();
            for (ExpressionContext filter : filters) {
                for (Token name : names(filter)) {
                    String n = name.getText();
                    if (n.equals(x)) {
                        continue;
                    }
                    if (graph.patternVariables().contains(n)) {
                        return refuse(second, a, firstLine, "'" + n + "' is a pattern variable, whose scope the analysis doesn't track");
                    }
                    if (!Objects.equals(atFirst.get(n), atSecond.get(n))) {
                        return refuse(second, a, firstLine, atFirst.get(n) == null
                                ? "the filter uses '" + n + "', which isn't declared yet on line " + firstLine
                                : "'" + n + "' means a different variable on line " + firstLine);
                    }
                }
            }

            // How to merge. If the first query ends in "select p" (p its own variable) and only
            // has where and orderby clauses, the filter joins it, with x renamed to p. Otherwise
            // the first query continues with "into x where <filter> select x".
            String p = first.query().fromClause().identifier().getText();
            QueryBodyContext firstBody = first.query().queryBody();
            boolean simple = firstBody.queryContinuation() == null
                    && firstBody.selectOrGroupClause() instanceof SelectClauseContext select
                    && select.expression().getText().equals(p)
                    && firstBody.queryBodyClause().stream().allMatch(c -> c.whereClause() != null || c.orderbyClause() != null)
                    && (x.equals(p) || filters.stream().flatMap(f -> names(f).stream()).noneMatch(t -> t.getText().equals(p)));
            if (!simple) {
                if (atFirst.containsKey(x) || graph.patternVariables().contains(x)) {
                    return refuse(second, a, firstLine, "'" + x + "' is already a variable on line " + firstLine);
                }
                Token declaration = atSecond.get(a);
                if (declaration != null && graph.declaredType(declaration).contains("OrderedQuery")) {
                    return refuse(second, a, firstLine, "'" + a + "' is declared as an OrderedQuery, and a filter after the sort makes a plain Query");
                }
            }

            List<Edit> edits = new ArrayList<>();
            if (simple) {
                String filter = joined(filters, x, p);
                List<QueryBodyClauseContext> clauses = firstBody.queryBodyClause();
                int sort = 0;
                while (sort < clauses.size() && clauses.get(sort).orderbyClause() == null) {
                    sort++;
                }
                // The filter goes before any orderby: that sorts fewer elements and keeps the
                // result an OrderedQuery. If a where clause is already there, it joins it.
                QueryBodyClauseContext before = sort > 0 ? clauses.get(sort - 1) : null;
                if (before != null && before.whereClause() != null) {
                    ExpressionContext existing = before.whereClause().expression();
                    edits.add(new Edit(existing.start.getTokenIndex(), existing.stop.getTokenIndex(),
                            parenthesized(existing, file.text(existing)) + " && " + filter));
                } else {
                    Token at = sort < clauses.size() ? clauses.get(sort).start : firstBody.selectOrGroupClause().start;
                    edits.add(new Edit(at.getTokenIndex(), at.getTokenIndex(), "where " + filter + " " + at.getText()));
                }
            } else {
                Token end = first.query().stop;
                edits.add(new Edit(end.getTokenIndex(), end.getTokenIndex(),
                        end.getText() + " into " + x + " where " + joined(filters, x, x) + " select " + x));
            }
            String merged = withEdits(first.query(), edits).replaceAll("\\s+", " ").trim();

            // The second query now just wraps a. Keep its line breaks so later lines don't move.
            edits.add(new Edit(second.start.getTokenIndex(), second.stop.getTokenIndex(),
                    "linq.Query.from(" + a + ") /* filter merged into the query on line " + firstLine + " */"
                    + "\n".repeat(SourceFile.newlines(file.text(second)))));
            Finding finding = new Finding(file.name(), second.start.getLine(), a, true, firstLine, merged);
            return new Candidate(finding, first.query(), edits);
        }

        private Candidate refuse(QueryExpressionContext second, String a, int firstLine, String reason) {
            return new Candidate(new Finding(file.name(), second.start.getLine(), a, false, firstLine, reason), null, List.of());
        }

        private boolean isNested(QueryExpressionContext query) {
            for (ParseTree t = query.getParent(); t != null && t != body; t = t.getParent()) {
                if (t instanceof LambdaExpressionContext || t instanceof QueryExpressionContext || t instanceof ClassBodyContext) {
                    return true;
                }
            }
            return false;
        }

        // The filters joined with &&, each with x renamed to p, on one line and without comments.
        private String joined(List<ExpressionContext> filters, String x, String p) {
            return filters.stream().map(f -> parenthesized(f, flatText(f, x, p))).collect(Collectors.joining(" && "));
        }

        private String flatText(ExpressionContext filter, String from, String to) {
            Set<Integer> renamed = names(filter).stream()
                    .filter(t -> t.getText().equals(from)).map(Token::getTokenIndex).collect(Collectors.toSet());
            StringBuilder text = new StringBuilder();
            for (int i = filter.start.getTokenIndex(); i <= filter.stop.getTokenIndex(); i++) {
                Token token = file.tokens().get(i);
                if (token.getType() == JLinqLexer.COMMENT || token.getType() == JLinqLexer.LINE_COMMENT) {
                    continue;
                }
                text.append(token.getType() == JLinqLexer.WS ? (token.getText().contains("\n") ? " " : token.getText())
                        : renamed.contains(i) ? to : token.getText());
            }
            return text.toString();
        }

        // The text of ctx after applying the edits that fall inside it.
        private String withEdits(ParserRuleContext ctx, List<Edit> edits) {
            StringBuilder text = new StringBuilder();
            for (int i = ctx.start.getTokenIndex(); i <= ctx.stop.getTokenIndex(); i++) {
                int index = i;
                Edit edit = edits.stream().filter(e -> e.start() == index).findFirst().orElse(null);
                if (edit != null) {
                    text.append(edit.text());
                    i = edit.stop();
                } else {
                    text.append(file.tokens().get(i).getText());
                }
            }
            return text.toString();
        }

        private static String lines(List<Definition> definitions) {
            return definitions.stream().map(d -> "line " + d.line()).distinct().collect(Collectors.joining(" or "));
        }
    }

    // ---- Recognizing queries and filters ------------------------------------------------------

    /** Why the query isn't just "from x in a where ... select x", or null if it is. */
    private static String notAFilter(QueryExpressionContext query, String x) {
        QueryBodyContext body = query.queryBody();
        if (body.queryContinuation() != null) {
            return "it does more than filter (it continues with 'into')";
        }
        for (QueryBodyClauseContext clause : body.queryBodyClause()) {
            if (clause.whereClause() == null) {
                return "it does more than filter (it has a '" + clause.start.getText() + "' clause)";
            }
        }
        if (!(body.selectOrGroupClause() instanceof SelectClauseContext select)) {
            return "it does more than filter (it groups)";
        }
        if (!select.expression().getText().equals(x)) {
            return "it does more than filter (its select changes the elements)";
        }
        if (body.queryBodyClause().isEmpty()) {
            return "it has no where clause, so there is nothing to merge";
        }
        return null;
    }

    // Finds the method's queries and the variables some query is assigned to. Skips nested
    // class bodies: their methods are analyzed separately.
    private static void scan(ParseTree t, Set<String> holdQueries, List<QueryExpressionContext> queries) {
        if (t instanceof ClassBodyContext || t instanceof LocalTypeDeclarationContext) {
            return;
        }
        if (t instanceof QueryExpressionContext query) {
            queries.add(query);
        }
        if (t instanceof LocalVariableDeclarationContext declaration) {
            if (declaration.VAR() != null) {
                if (queryValue(declaration.expression()) != null) {
                    holdQueries.add(declaration.identifier().getText());
                }
            } else {
                for (VariableDeclaratorContext declarator : declaration.variableDeclarators().variableDeclarator()) {
                    if (declarator.variableInitializer() != null && queryValue(declarator.variableInitializer().expression()) != null) {
                        holdQueries.add(declarator.variableDeclaratorId().getText());
                    }
                }
            }
        }
        if (t instanceof BinaryOperatorExpressionContext e && e.bop != null && e.bop.getText().equals("=")) {
            Token target = ControlFlowGraph.simpleName(e.expression(0));
            if (target != null && queryValue(e.expression(1)) != null) {
                holdQueries.add(target.getText());
            }
        }
        for (int i = 0; i < t.getChildCount(); i++) {
            scan(t.getChild(i), holdQueries, queries);
        }
    }

    /** The query that the statement {@code ctx} assigns to {@code a}, or null. */
    private static QueryValue queryAssignedIn(ParserRuleContext ctx, String a) {
        if (ctx instanceof ForInitContext init) {
            ctx = init.localVariableDeclaration();
        }
        if (ctx instanceof LocalVariableDeclarationContext declaration) {
            if (declaration.VAR() != null) {
                return declaration.identifier().getText().equals(a) ? queryValue(declaration.expression()) : null;
            }
            for (VariableDeclaratorContext declarator : declaration.variableDeclarators().variableDeclarator()) {
                if (declarator.variableDeclaratorId().getText().equals(a) && declarator.variableInitializer() != null) {
                    return queryValue(declarator.variableInitializer().expression());
                }
            }
            return null;
        }
        if (ctx instanceof BinaryOperatorExpressionContext e && e.bop != null && e.bop.getText().equals("=")
                && e.expression(0).getText().equals(a)) {
            return queryValue(e.expression(1));
        }
        return null;
    }

    // A query, a parenthesized query, or a method called on one, like (from ...).toList().
    private static QueryValue queryValue(ExpressionContext e) {
        String called = null;
        while (e instanceof MemberReferenceExpressionContext member && member.methodCall() != null) {
            called = member.methodCall().getChild(0).getText();
            e = member.expression();
        }
        while (e instanceof PrimaryExpressionContext primary && primary.primary().expression() != null) {
            e = primary.primary().expression();
        }
        return e instanceof ExpressionQueryContext query ? new QueryValue(query.queryExpression(), called) : null;
    }

    /** Every simple name read in the tree (over-approximated: includes names bound inside it). */
    private static List<Token> names(ParseTree tree) {
        List<Token> found = new ArrayList<>();
        if (tree instanceof PrimaryContext primary && primary.identifier() != null) {
            found.add(primary.identifier().start);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            found.addAll(names(tree.getChild(i)));
        }
        return found;
    }

    // Parentheses around expressions that bind more loosely than &&.
    private static String parenthesized(ExpressionContext e, String text) {
        boolean loose = e instanceof TernaryExpressionContext || e instanceof ExpressionLambdaContext || e instanceof ExpressionQueryContext
                || e instanceof BinaryOperatorExpressionContext b
                   && (ControlFlowGraph.isAssignment(b) || b.bop != null && b.bop.getType() == JLinqLexer.OR);
        return loose ? "(" + text + ")" : text;
    }
}
