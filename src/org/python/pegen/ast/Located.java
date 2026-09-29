package org.python.pegen.ast;

/**
 * Something with a source range: AST nodes whose ASDL type has location
 * attributes, and parser tokens. What C code reads as (a)->lineno etc. for
 * any such node or token. Columns are UTF-8 byte offsets, as in CPython.
 */
public interface Located {

    int lineno();

    int col_offset();

    int end_lineno();

    int end_col_offset();
}
