package org.python.pegen.compile;

import org.python.pegen.ast.Located;

/**
 * C: _Py_SourceLocation (Include/internal/pycore_symtable.h): a range in the
 * source, columns as UTF-8 byte offsets like the AST's. Immutable, so the
 * shared NO_LOCATION and NEXT_LOCATION can be passed around like C's
 * by-value structs.
 */
public final class SourceLocation {

    public final int lineno;
    public final int end_lineno;
    public final int col_offset;
    public final int end_col_offset;

    public static final SourceLocation NO_LOCATION = new SourceLocation(-1, -1, -1, -1);
    public static final SourceLocation NEXT_LOCATION = new SourceLocation(-2, -2, -2, -2);

    /** C's initializer order: {lineno, end_lineno, col_offset, end_col_offset}. */
    public SourceLocation(int lineno, int end_lineno, int col_offset, int end_col_offset) {
        this.lineno = lineno;
        this.end_lineno = end_lineno;
        this.col_offset = col_offset;
        this.end_col_offset = end_col_offset;
    }

    /** SRC_LOCATION_FROM_AST */
    public static SourceLocation SRC_LOCATION_FROM_AST(Located n) {
        return new SourceLocation(n.lineno(), n.end_lineno(), n.col_offset(), n.end_col_offset());
    }

    @Override
    public String toString() {
        return "(" + lineno + ", " + end_lineno + ", " + col_offset + ", " + end_col_offset + ")";
    }
}
