package org.python.pegen;

/**
 * Supplies tokens to the {@link Parser}; stands in for CPython's tokenizer
 * ({@code struct tok_state}). Implementations return ENDMARKER at end of
 * input, and again on every later call, as C's tokenizer does; they must not
 * return NL, COMMENT or ENCODING tokens.
 *
 * <p>Besides tokens, the parser reads some of the tokenizer's state: for
 * f-string actions, and for error reporting (pegen_errors.c). Each method
 * names the tok_state field it stands for; the state is that after the last
 * token returned. The defaults describe a tokenizer that knows nothing more
 * than its tokens, so that a plain token list can drive the parser.
 */
public interface TokenSource {

    /**
     * Returns the next token. On a tokenizer error, returns an ERRORTOKEN
     * with done() giving the error code and error() the exception, if the
     * tokenizer raised one itself (C: an exception set by the tokenizer). A
     * null return is an error with no further information.
     */
    Token next();

    /**
     * Single input only: the parser has turned an ENDMARKER into the NEWLINE
     * ending the statement, and the tokenizer is to close the indentation
     * levels still open with DEDENTs before its next ENDMARKER. (C, in
     * _PyPegen_fill_token: tok->pendin = -tok->indent; tok->indent = 0.)
     */
    default void implyDedents() {
    }

    /**
     * C: tok->barry_as_bdfl, set when barry_as_FLUFL is in effect: the
     * tokenizer reads {@code <>} as NOTEQUAL only then.
     */
    default void setBarryAsBdfl(boolean barry_as_bdfl) {
    }

    // ---- f- and t-strings ----

    /** C: INSIDE_FSTRING(tok), whether the tokenizer is inside an f- or t-string. */
    default boolean insideFstring() {
        return false;
    }

    /** C: TOK_GET_MODE(tok)->string_kind: whether the innermost string is a t-string. */
    default boolean insideTstring() {
        return false;
    }

    /** C: TOK_GET_MODE(tok)->raw: whether the innermost f- or t-string is raw. */
    default boolean fstringRaw() {
        return false;
    }

    // ---- errors ----

    /** C: tok->done, an errcode.h value: E_OK, or E_EOF after ENDMARKER, or the error. */
    default int done() {
        return ActionHelpers.E_OK;
    }

    /** The exception the tokenizer raised with its last ERRORTOKEN, if any. */
    default PythonSyntaxError error() {
        return null;
    }

    /** C: tok->lineno, the line the tokenizer is on. */
    default int lineno() {
        return 0;
    }

    /** C: tok->cur - tok->line_start, the byte offset of the cursor in its line. */
    default int cursorColumn() {
        return 0;
    }

    /**
     * C: tok->cur - tok->buf, the cursor's byte offset from the start of the
     * buffer, which is the start of the line the current token began on
     * (unlike line_start, it stays put across a line continuation).
     */
    default int bufferOffset() {
        return cursorColumn();
    }

    /**
     * C: the text from tok->line_start to tok->inp, the line the tokenizer is
     * on, including its newline; null if nothing has been read (tok->inp ==
     * tok->buf).
     */
    default String currentLine() {
        return null;
    }

    /**
     * get_error_line_from_tokenizer_buffers: source line lineno, without its
     * newline; null if the source isn't available.
     */
    default String getLine(int lineno) {
        return null;
    }

    /**
     * C: the text from tok->cur to the end of the input, which
     * bad_single_statement scans; null if the source isn't available.
     */
    default String rest() {
        return null;
    }

    /** C: tok->str, the whole source, or null (for SyntaxError._metadata). */
    default String source() {
        return null;
    }

    /** C: tok->prompt != NULL, whether this is interactive input. */
    default boolean interactive() {
        return false;
    }

    /** C: tok->level, the number of open brackets. */
    default int level() {
        return 0;
    }

    /** C: tok->parenstack[i], the i-th open bracket. */
    default char parenstack(int i) {
        throw new IndexOutOfBoundsException();
    }

    /** C: tok->parenlinenostack[i], the line of the i-th open bracket. */
    default int parenlinenostack(int i) {
        throw new IndexOutOfBoundsException();
    }

    /** C: tok->parencolstack[i], the byte column of the i-th open bracket. */
    default int parencolstack(int i) {
        throw new IndexOutOfBoundsException();
    }
}
