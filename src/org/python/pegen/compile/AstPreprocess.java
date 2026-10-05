package org.python.pegen.compile;

import static org.python.pegen.compile.Compile.CO_FUTURE_ANNOTATIONS;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.python.pegen.LargeStack;
import org.python.pegen.Parser;
import org.python.pegen.PythonSyntaxError;
import org.python.pegen.ast.AnnAssign;
import org.python.pegen.ast.Assert;
import org.python.pegen.ast.Assign;
import org.python.pegen.ast.AsyncFor;
import org.python.pegen.ast.AsyncFunctionDef;
import org.python.pegen.ast.AsyncWith;
import org.python.pegen.ast.Attribute;
import org.python.pegen.ast.AugAssign;
import org.python.pegen.ast.Await;
import org.python.pegen.ast.BinOp;
import org.python.pegen.ast.BoolOp;
import org.python.pegen.ast.Call;
import org.python.pegen.ast.ClassDef;
import org.python.pegen.ast.Compare;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Constant;
import org.python.pegen.ast.Delete;
import org.python.pegen.ast.Dict;
import org.python.pegen.ast.DictComp;
import org.python.pegen.ast.ExceptHandler;
import org.python.pegen.ast.Expr;
import org.python.pegen.ast.Expression;
import org.python.pegen.ast.For;
import org.python.pegen.ast.FormattedValue;
import org.python.pegen.ast.FunctionDef;
import org.python.pegen.ast.GeneratorExp;
import org.python.pegen.ast.If;
import org.python.pegen.ast.IfExp;
import org.python.pegen.ast.Interactive;
import org.python.pegen.ast.Interpolation;
import org.python.pegen.ast.JoinedStr;
import org.python.pegen.ast.Lambda;
import org.python.pegen.ast.ListComp;
import org.python.pegen.ast.Match;
import org.python.pegen.ast.MatchAs;
import org.python.pegen.ast.MatchClass;
import org.python.pegen.ast.MatchMapping;
import org.python.pegen.ast.MatchOr;
import org.python.pegen.ast.MatchSequence;
import org.python.pegen.ast.MatchValue;
import org.python.pegen.ast.Module;
import org.python.pegen.ast.Name;
import org.python.pegen.ast.NamedExpr;
import org.python.pegen.ast.ParamSpec;
import org.python.pegen.ast.Pass;
import org.python.pegen.ast.Raise;
import org.python.pegen.ast.Return;
import org.python.pegen.ast.SetComp;
import org.python.pegen.ast.Singleton;
import org.python.pegen.ast.Slice;
import org.python.pegen.ast.Starred;
import org.python.pegen.ast.Subscript;
import org.python.pegen.ast.TemplateStr;
import org.python.pegen.ast.Try;
import org.python.pegen.ast.TryStar;
import org.python.pegen.ast.Tuple;
import org.python.pegen.ast.TypeAlias;
import org.python.pegen.ast.TypeVar;
import org.python.pegen.ast.TypeVarTuple;
import org.python.pegen.ast.UnaryOp;
import org.python.pegen.ast.While;
import org.python.pegen.ast.With;
import org.python.pegen.ast.Yield;
import org.python.pegen.ast.YieldFrom;
import org.python.pegen.ast.arg;
import org.python.pegen.ast.arguments;
import org.python.pegen.ast.comprehension;
import org.python.pegen.ast.expr_contextType;
import org.python.pegen.ast.keyword;
import org.python.pegen.ast.match_case;
import org.python.pegen.ast.operatorType;
import org.python.pegen.ast.unaryopType;
import org.python.pegen.ast.withitem;
import org.python.pegen.ast.base.excepthandler;
import org.python.pegen.ast.base.expr;
import org.python.pegen.ast.base.mod;
import org.python.pegen.ast.base.pattern;
import org.python.pegen.ast.base.stmt;
import org.python.pegen.ast.base.type_param;

/**
 * A port of CPython's Python/ast_preprocess.c: the pass over the AST between
 * future and symtable. It removes docstrings at optimize level 2, and unless
 * only a syntax check is asked for, replaces {@code __debug__} with its value,
 * folds {@code "..." % (...)} into an f-string and folds the negative and
 * complex numbers of match patterns into constants. When warnings are
 * enabled it issues PEP 765's warnings for return, break and continue that
 * leave a finally block. C's names and order are kept.
 *
 * <p>C changes an expression node into another kind in place (make_const,
 * COPY_NODE). A Java node's class is its kind, so here astfold_expr and the
 * folds return the node that takes the old one's place, and the caller
 * stores it where the old one was.
 *
 * <p>Errors are thrown as PythonSyntaxError where C sets one and returns 0.
 */
public final class AstPreprocess {

    /** See PEP 765 */
    private static final class ControlFlowInFinallyContext {
        final boolean in_finally;
        final boolean in_funcdef;
        final boolean in_loop;

