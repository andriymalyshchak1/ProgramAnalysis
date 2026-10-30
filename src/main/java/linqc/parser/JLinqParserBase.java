package linqc.parser;

import java.util.List;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;

/**
 * Semantic predicates used by JLinqParser.g4. Adapted from JavaParserBase.java in
 * grammars-v4 (java/java/Java), the Java target's helper for the Java grammar.
 */
public abstract class JLinqParserBase extends Parser {

    public JLinqParserBase(TokenStream input) {
        super(input);
    }

    /** Only the last record component may be a varargs parameter. */
    public boolean DoLastRecordComponent() {
        ParserRuleContext ctx = this.getContext();
        if (!(ctx instanceof JLinqParser.RecordComponentListContext components)) {
            return true;
        }
        List<JLinqParser.RecordComponentContext> list = components.recordComponent();
        for (int i = 0; i < list.size() - 1; i++) {
            if (list.get(i).ELLIPSIS() != null) {
                return false;
            }
        }
        return true;
    }

    /** In an annotation, tells {@code @A(x = 1)} (a named value) apart from {@code @A(x)}. */
    public boolean IsNotIdentifierAssign() {
        switch (this._input.LA(1)) {
            case JLinqParser.IDENTIFIER:
            case JLinqParser.MODULE:
            case JLinqParser.OPEN:
            case JLinqParser.REQUIRES:
            case JLinqParser.EXPORTS:
            case JLinqParser.OPENS:
            case JLinqParser.TO:
            case JLinqParser.USES:
            case JLinqParser.PROVIDES:
            case JLinqParser.WHEN:
            case JLinqParser.WITH:
            case JLinqParser.TRANSITIVE:
            case JLinqParser.YIELD:
            case JLinqParser.SEALED:
            case JLinqParser.PERMITS:
            case JLinqParser.RECORD:
            case JLinqParser.VAR:
            // The LINQ query keywords can be identifiers too (added for linq-java).
            case JLinqParser.ASCENDING:
            case JLinqParser.BY:
            case JLinqParser.DESCENDING:
            case JLinqParser.EQUALS:
            case JLinqParser.FROM:
            case JLinqParser.GROUP:
            case JLinqParser.IN:
            case JLinqParser.INTO:
            case JLinqParser.JOIN:
            case JLinqParser.LET:
            case JLinqParser.ON:
            case JLinqParser.ORDERBY:
            case JLinqParser.SELECT:
            case JLinqParser.WHERE:
                break;
            default:
                return true;
        }
        return this._input.LA(2) != JLinqParser.ASSIGN;
    }
}
