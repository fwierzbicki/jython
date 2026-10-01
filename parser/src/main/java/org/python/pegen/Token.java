package org.python.pegen;

import org.python.pegen.ast.Located;

/**
 * A token as seen by the parser; the counterpart of {@code Token} in CPython's
 * Parser/pegen.h. Field names match the C struct so grammar actions translate
 * mechanically.
 */
public class Token implements Located {

    public int type;
    public String string;
    public int level;
    public int lineno, col_offset, end_lineno, end_col_offset;
    public Object metadata;

    /** Head of this token's memo list (C: {@code Memo *memo}). */
    Parser.Memo memo;

    public Token(int type, String string, int lineno, int col_offset, int end_lineno,
            int end_col_offset) {
        this.type = type;
        this.string = string;
        this.lineno = lineno;
        this.col_offset = col_offset;
        this.end_lineno = end_lineno;
        this.end_col_offset = end_col_offset;
    }

    @Override
    public int lineno() {
        return lineno;
    }

    @Override
    public int col_offset() {
        return col_offset;
    }

    @Override
    public int end_lineno() {
        return end_lineno;
    }

    @Override
    public int end_col_offset() {
        return end_col_offset;
    }

    @Override
    public String toString() {
        String name = type < TokenTypes.NAMES.length ? TokenTypes.NAMES[type] : "KEYWORD";
        return name + " '" + string + "' " + lineno + ":" + col_offset;
    }
}
