package linqc;

/** A .jlinq file could not be translated. The message says where, as file:line:column. */
public class TranslationException extends RuntimeException {

    public TranslationException(String message) {
        super(message);
    }
}
