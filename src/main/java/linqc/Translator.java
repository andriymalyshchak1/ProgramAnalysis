package linqc;

import java.util.ArrayList;
import java.util.List;
import linqc.parser.JLinqLexer;
import linqc.parser.JLinqParser.FromClauseContext;
import linqc.parser.JLinqParser.GroupClauseContext;
import linqc.parser.JLinqParser.OrderbyClauseContext;
import linqc.parser.JLinqParser.OrderingContext;
import linqc.parser.JLinqParser.QueryBodyClauseContext;
import linqc.parser.JLinqParser.QueryBodyContext;
import linqc.parser.JLinqParser.QueryContinuationContext;
import linqc.parser.JLinqParser.QueryExpressionContext;
import linqc.parser.JLinqParser.SelectClauseContext;
import linqc.parser.JLinqParser.SelectOrGroupClauseContext;
import linqc.parser.JLinqParserBaseVisitor;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * Translates a .jlinq file (Java with LINQ query expressions) into plain Java.
 *
 * <p>Each query expression becomes a chain of calls on {@code linq.Query}, following the
 * translation rules in the C# specification. As in C#, the rewrite is purely syntactic: the
 * translator never needs to know any types, because javac type-checks the generated code.
 *
 * <p>Everything outside query expressions is copied exactly, whitespace and comments included.
 * A multi-line query keeps each clause on its original line, so javac's line numbers for the
 * generated file also point at the right lines of the .jlinq file.
 */
public final class Translator {

    private final String sourceName;
    private final CommonTokenStream tokens;

    private Translator(String sourceName, CommonTokenStream tokens) {
        this.sourceName = sourceName;
        this.tokens = tokens;
    }

    /**
     * @param sourceName the file name to show in error messages, e.g. "Adults.jlinq"
     * @throws TranslationException for syntax errors and for query clauses not supported yet
     */
    public static String translate(String source, String sourceName) {
        SourceFile file = SourceFile.parse(source, sourceName);
        Translator translator = new Translator(sourceName, file.tokens());
        TokenStreamRewriter rewriter = new TokenStreamRewriter(file.tokens());
        for (QueryExpressionContext query : outermostQueries(file.tree())) {
            rewriter.replace(query.start, query.stop, translator.translateQuery(query));
        }
        return rewriter.getText();
    }

    private String translateQuery(QueryExpressionContext query) {
        String java = query(query);
        // Clauses keep their own lines (see whitespaceBefore). If a newline sat somewhere else
        // in the query, e.g. right after "where", add it back at the end so later lines don't move.
        int lost = SourceFile.newlines(textOf(query)) - SourceFile.newlines(java);
        return java + "\n".repeat(Math.max(0, lost));
    }

    // from x in e  ->  linq.Query.from(e); each later clause adds one call to the chain.
    private String query(QueryExpressionContext query) {
        FromClauseContext from = query.fromClause();
        StringBuilder out = new StringBuilder("linq.Query.from(").append(render(from.expression())).append(')');
        String var = from.identifier().getText();

        QueryBodyContext body = query.queryBody();
        while (true) {
            for (QueryBodyClauseContext clause : body.queryBodyClause()) {
                out.append(whitespaceBefore(clause));
                if (clause.whereClause() != null) {
                    out.append(".where(").append(lambda(var, clause.whereClause().expression())).append(')');
                } else if (clause.orderbyClause() != null) {
                    out.append(orderings(var, clause.orderbyClause()));
                } else {
                    throw unsupported(clause);
                }
            }

            SelectOrGroupClauseContext last = body.selectOrGroupClause();
            out.append(whitespaceBefore(last));
            if (last instanceof SelectClauseContext select) {
                // "select x" returns each element unchanged, so like C# we leave it out.
                if (!isVariable(select.expression(), var)) {
                    out.append(".select(").append(lambda(var, select.expression())).append(')');
                }
            } else {
                GroupClauseContext group = (GroupClauseContext) last;
                out.append(".groupBy(").append(lambda(var, group.key));
                if (!isVariable(group.element, var)) {
                    out.append(", ").append(lambda(var, group.element));
                }
                out.append(')');
            }

            // "... into g <more clauses>" continues the same chain, with g as the new variable.
            QueryContinuationContext into = body.queryContinuation();
            if (into == null) {
                return out.toString();
            }
            out.append(whitespaceBefore(into));
            var = into.identifier().getText();
            body = into.queryBody();
        }
    }

    // orderby k1, k2 descending  ->  .orderBy(x -> k1).thenByDescending(x -> k2)
    private String orderings(String var, OrderbyClauseContext orderby) {
        StringBuilder out = new StringBuilder();
        List<OrderingContext> orderings = orderby.ordering();
        for (int i = 0; i < orderings.size(); i++) {
            OrderingContext ordering = orderings.get(i);
            boolean descending = ordering.direction != null && ordering.direction.getType() == JLinqLexer.DESCENDING;
            if (i > 0) {
                out.append(whitespaceBefore(ordering));
            }
            out.append(i == 0 ? ".orderBy" : ".thenBy").append(descending ? "Descending(" : "(")
               .append(lambda(var, ordering.expression())).append(')');
        }
        return out.toString();
    }

    private String lambda(String var, ParserRuleContext body) {
        return var + " -> " + render(body);
    }

    private static boolean isVariable(ParserRuleContext expression, String var) {
        return expression.getText().equals(var);
    }

    /** The source text of a node as written, with any queries nested inside it translated. */
    private String render(ParserRuleContext ctx) {
        StringBuilder out = new StringBuilder();
        int next = ctx.start.getTokenIndex();
        for (QueryExpressionContext nested : outermostQueries(ctx)) {
            out.append(tokens.getText(Interval.of(next, nested.start.getTokenIndex() - 1)));
            out.append(query(nested));
            next = nested.stop.getTokenIndex() + 1;
        }
        out.append(tokens.getText(Interval.of(next, ctx.stop.getTokenIndex())));
        return out.toString();
    }

    private String textOf(ParserRuleContext ctx) {
        return tokens.getText(Interval.of(ctx.start.getTokenIndex(), ctx.stop.getTokenIndex()));
    }

    /**
     * The whitespace and comments just before a clause, if they include a line break or a
     * comment. Emitting them before the clause's method call keeps it on its original line.
     */
    private String whitespaceBefore(ParserRuleContext clause) {
        List<Token> hidden = tokens.getHiddenTokensToLeft(clause.start.getTokenIndex());
        if (hidden == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        boolean keep = false;
        for (Token token : hidden) {
            text.append(token.getText());
            keep |= token.getType() != JLinqLexer.WS || token.getText().contains("\n");
        }
        return keep ? text.toString() : "";
    }

    private TranslationException unsupported(QueryBodyClauseContext clause) {
        String what = clause.fromClause() != null ? "a second 'from' clause is"
                                                  : "'" + clause.start.getText() + "' clauses are";
        return new TranslationException(String.format("%s:%d:%d: %s not supported yet",
                sourceName, clause.start.getLine(), clause.start.getCharPositionInLine() + 1, what));
    }

    /** Query expressions under a node, not counting queries nested inside other queries. */
    private static List<QueryExpressionContext> outermostQueries(ParseTree root) {
        List<QueryExpressionContext> found = new ArrayList<>();
        root.accept(new JLinqParserBaseVisitor<Void>() {
            @Override
            public Void visitQueryExpression(QueryExpressionContext ctx) {
                found.add(ctx); // don't visit the children: nested queries are handled by render()
                return null;
            }
        });
        return found;
    }

}
