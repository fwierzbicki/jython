package org.python.pegen;

/**
 * An exception raised while parsing: the Java stand-in for the Python
 * exception CPython's parser sets (PyErr_*). type is the Python class name
 * ("SyntaxError", "IndentationError", "ValueError", "MemoryError", ...).
 *
 * <p>For SyntaxError and its subclasses the location fields are SyntaxError's
 * attributes: lineno, 1-based offset and end_offset in characters (not
 * bytes), and text, the source line. An exception raised without a location
 * (PyErr_SetString) has lineno 0 and a null text; hasLocation() says which.
 */
public class PythonSyntaxError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public final String type;
    public final String msg;
    public final int lineno;
    public final int offset;
    public final int end_lineno;
    public final int end_offset;
    public final String text;
    private final boolean hasLocation;

    /**
     * SyntaxError._metadata, set from Parser.last_stmt_location after the
     * second pass (C: _PyPegen_set_syntax_error_metadata): the start of the
     * last statement parsed and the source, or null.
     */
    public int[] metadata_location;
    public String metadata_source;

    /** An exception without a location (PyErr_SetString). */
    public PythonSyntaxError(String type, String msg) {
        super(type + ": " + msg);
        this.type = type;
        this.msg = msg;
        this.lineno = 0;
        this.offset = 0;
        this.end_lineno = 0;
        this.end_offset = 0;
        this.text = null;
        this.hasLocation = false;
    }

    /** A SyntaxError (or subclass) with its location attributes. */
    public PythonSyntaxError(String type, String msg, int lineno, int offset, String text,
            int end_lineno, int end_offset) {
        super(type + ": " + msg + " (line " + lineno + ", offset " + offset + ")");
        this.type = type;
        this.msg = msg;
        this.lineno = lineno;
        this.offset = offset;
        this.end_lineno = end_lineno;
        this.end_offset = end_offset;
        this.text = text;
        this.hasLocation = true;
    }

    public boolean hasLocation() {
        return hasLocation;
    }
}