        ControlFlowInFinallyContext(boolean in_finally, boolean in_funcdef, boolean in_loop) {
            this.in_finally = in_finally;
            this.in_funcdef = in_funcdef;
            this.in_loop = in_loop;
        }
    }

    /* C: _PyASTPreprocessState */
    private final String filename;
    private final String module;
    private final int optimize;
    private final int ff_features;
    private final boolean syntax_check_only;
    private final boolean enable_warnings;
    /** Where the warnings go (C: Python's warnings machinery). */
    private final Parser.WarningHandler warnings;

    /** context for PEP 765 check (C: cf_finally and cf_finally_used) */
    private final List<ControlFlowInFinallyContext> cf_finally = new ArrayList<>();

    private AstPreprocess(String filename, String module, int optimize, int ff_features,
            boolean syntax_check_only, boolean enable_warnings, Parser.WarningHandler warnings) {
        this.filename = filename;
        this.module = module;
        this.optimize = optimize;
        this.ff_features = ff_features;
        this.syntax_check_only = syntax_check_only;
        this.enable_warnings = enable_warnings;
        this.warnings = warnings;
    }

    private ControlFlowInFinallyContext get_cf_finally_top() {
        return cf_finally.get(cf_finally.size() - 1);
    }

    private void push_cf_context(stmt node, boolean finally_, boolean funcdef, boolean loop) {
        cf_finally.add(new ControlFlowInFinallyContext(finally_, funcdef, loop));
    }

    private void pop_cf_context() {
        assert !cf_finally.isEmpty();
        cf_finally.remove(cf_finally.size() - 1);
    }

    private void control_flow_in_finally_warning(String kw, stmt n) {
        String msg = String.format("'%s' in a 'finally' block", kw);
        Errors._PyErr_EmitSyntaxWarning(warnings, msg, filename, n.lineno, n.col_offset + 1,
                n.end_lineno, n.end_col_offset + 1, module);
    }

    private void before_return(stmt node_) {
        if (enable_warnings && !cf_finally.isEmpty()) {
            ControlFlowInFinallyContext ctx = get_cf_finally_top();
            if (ctx.in_finally && !ctx.in_funcdef) {
                control_flow_in_finally_warning("return", node_);
            }
        }
    }

    private void before_loop_exit(stmt node_, String kw) {
        if (enable_warnings && !cf_finally.isEmpty()) {
            ControlFlowInFinallyContext ctx = get_cf_finally_top();
            if (ctx.in_finally && !ctx.in_loop) {
                control_flow_in_finally_warning(kw, node_);
            }
        }
    }

    private void BEFORE_FINALLY(stmt n) {
        push_cf_context(n, true, false, false);
    }

    private void AFTER_FINALLY() {
        pop_cf_context();
    }

    private void BEFORE_FUNC_BODY(stmt n) {
        push_cf_context(n, false, true, false);
    }

    private void AFTER_FUNC_BODY() {
        pop_cf_context();
    }

    private void BEFORE_LOOP_BODY(stmt n) {
        push_cf_context(n, false, false, true);
    }

    private void AFTER_LOOP_BODY() {
        pop_cf_context();
    }

    /**
     * make_const: a Constant with node's location that takes node's place,
     * or node itself if no value was calculated (val is null: C's NULL with
     * the error cleared, e.g. for an int too large for a float).
     */
    private static expr make_const(expr node, Object val) {
        if (val == null) {
            return node;
        }
        return new Constant(val, null, node.lineno, node.col_offset, node.end_lineno,
                node.end_col_offset);
    }

    private static boolean has_starred(List<expr> elts) {
        int n = asdl_seq_LEN(elts);
        for (int i = 0; i < n; i++) {
            expr e = elts.get(i);
            if (e.kind() == expr.Kind.Starred) {
                return true;
            }
        }
        return false;
    }

    /**
     * parse_literal: the Constant for the literal text of fmt at ppos[0] (up
     * to a %-format unit), with %% read as %, and ppos[0] moved past it; null
     * if there is none.
     */
    private static expr parse_literal(String fmt, int[] ppos) {
        int size = fmt.length();
        int start, pos;
        boolean has_percents = false;
        start = pos = ppos[0];
        while (pos < size) {
            if (fmt.charAt(pos) != '%') {
                pos++;
            } else if (pos + 1 < size && fmt.charAt(pos + 1) == '%') {
                has_percents = true;
                pos += 2;
            } else {
                break;
            }
        }
        ppos[0] = pos;
        if (pos == start) {
            return null;
        }
        String str = fmt.substring(start, pos);
        /* str = str.replace('%%', '%') */
        if (has_percents) {
            str = str.replace("%%", "%");
        }
        return new Constant(str, null, -1, -1, -1, -1);
    }

    private static final int MAXDIGITS = 3;

    /* Format flags (Include/internal/pycore_format.h) */
    private static final int F_LJUST = 1 << 0;
    private static final int F_SIGN = 1 << 1;
    private static final int F_BLANK = 1 << 2;
    private static final int F_ALT = 1 << 3;
    private static final int F_ZERO = 1 << 4;

