package org.python.pegen;

/**
 * Supplies tokens to the {@link Parser}; stands in for CPython's tokenizer
 * ({@code struct tok_state}). Implementations return ENDMARKER at end of
 * input and must not return NL, COMMENT or ENCODING tokens.
 */
public interface TokenSource {

    /** Returns the next token, or null after setting an error. */
    Token next();
}
