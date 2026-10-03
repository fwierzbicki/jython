package org.python.pegen.compile;

import java.util.List;

import org.python.pegen.ast.Constant;
import org.python.pegen.ast.Expr;
import org.python.pegen.ast.base.expr;
import org.python.pegen.ast.base.stmt;

/** The parts of CPython's Python/ast.c the compiler stages use, under their C names. */
public final class Ast {

    private Ast() {}

    /** _PyAST_GetDocString: the docstring that starts body (a str Constant), or null. */
    public static String _PyAST_GetDocString(List<stmt> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        stmt st = body.get(0);
        if (st.kind() != stmt.Kind.Expr) {
            return null;
        }
        expr e = ((Expr) st).value;
        if (e.kind() == expr.Kind.Constant && ((Constant) e).value instanceof String) {
            return (String) ((Constant) e).value;
        }
        return null;
    }
}
