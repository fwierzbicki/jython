package org.python.pegen.ast;

/**
 * A node constructor was given null for a required field: the ValueError
 * CPython's _PyAST_* functions raise ("field 'left' is required for BinOp").
 * Parser.runParser reports it as that ValueError, as _PyPegen_run_parser does.
 */
public class AstValueError extends RuntimeException {

    public AstValueError(String message) {
        super(message);
    }
}
