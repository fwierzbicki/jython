package org.python.pegen;

/**
 * Supplies tokens to the {@link Parser}; stands in for CPython's tokenizer
 * ({@code struct tok_state}). Implementations return ENDMARKER at end of
 * input and must not return NL, COMMENT or ENCODING tokens.
 */
public interface TokenSource {

    /** Returns the next token, or null after setting an error. */
    Token next();

    // The tokenizer state some grammar actions read (through macros in
    // ActionHelpers). It is the state after the last token returned. The
    // defaults describe a tokenizer outside any f- or t-string.

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
}