    /**
     * simple_format_arg_parse: reads the format unit at ppos[0] (after its
     * %) into spec[0], flags[0], width[0] and prec[0]; false if it isn't one
     * of the simple ones this folds. A Java String is read by UTF-16 code
     * unit where C reads code points, which gives the same result: none of
     * the characters looked for is a surrogate.
     */
    private static boolean simple_format_arg_parse(String fmt, int[] ppos, int[] spec,
            int[] flags, int[] width, int[] prec) {
        int pos = ppos[0], len = fmt.length();
        char ch;

        flags[0] = 0;
        while (true) {
            // NEXTC
            if (pos >= len) {
                return false;
            }
            ch = fmt.charAt(pos++);
            switch (ch) {
                case '-': flags[0] |= F_LJUST; continue;
                case '+': flags[0] |= F_SIGN; continue;
                case ' ': flags[0] |= F_BLANK; continue;
                case '#': flags[0] |= F_ALT; continue;
                case '0': flags[0] |= F_ZERO; continue;
            }
            break;
        }
        if ('0' <= ch && ch <= '9') {
            width[0] = 0;
            int digits = 0;
            while ('0' <= ch && ch <= '9') {
                width[0] = width[0] * 10 + (ch - '0');
                // NEXTC
                if (pos >= len) {
                    return false;
                }
                ch = fmt.charAt(pos++);
                if (++digits >= MAXDIGITS) {
                    return false;
                }
            }
        }

        if (ch == '.') {
            // NEXTC
            if (pos >= len) {
                return false;
            }
            ch = fmt.charAt(pos++);
            prec[0] = 0;
            if ('0' <= ch && ch <= '9') {
                int digits = 0;
                while ('0' <= ch && ch <= '9') {
                    prec[0] = prec[0] * 10 + (ch - '0');
                    // NEXTC
                    if (pos >= len) {
                        return false;
                    }
                    ch = fmt.charAt(pos++);
                    if (++digits >= MAXDIGITS) {
                        return false;
                    }
                }
            }
        }
        spec[0] = ch;
        ppos[0] = pos;
        return true;
    }

    /**
     * parse_format: the FormattedValue for arg formatted by the unit at
     * ppos[0], or null if the unit isn't one this folds.
     */
    private static expr parse_format(String fmt, int[] ppos, expr arg) {
        int[] spec = {0}, flags = {0}, width = {-1}, prec = {-1};
        if (!simple_format_arg_parse(fmt, ppos, spec, flags, width, prec)) {
            // Unsupported format.
            return null;
        }
        if (spec[0] == 's' || spec[0] == 'r' || spec[0] == 'a') {
            StringBuilder buf = new StringBuilder();
            if ((flags[0] & F_LJUST) == 0 && width[0] > 0) {
                buf.append('>');
            }
            if (width[0] >= 0) {
                buf.append(width[0]);
            }
            if (prec[0] >= 0) {
                buf.append('.').append(prec[0]);
            }
            expr format_spec = null;
            if (buf.length() > 0) {
                format_spec = new Constant(buf.toString(), null, -1, -1, -1, -1);
            }
            return new FormattedValue(arg, spec[0], format_spec, arg.lineno, arg.col_offset,
                    arg.end_lineno, arg.end_col_offset);
        }
        // Unsupported format.
        return null;
    }

    /**
     * optimize_format: the JoinedStr that fmt % (elts) folds to, with node's
     * location, or node itself if it can't be folded.
     */
    private static expr optimize_format(expr node, String fmt, List<expr> elts) {
        int[] pos = {0};
        int cnt = 0;
        List<expr> seq = new ArrayList<>(asdl_seq_LEN(elts) * 2 + 1);

        while (true) {
            expr lit = parse_literal(fmt, pos);
            if (lit != null) {
                seq.add(lit);
            }

            if (pos[0] >= fmt.length()) {
                break;
            }
            if (cnt >= asdl_seq_LEN(elts)) {
                // More format units than items.
                return node;
            }
            assert fmt.charAt(pos[0]) == '%';
            pos[0]++;
            expr e = parse_format(fmt, pos, elts.get(cnt));
            cnt++;
            if (e == null) {
                return node;
            }
            seq.add(e);
        }
        if (cnt < asdl_seq_LEN(elts)) {
            // More items than format units.
            return node;
        }
        return new JoinedStr(seq, node.lineno, node.col_offset, node.end_lineno,
                node.end_col_offset);
    }

    private expr fold_binop(BinOp node) {
        if (syntax_check_only) {
            return node;
        }
        expr lhs, rhs;
        lhs = node.left;
        rhs = node.right;
        if (lhs.kind() != expr.Kind.Constant) {
            return node;
        }
        Object lv = ((Constant) lhs).value;

        if (node.op == operatorType.Mod
                && rhs.kind() == expr.Kind.Tuple
                && lv instanceof String
                && !has_starred(((Tuple) rhs).elts)) {
            return optimize_format(node, (String) lv, ((Tuple) rhs).elts);
        }

        return node;
    }

    private static int asdl_seq_LEN(List<?> seq) {
        return seq == null ? 0 : seq.size();
    }

    /* CALL_SEQ, for each kind of sequence. Null elements are skipped, as in C. */

    private void astfold_expr_seq(List<expr> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            expr elt = seq.get(i);
            if (elt != null) {
                seq.set(i, astfold_expr(elt));
            }
        }
    }

    private void astfold_stmt_seq(List<stmt> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            stmt elt = seq.get(i);
            if (elt != null) {
                astfold_stmt(elt);
            }
        }
    }

    private void astfold_pattern_seq(List<pattern> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            pattern elt = seq.get(i);
            if (elt != null) {
                astfold_pattern(elt);
            }
        }
    }

    private void astfold_keyword_seq(List<keyword> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            keyword elt = seq.get(i);
            if (elt != null) {
                astfold_keyword(elt);
            }
        }
    }

    private void astfold_comprehension_seq(List<comprehension> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            comprehension elt = seq.get(i);
            if (elt != null) {
                astfold_comprehension(elt);
            }
        }
    }

    private void astfold_arg_seq(List<arg> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            arg elt = seq.get(i);
            if (elt != null) {
                astfold_arg(elt);
            }
        }
    }

    private void astfold_type_param_seq(List<type_param> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            type_param elt = seq.get(i);
            if (elt != null) {
                astfold_type_param(elt);
            }
        }
    }

    private expr astfold_expr_opt(expr e) {
        return e == null ? null : astfold_expr(e);
    }

    private static void stmt_seq_remove_item(List<stmt> stmts, int idx) {
        if (idx >= asdl_seq_LEN(stmts)) {
            return;
        }
        stmts.remove(idx);
    }

    private static void remove_docstring(List<stmt> stmts, int idx) {
        assert Ast._PyAST_GetDocString(stmts) != null;
        // In case there's just the docstring in the body, replace it with `pass`
        // keyword, so body won't be empty.
        if (asdl_seq_LEN(stmts) == 1) {
            stmt docstring = stmts.get(0);
            stmt pass = new Pass(
                    docstring.lineno, docstring.col_offset,
                    // we know that `pass` always takes 4 chars and a single line,
                    // while docstring can span on multiple lines
                    docstring.lineno, docstring.col_offset + 4);
            stmts.set(0, pass);
            return;
        }
        // In case there are more than 1 body items, just remove the docstring.
        stmt_seq_remove_item(stmts, idx);
    }

    private void astfold_body(List<stmt> stmts) {
        boolean docstring = Ast._PyAST_GetDocString(stmts) != null;
        if (docstring && (optimize >= 2)) {
            /* remove the docstring */
            remove_docstring(stmts, 0);
            docstring = false;
        }
        astfold_stmt_seq(stmts);
        if (!docstring && Ast._PyAST_GetDocString(stmts) != null) {
            Expr st = (Expr) stmts.get(0);
            List<expr> values = new ArrayList<>(1);
            values.add(st.value);
            st.value = new JoinedStr(values, st.lineno, st.col_offset, st.end_lineno,
                    st.end_col_offset);
        }
    }

    private void astfold_mod(mod node_) {
        switch (node_.kind()) {
            case Module:
                astfold_body(((Module) node_).body);
                break;
            case Interactive:
                astfold_stmt_seq(((Interactive) node_).body);
                break;
            case Expression: {
                Expression n = (Expression) node_;
                n.body = astfold_expr(n.body);
                break;
            }
            // The following top level nodes don't participate in constant folding
            case FunctionType:
                break;
        }
    }

    /** astfold_expr: the node to put in node_'s place (node_ itself unless it was folded). */
    private expr astfold_expr(expr node_) {
        switch (node_.kind()) {
            case BoolOp:
                astfold_expr_seq(((BoolOp) node_).values);
                break;
            case BinOp: {
                BinOp n = (BinOp) node_;
                n.left = astfold_expr(n.left);
                n.right = astfold_expr(n.right);
                return fold_binop(n);
            }
            case UnaryOp: {
                UnaryOp n = (UnaryOp) node_;
                n.operand = astfold_expr(n.operand);
                break;
            }
            case Lambda: {
                Lambda n = (Lambda) node_;
                astfold_arguments(n.args);
                n.body = astfold_expr(n.body);
                break;
            }
            case IfExp: {
                IfExp n = (IfExp) node_;
                n.test = astfold_expr(n.test);
                n.body = astfold_expr(n.body);
                n.orelse = astfold_expr(n.orelse);
                break;
            }
            case Dict:
                astfold_expr_seq(((Dict) node_).keys);
                astfold_expr_seq(((Dict) node_).values);
                break;
            case Set:
                astfold_expr_seq(((org.python.pegen.ast.Set) node_).elts);
                break;
            case ListComp: {
                ListComp n = (ListComp) node_;
                n.elt = astfold_expr(n.elt);
                astfold_comprehension_seq(n.generators);
                break;
            }
            case SetComp: {
                SetComp n = (SetComp) node_;
                n.elt = astfold_expr(n.elt);
                astfold_comprehension_seq(n.generators);
                break;
            }
            case DictComp: {
                DictComp n = (DictComp) node_;
                n.key = astfold_expr(n.key);
                if (n.value != null) {
                    n.value = astfold_expr(n.value);
                }
                astfold_comprehension_seq(n.generators);
                break;
            }
            case GeneratorExp: {
                GeneratorExp n = (GeneratorExp) node_;
                n.elt = astfold_expr(n.elt);
                astfold_comprehension_seq(n.generators);
                break;
            }
            case Await: {
                Await n = (Await) node_;
                n.value = astfold_expr(n.value);
                break;
            }
            case Yield: {
                Yield n = (Yield) node_;
                n.value = astfold_expr_opt(n.value);
                break;
            }
            case YieldFrom: {
                YieldFrom n = (YieldFrom) node_;
                n.value = astfold_expr(n.value);
                break;
            }
            case Compare: {
                Compare n = (Compare) node_;
                n.left = astfold_expr(n.left);
                astfold_expr_seq(n.comparators);
                break;
            }
            case Call: {
                Call n = (Call) node_;
                n.func = astfold_expr(n.func);
                astfold_expr_seq(n.args);
                astfold_keyword_seq(n.keywords);
                break;
            }
            case FormattedValue: {
                FormattedValue n = (FormattedValue) node_;
                n.value = astfold_expr(n.value);
                n.format_spec = astfold_expr_opt(n.format_spec);
                break;
            }
            case Interpolation: {
                Interpolation n = (Interpolation) node_;
                n.value = astfold_expr(n.value);
                n.format_spec = astfold_expr_opt(n.format_spec);
                break;
            }
            case JoinedStr:
                astfold_expr_seq(((JoinedStr) node_).values);
                break;
            case TemplateStr:
                astfold_expr_seq(((TemplateStr) node_).values);
                break;
            case Attribute: {
                Attribute n = (Attribute) node_;
                n.value = astfold_expr(n.value);
                break;
            }
            case Subscript: {
                Subscript n = (Subscript) node_;
                n.value = astfold_expr(n.value);
                n.slice = astfold_expr(n.slice);
                break;
            }
            case Starred: {
                Starred n = (Starred) node_;
                n.value = astfold_expr(n.value);
                break;
            }
            case Slice: {
                Slice n = (Slice) node_;
                n.lower = astfold_expr_opt(n.lower);
                n.upper = astfold_expr_opt(n.upper);
                n.step = astfold_expr_opt(n.step);
                break;
            }
            case List:
                astfold_expr_seq(((org.python.pegen.ast.List) node_).elts);
                break;
            case Tuple:
                astfold_expr_seq(((Tuple) node_).elts);
                break;
            case Name: {
                if (syntax_check_only) {
                    break;
                }
                Name n = (Name) node_;
                if (n.ctx == expr_contextType.Load && n.id.equals("__debug__")) {
                    return make_const(node_, optimize == 0 ? Singleton.True : Singleton.False);
                }
                break;
            }
            case NamedExpr: {
                NamedExpr n = (NamedExpr) node_;
                n.value = astfold_expr(n.value);
                break;
            }
            case Constant:
                // Already a constant, nothing further to do
                break;
        }
        return node_;
    }

    private void astfold_keyword(keyword node_) {
        node_.value = astfold_expr(node_.value);
    }

    private void astfold_comprehension(comprehension node_) {
        node_.target = astfold_expr(node_.target);
        node_.iter = astfold_expr(node_.iter);
        astfold_expr_seq(node_.ifs);
    }

    private void astfold_arguments(arguments node_) {
        astfold_arg_seq(node_.posonlyargs);
        astfold_arg_seq(node_.args);
        if (node_.vararg != null) {
            astfold_arg(node_.vararg);
        }
        astfold_arg_seq(node_.kwonlyargs);
        astfold_expr_seq(node_.kw_defaults);
        if (node_.kwarg != null) {
            astfold_arg(node_.kwarg);
        }
        astfold_expr_seq(node_.defaults);
    }

    private void astfold_arg(arg node_) {
        if ((ff_features & CO_FUTURE_ANNOTATIONS) == 0) {
            node_.annotation = astfold_expr_opt(node_.annotation);
        }
    }

    private void astfold_stmt(stmt node_) {
        switch (node_.kind()) {
            case FunctionDef: {
                FunctionDef n = (FunctionDef) node_;
                astfold_type_param_seq(n.type_params);
                astfold_arguments(n.args);
                BEFORE_FUNC_BODY(node_);
                astfold_body(n.body);
                AFTER_FUNC_BODY();
                astfold_expr_seq(n.decorator_list);
                if ((ff_features & CO_FUTURE_ANNOTATIONS) == 0) {
                    n.returns = astfold_expr_opt(n.returns);
                }
                break;
            }
            case AsyncFunctionDef: {
                AsyncFunctionDef n = (AsyncFunctionDef) node_;
                astfold_type_param_seq(n.type_params);
                astfold_arguments(n.args);
                BEFORE_FUNC_BODY(node_);
                astfold_body(n.body);
                AFTER_FUNC_BODY();
                astfold_expr_seq(n.decorator_list);
                if ((ff_features & CO_FUTURE_ANNOTATIONS) == 0) {
                    n.returns = astfold_expr_opt(n.returns);
                }
                break;
            }
            case ClassDef: {
                ClassDef n = (ClassDef) node_;
                astfold_type_param_seq(n.type_params);
                astfold_expr_seq(n.bases);
                astfold_keyword_seq(n.keywords);
                astfold_body(n.body);
                astfold_expr_seq(n.decorator_list);
                break;
            }
            case Return: {
                Return n = (Return) node_;
                before_return(node_);
                n.value = astfold_expr_opt(n.value);
                break;
            }
            case Delete:
                astfold_expr_seq(((Delete) node_).targets);
                break;
            case Assign: {
                Assign n = (Assign) node_;
                astfold_expr_seq(n.targets);
                n.value = astfold_expr(n.value);
                break;
            }
            case AugAssign: {
                AugAssign n = (AugAssign) node_;
                n.target = astfold_expr(n.target);
                n.value = astfold_expr(n.value);
                break;
            }
            case AnnAssign: {
                AnnAssign n = (AnnAssign) node_;
                n.target = astfold_expr(n.target);
                if ((ff_features & CO_FUTURE_ANNOTATIONS) == 0) {
                    n.annotation = astfold_expr(n.annotation);
                }
                n.value = astfold_expr_opt(n.value);
                break;
            }
            case TypeAlias: {
                TypeAlias n = (TypeAlias) node_;
                n.name = astfold_expr(n.name);
                astfold_type_param_seq(n.type_params);
                n.value = astfold_expr(n.value);
                break;
            }
            case For: {
                For n = (For) node_;
                n.target = astfold_expr(n.target);
                n.iter = astfold_expr(n.iter);
                BEFORE_LOOP_BODY(node_);
                astfold_stmt_seq(n.body);
                AFTER_LOOP_BODY();
                astfold_stmt_seq(n.orelse);
                break;
            }
            case AsyncFor: {
                AsyncFor n = (AsyncFor) node_;
                n.target = astfold_expr(n.target);
                n.iter = astfold_expr(n.iter);
                BEFORE_LOOP_BODY(node_);
                astfold_stmt_seq(n.body);
                AFTER_LOOP_BODY();
                astfold_stmt_seq(n.orelse);
                break;
            }
            case While: {
                While n = (While) node_;
                n.test = astfold_expr(n.test);
                BEFORE_LOOP_BODY(node_);
                astfold_stmt_seq(n.body);
                AFTER_LOOP_BODY();
                astfold_stmt_seq(n.orelse);
                break;
            }
            case If: {
                If n = (If) node_;
                n.test = astfold_expr(n.test);
                astfold_stmt_seq(n.body);
                astfold_stmt_seq(n.orelse);
                break;
            }
            case With: {
                With n = (With) node_;
                astfold_withitem_seq(n.items);
                astfold_stmt_seq(n.body);
                break;
            }
            case AsyncWith: {
                AsyncWith n = (AsyncWith) node_;
                astfold_withitem_seq(n.items);
                astfold_stmt_seq(n.body);
                break;
            }
            case Raise: {
                Raise n = (Raise) node_;
                n.exc = astfold_expr_opt(n.exc);
                n.cause = astfold_expr_opt(n.cause);
                break;
            }
            case Try: {
                Try n = (Try) node_;
                astfold_stmt_seq(n.body);
                astfold_excepthandler_seq(n.handlers);
                astfold_stmt_seq(n.orelse);
                BEFORE_FINALLY(node_);
                astfold_stmt_seq(n.finalbody);
                AFTER_FINALLY();
                break;
            }
            case TryStar: {
                TryStar n = (TryStar) node_;
                astfold_stmt_seq(n.body);
                astfold_excepthandler_seq(n.handlers);
                astfold_stmt_seq(n.orelse);
                BEFORE_FINALLY(node_);
                astfold_stmt_seq(n.finalbody);
                AFTER_FINALLY();
                break;
            }
            case Assert: {
                Assert n = (Assert) node_;
                n.test = astfold_expr(n.test);
                n.msg = astfold_expr_opt(n.msg);
                break;
            }
            case Expr: {
                Expr n = (Expr) node_;
                n.value = astfold_expr(n.value);
                break;
            }
            case Match: {
                Match n = (Match) node_;
                n.subject = astfold_expr(n.subject);
                astfold_match_case_seq(n.cases);
                break;
            }
            case Break:
                before_loop_exit(node_, "break");
                break;
            case Continue:
                before_loop_exit(node_, "continue");
                break;
            // The following statements don't contain any subexpressions to be folded
            case Import:
            case ImportFrom:
            case Global:
            case Nonlocal:
            case Pass:
                break;
        }
    }

    private void astfold_excepthandler_seq(List<excepthandler> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            excepthandler elt = seq.get(i);
            if (elt != null) {
                astfold_excepthandler(elt);
            }
        }
    }

    private void astfold_excepthandler(excepthandler node_) {
        switch (node_.kind()) {
            case ExceptHandler: {
                ExceptHandler n = (ExceptHandler) node_;
                n.type = astfold_expr_opt(n.type);
                astfold_stmt_seq(n.body);
                break;
            }
        }
    }

    private void astfold_withitem_seq(List<withitem> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            withitem elt = seq.get(i);
            if (elt != null) {
                astfold_withitem(elt);
            }
        }
    }

    private void astfold_withitem(withitem node_) {
        node_.context_expr = astfold_expr(node_.context_expr);
        node_.optional_vars = astfold_expr_opt(node_.optional_vars);
    }

    /** fold_const_match_patterns: the node to put in node's place. */
    private expr fold_const_match_patterns(expr node) {
        if (syntax_check_only) {
            return node;
        }
        switch (node.kind()) {
            case UnaryOp: {
                UnaryOp n = (UnaryOp) node;
                if ((n.op == unaryopType.USub || n.op == unaryopType.UAdd) &&
                    n.operand.kind() == expr.Kind.Constant)
                {
                    Object operand = ((Constant) n.operand).value;
                    Object folded = n.op == unaryopType.USub ? PyNumber_Negative(operand) : PyNumber_Positive(operand);
                    return make_const(node, folded);
                }
                break;
            }
            case BinOp: {
                BinOp n = (BinOp) node;
                operatorType op = n.op;
                if ((op == operatorType.Add || op == operatorType.Sub)
                        && n.right.kind() == expr.Kind.Constant) {
                    n.left = fold_const_match_patterns(n.left);
                    if (n.left.kind() == expr.Kind.Constant) {
                        Object left = ((Constant) n.left).value;
                        Object right = ((Constant) n.right).value;
                        Object folded = op == operatorType.Add ? PyNumber_Add(left, right)
                                : PyNumber_Subtract(left, right);
                        return make_const(node, folded);
                    }
                }
                break;
            }
            default:
                break;
        }
        return node;
    }

    private void fold_const_match_patterns_seq(List<expr> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            expr elt = seq.get(i);
            if (elt != null) {
                seq.set(i, fold_const_match_patterns(elt));
            }
        }
    }

    private void astfold_pattern(pattern node_) {
        // Currently, this is really only used to form complex/negative numeric
        // constants in MatchValue and MatchMapping nodes
        // We still recurse into all subexpressions and subpatterns anyway
        switch (node_.kind()) {
            case MatchValue: {
                MatchValue n = (MatchValue) node_;
                n.value = fold_const_match_patterns(n.value);
                break;
            }
            case MatchSingleton:
                break;
            case MatchSequence:
                astfold_pattern_seq(((MatchSequence) node_).patterns);
                break;
            case MatchMapping: {
                MatchMapping n = (MatchMapping) node_;
                fold_const_match_patterns_seq(n.keys);
                astfold_pattern_seq(n.patterns);
                break;
            }
            case MatchClass: {
                MatchClass n = (MatchClass) node_;
                n.cls = astfold_expr(n.cls);
                astfold_pattern_seq(n.patterns);
                astfold_pattern_seq(n.kwd_patterns);
                break;
            }
            case MatchStar:
                break;
            case MatchAs: {
                MatchAs n = (MatchAs) node_;
                if (n.pattern != null) {
                    astfold_pattern(n.pattern);
                }
                break;
            }
            case MatchOr:
                astfold_pattern_seq(((MatchOr) node_).patterns);
                break;
        }
    }

    private void astfold_match_case_seq(List<match_case> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            match_case elt = seq.get(i);
            if (elt != null) {
                astfold_match_case(elt);
            }
        }
    }

    private void astfold_match_case(match_case node_) {
        astfold_pattern(node_.pattern);
        node_.guard = astfold_expr_opt(node_.guard);
        astfold_stmt_seq(node_.body);
    }

    private void astfold_type_param(type_param node_) {
        switch (node_.kind()) {
            case TypeVar: {
                TypeVar n = (TypeVar) node_;
                n.bound = astfold_expr_opt(n.bound);
                n.default_value = astfold_expr_opt(n.default_value);
                break;
            }
            case ParamSpec: {
                ParamSpec n = (ParamSpec) node_;
                n.default_value = astfold_expr_opt(n.default_value);
                break;
            }
            case TypeVarTuple: {
                TypeVarTuple n = (TypeVarTuple) node_;
                n.default_value = astfold_expr_opt(n.default_value);
                break;
            }
        }
    }

    /*
     * The number protocol for the values a match pattern can fold: int
     * (BigInteger), float (Double) and complex (Complex), with CPython's
     * results. Each returns null where CPython raises (TypeError, or
     * OverflowError for an int too large for a float), which make_const
     * reads as "not folded".
     */

    /** PyNumber_Negative */
    static Object PyNumber_Negative(Object v) {
        if (v instanceof BigInteger) {
            return ((BigInteger) v).negate();
        } else if (v instanceof Double) {
            return -(Double) v;
        } else if (v instanceof Complex) {
            // complex_neg
            Complex c = (Complex) v;
            return new Complex(-c.real, -c.imag);
        }
        return null;
    }

    /** PyNumber_Positive */
    static Object PyNumber_Positive(Object v) {
        if (v instanceof BigInteger || v instanceof Double || v instanceof Complex) {
            // long_long, float_float, complex_pos: an exact number is returned itself
            return v;
        }
        return null;
    }

    /** PyNumber_Add */
    static Object PyNumber_Add(Object v, Object w) {
        return binary_op(v, w, false);
    }

    /** PyNumber_Subtract */
    static Object PyNumber_Subtract(Object v, Object w) {
        return binary_op(v, w, true);
    }

    private static Object binary_op(Object v, Object w, boolean sub) {
        if (v instanceof BigInteger && w instanceof BigInteger) {
            // long_add, long_sub
            BigInteger a = (BigInteger) v, b = (BigInteger) w;
            return sub ? a.subtract(b) : a.add(b);
        } else if (v instanceof Complex || w instanceof Complex) {
            // complex_add, complex_sub (COMPLEX_BINOP): a real operand isn't
            // made complex first, so its imaginary part isn't added (C11 Annex G).
            if (v instanceof Complex && w instanceof Complex) {
                Complex a = (Complex) v, b = (Complex) w;
                return sub ? new Complex(a.real - b.real, a.imag - b.imag) // _Py_c_diff
                        : new Complex(a.real + b.real, a.imag + b.imag); // _Py_c_sum
            } else if (w instanceof Complex) {
                Double a = real_to_double(v);
                if (a == null) {
                    return null;
                }
                Complex b = (Complex) w;
                return sub ? new Complex(a - b.real, -b.imag) // _Py_rc_diff
                        : new Complex(b.real + a, b.imag); // _Py_rc_sum
            } else {
                Complex a = (Complex) v;
                Double b = real_to_double(w);
                if (b == null) {
                    return null;
                }
                return sub ? new Complex(a.real - b, a.imag) // _Py_cr_diff
                        : new Complex(a.real + b, a.imag); // _Py_cr_sum
            }
        } else if (v instanceof Double || w instanceof Double) {
            // float_add, float_sub
            Double a = real_to_double(v), b = real_to_double(w);
            if (a == null || b == null) {
                return null;
            }
            return sub ? a - b : a + b;
        }
        return null;
    }

    /**
     * real_to_double: a float's value, or an int's as PyLong_AsDouble gives
     * it (correctly rounded); null for an int too large (OverflowError) or
     * anything else.
     */
    private static Double real_to_double(Object v) {
        if (v instanceof Double) {
            return (Double) v;
        } else if (v instanceof BigInteger) {
            double d = ((BigInteger) v).doubleValue();
            return Double.isInfinite(d) ? null : d;
        }
        return null;
    }

    /**
     * _PyAST_Preprocess: preprocesses mod in place. With syntax_check_only
     * nothing is folded (only docstrings go, at optimize 2). Warnings go to
     * warnings when enable_warnings is set; one the handler makes an error
     * is thrown as a SyntaxError.
     *
     * <p>It runs on a thread with a large stack (LargeStack), as the parser
     * does, so that any tree the parser makes fits; the warning handler runs
     * there too. C's Py_EnterRecursiveCall guards the recursion: here a
     * StackOverflowError becomes the RecursionError C raises, without the
     * stack use C puts in its message.
     */
    public static void _PyAST_Preprocess(mod mod, String filename, int optimize,
            int ff_features, boolean syntax_check_only, boolean enable_warnings, String module,
            Parser.WarningHandler warnings) {
        AstPreprocess state = new AstPreprocess(filename, module, optimize, ff_features,
                syntax_check_only, enable_warnings, warnings);
        LargeStack.call(() -> {
            try {
                state.astfold_mod(mod);
            } catch (StackOverflowError e) {
                throw new PythonSyntaxError("RecursionError",
                        "Stack overflow during compilation");
            }
            return null;
        });
    }
}
