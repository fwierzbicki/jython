package org.python.pegen.compile;

import java.math.BigInteger;
import java.util.List;

import org.python.pegen.ast.Attribute;
import org.python.pegen.ast.Await;
import org.python.pegen.ast.BinOp;
import org.python.pegen.ast.BoolOp;
import org.python.pegen.ast.Call;
import org.python.pegen.ast.Compare;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Constant;
import org.python.pegen.ast.Dict;
import org.python.pegen.ast.DictComp;
import org.python.pegen.ast.FormattedValue;
import org.python.pegen.ast.GeneratorExp;
import org.python.pegen.ast.IfExp;
import org.python.pegen.ast.Interpolation;
import org.python.pegen.ast.JoinedStr;
import org.python.pegen.ast.Lambda;
import org.python.pegen.ast.ListComp;
import org.python.pegen.ast.Name;
import org.python.pegen.ast.NamedExpr;
import org.python.pegen.ast.SetComp;
import org.python.pegen.ast.Singleton;
import org.python.pegen.ast.Slice;
import org.python.pegen.ast.Starred;
import org.python.pegen.ast.Subscript;
import org.python.pegen.ast.TemplateStr;
import org.python.pegen.ast.Tuple;
import org.python.pegen.ast.UnaryOp;
import org.python.pegen.ast.Yield;
import org.python.pegen.ast.YieldFrom;
import org.python.pegen.ast.arg;
import org.python.pegen.ast.arguments;
import org.python.pegen.ast.boolopType;
import org.python.pegen.ast.cmpopType;
import org.python.pegen.ast.comprehension;
import org.python.pegen.ast.keyword;
import org.python.pegen.ast.base.expr;

/**
 * A port of Python/ast_unparse.c: the limited unparser codegen uses to turn
 * annotations back into strings under {@code from __future__ import
 * annotations} (_PyAST_ExprAsUnicode). C's names and order are kept; a
 * StringBuilder is C's PyUnicodeWriter, and the C functions' only failures
 * are SystemErrors for impossible trees.
 */
public final class AstUnparse {

    private AstUnparse() {}

    private static void append_repr(StringBuilder writer, Object obj) {
        String repr = Repr.repr(obj);

        if ((obj instanceof Double && Double.isInfinite((Double) obj)) ||
            obj instanceof Complex)
        {
            repr = repr.replace("inf", "1e309");  // evaluates to inf
        }

        writer.append(repr);
    }

    /* Priority levels */

    private static final int PR_TUPLE = 0;
    private static final int PR_TEST = 1;            /* 'if'-'else', 'lambda' */
    private static final int PR_OR = 2;              /* 'or' */
    private static final int PR_AND = 3;             /* 'and' */
    private static final int PR_NOT = 4;             /* 'not' */
    private static final int PR_CMP = 5;             /* '<', '>', '==', '>=', '<=', '!=',
                                                        'in', 'not in', 'is', 'is not' */
    private static final int PR_EXPR = 6;
    private static final int PR_BOR = PR_EXPR;       /* '|' */
    private static final int PR_BXOR = 7;            /* '^' */
    private static final int PR_BAND = 8;            /* '&' */
    private static final int PR_SHIFT = 9;           /* '<<', '>>' */
    private static final int PR_ARITH = 10;          /* '+', '-' */
    private static final int PR_TERM = 11;           /* '*', '@', '/', '%', '//' */
    private static final int PR_FACTOR = 12;         /* unary '+', '-', '~' */
    private static final int PR_POWER = 13;          /* '**' */
    private static final int PR_AWAIT = 14;          /* 'await' */
    private static final int PR_ATOM = 15;

    private static void APPEND_STR_IF(StringBuilder writer, boolean cond, String str) {
        if (cond) {
            writer.append(str);
        }
    }

    private static int LEN(List<?> seq) {
        return seq == null ? 0 : seq.size();
    }

    private static void append_ast_boolop(StringBuilder writer, BoolOp e, int level) {
        String op = (e.op == boolopType.And) ? " and " : " or ";
        int pr = (e.op == boolopType.And) ? PR_AND : PR_OR;

        APPEND_STR_IF(writer, level > pr, "(");

        List<expr> values = e.values;
        int value_count = LEN(values);

        for (int i = 0; i < value_count; ++i) {
            APPEND_STR_IF(writer, i > 0, op);
            append_ast_expr(writer, values.get(i), pr + 1);
        }

        APPEND_STR_IF(writer, level > pr, ")");
    }

    private static void append_ast_binop(StringBuilder writer, BinOp e, int level) {
        String op;
        int pr;
        boolean rassoc = false;  /* is right-associative? */

        switch (e.op) {
        case Add: op = " + "; pr = PR_ARITH; break;
        case Sub: op = " - "; pr = PR_ARITH; break;
        case Mult: op = " * "; pr = PR_TERM; break;
        case MatMult: op = " @ "; pr = PR_TERM; break;
        case Div: op = " / "; pr = PR_TERM; break;
        case Mod: op = " % "; pr = PR_TERM; break;
        case LShift: op = " << "; pr = PR_SHIFT; break;
        case RShift: op = " >> "; pr = PR_SHIFT; break;
        case BitOr: op = " | "; pr = PR_BOR; break;
        case BitXor: op = " ^ "; pr = PR_BXOR; break;
        case BitAnd: op = " & "; pr = PR_BAND; break;
        case FloorDiv: op = " // "; pr = PR_TERM; break;
        case Pow: op = " ** "; pr = PR_POWER; rassoc = true; break;
        default:
            throw new IllegalStateException("unknown binary operator");
        }

        APPEND_STR_IF(writer, level > pr, "(");
        append_ast_expr(writer, e.left, pr + (rassoc ? 1 : 0));
        writer.append(op);
        append_ast_expr(writer, e.right, pr + (!rassoc ? 1 : 0));
        APPEND_STR_IF(writer, level > pr, ")");
    }

    private static void append_ast_unaryop(StringBuilder writer, UnaryOp e, int level) {
        String op;
        int pr;

        switch (e.op) {
        case Invert: op = "~"; pr = PR_FACTOR; break;
        case Not: op = "not "; pr = PR_NOT; break;
        case UAdd: op = "+"; pr = PR_FACTOR; break;
        case USub: op = "-"; pr = PR_FACTOR; break;
        default:
            throw new IllegalStateException("unknown unary operator");
        }

        APPEND_STR_IF(writer, level > pr, "(");
        writer.append(op);
        append_ast_expr(writer, e.operand, pr);
        APPEND_STR_IF(writer, level > pr, ")");
    }

    private static void append_ast_arg(StringBuilder writer, arg arg) {
        writer.append(arg.arg);
        if (arg.annotation != null) {
            writer.append(": ");
            append_ast_expr(writer, arg.annotation, PR_TEST);
        }
    }

    private static void append_ast_args(StringBuilder writer, arguments args) {
        boolean first;
        int i, di, arg_count, posonlyarg_count, default_count;

        first = true;

        /* positional-only and positional arguments with defaults */
        posonlyarg_count = LEN(args.posonlyargs);
        arg_count = LEN(args.args);
        default_count = LEN(args.defaults);
        for (i = 0; i < posonlyarg_count + arg_count; i++) {
            APPEND_STR_IF(writer, !first, ", ");
            first = false;
            if (i < posonlyarg_count){
                append_ast_arg(writer, args.posonlyargs.get(i));
            } else {
                append_ast_arg(writer, args.args.get(i-posonlyarg_count));
            }

            di = i - posonlyarg_count - arg_count + default_count;
            if (di >= 0) {
                writer.append('=');
                append_ast_expr(writer, args.defaults.get(di), PR_TEST);
            }
            if (posonlyarg_count != 0 && i + 1 == posonlyarg_count) {
                writer.append(", /");
            }
        }

        /* vararg, or bare '*' if no varargs but keyword-only arguments present */
        if (args.vararg != null || LEN(args.kwonlyargs) != 0) {
            APPEND_STR_IF(writer, !first, ", ");
            first = false;
            writer.append("*");
            if (args.vararg != null) {
                append_ast_arg(writer, args.vararg);
            }
        }

        /* keyword-only arguments */
        arg_count = LEN(args.kwonlyargs);
        default_count = LEN(args.kw_defaults);
        for (i = 0; i < arg_count; i++) {
            APPEND_STR_IF(writer, !first, ", ");
            first = false;
            append_ast_arg(writer, args.kwonlyargs.get(i));

            di = i - arg_count + default_count;
            if (di >= 0) {
                expr default_ = args.kw_defaults.get(di);
                if (default_ != null) {
                    writer.append('=');
                    append_ast_expr(writer, default_, PR_TEST);
                }
            }
        }

        /* **kwargs */
        if (args.kwarg != null) {
            APPEND_STR_IF(writer, !first, ", ");
            first = false;
            writer.append("**");
            append_ast_arg(writer, args.kwarg);
        }
    }

    private static void append_ast_lambda(StringBuilder writer, Lambda e, int level) {
        APPEND_STR_IF(writer, level > PR_TEST, "(");
        int n_positional = (LEN(e.args.args) +
                            LEN(e.args.posonlyargs));
        writer.append(n_positional != 0 ? "lambda " : "lambda");
        append_ast_args(writer, e.args);
        writer.append(": ");
        append_ast_expr(writer, e.body, PR_TEST);
        APPEND_STR_IF(writer, level > PR_TEST, ")");
    }

    private static void append_ast_ifexp(StringBuilder writer, IfExp e, int level) {
        APPEND_STR_IF(writer, level > PR_TEST, "(");
        append_ast_expr(writer, e.body, PR_TEST + 1);
        writer.append(" if ");
        append_ast_expr(writer, e.test, PR_TEST + 1);
        writer.append(" else ");
        append_ast_expr(writer, e.orelse, PR_TEST);
        APPEND_STR_IF(writer, level > PR_TEST, ")");
    }

    private static void append_ast_dict(StringBuilder writer, Dict e) {
        int i, value_count;
        expr key_node;

        writer.append('{');
        value_count = LEN(e.values);

        for (i = 0; i < value_count; i++) {
            APPEND_STR_IF(writer, i > 0, ", ");
            key_node = e.keys.get(i);
            if (key_node != null) {
                append_ast_expr(writer, key_node, PR_TEST);
                writer.append(": ");
                append_ast_expr(writer, e.values.get(i), PR_TEST);
            }
            else {
                writer.append("**");
                append_ast_expr(writer, e.values.get(i), PR_EXPR);
            }
        }

        writer.append('}');
    }

    private static void append_ast_set(StringBuilder writer, org.python.pegen.ast.Set e) {
        writer.append('{');
        int elem_count = LEN(e.elts);
        for (int i = 0; i < elem_count; i++) {
            APPEND_STR_IF(writer, i > 0, ", ");
            append_ast_expr(writer, e.elts.get(i), PR_TEST);
        }

        writer.append('}');
    }

    private static void append_ast_list(StringBuilder writer, org.python.pegen.ast.List e) {
        writer.append('[');
        int elem_count = LEN(e.elts);
        for (int i = 0; i < elem_count; i++) {
            APPEND_STR_IF(writer, i > 0, ", ");
            append_ast_expr(writer, e.elts.get(i), PR_TEST);
        }

        writer.append(']');
    }

    private static void append_ast_tuple(StringBuilder writer, Tuple e, int level) {
        int elem_count = LEN(e.elts);

        if (elem_count == 0) {
            writer.append("()");
            return;
        }

        APPEND_STR_IF(writer, level > PR_TUPLE, "(");

        for (int i = 0; i < elem_count; i++) {
            APPEND_STR_IF(writer, i > 0, ", ");
            append_ast_expr(writer, e.elts.get(i), PR_TEST);
        }

        APPEND_STR_IF(writer, elem_count == 1, ",");
        APPEND_STR_IF(writer, level > PR_TUPLE, ")");
    }

    private static void append_ast_comprehension(StringBuilder writer, comprehension gen) {
        writer.append(gen.is_async != 0 ? " async for " : " for ");
        append_ast_expr(writer, gen.target, PR_TUPLE);
        writer.append(" in ");
        append_ast_expr(writer, gen.iter, PR_TEST + 1);

        int if_count = LEN(gen.ifs);
        for (int i = 0; i < if_count; i++) {
            writer.append(" if ");
            append_ast_expr(writer, gen.ifs.get(i), PR_TEST + 1);
        }
    }

    private static void append_ast_comprehensions(StringBuilder writer,
            List<comprehension> comprehensions) {
        int gen_count = LEN(comprehensions);

        for (int i = 0; i < gen_count; i++) {
            append_ast_comprehension(writer, comprehensions.get(i));
        }
    }

    private static void append_ast_genexp(StringBuilder writer, GeneratorExp e) {
        writer.append('(');
        append_ast_expr(writer, e.elt, PR_TEST);
        append_ast_comprehensions(writer, e.generators);
        writer.append(')');
    }

    private static void append_ast_listcomp(StringBuilder writer, ListComp e) {
        writer.append('[');
        append_ast_expr(writer, e.elt, PR_TEST);
        append_ast_comprehensions(writer, e.generators);
        writer.append(']');
    }

    private static void append_ast_setcomp(StringBuilder writer, SetComp e) {
        writer.append('{');
        append_ast_expr(writer, e.elt, PR_TEST);
        append_ast_comprehensions(writer, e.generators);
        writer.append('}');
    }

    private static void append_ast_dictcomp(StringBuilder writer, DictComp e) {
        writer.append('{');
        if (e.value != null) {
            append_ast_expr(writer, e.key, PR_TEST);
            writer.append(": ");
            append_ast_expr(writer, e.value, PR_TEST);
        }
        else {
            writer.append("**");
            append_ast_expr(writer, e.key, PR_TEST);
        }
        append_ast_comprehensions(writer, e.generators);
        writer.append('}');
    }

    private static void append_ast_compare(StringBuilder writer, Compare e, int level) {
        String op;

        APPEND_STR_IF(writer, level > PR_CMP, "(");

        List<expr> comparators = e.comparators;
        List<cmpopType> ops = e.ops;
        int comparator_count = LEN(comparators);
        assert comparator_count > 0;
        assert comparator_count == LEN(ops);

        append_ast_expr(writer, e.left, PR_CMP + 1);

        for (int i = 0; i < comparator_count; i++) {
            switch (ops.get(i)) {
            case Eq:
                op = " == ";
                break;
            case NotEq:
                op = " != ";
                break;
            case Lt:
                op = " < ";
                break;
            case LtE:
                op = " <= ";
                break;
            case Gt:
                op = " > ";
                break;
            case GtE:
                op = " >= ";
                break;
            case Is:
                op = " is ";
                break;
            case IsNot:
                op = " is not ";
                break;
            case In:
                op = " in ";
                break;
            case NotIn:
                op = " not in ";
                break;
            default:
                throw new IllegalStateException("unexpected comparison kind");
            }

            writer.append(op);
            append_ast_expr(writer, comparators.get(i), PR_CMP + 1);
        }

        APPEND_STR_IF(writer, level > PR_CMP, ")");
    }

    private static void append_ast_keyword(StringBuilder writer, keyword kw) {
        if (kw.arg == null) {
            writer.append("**");
        }
        else {
            writer.append(kw.arg);
            writer.append('=');
        }

        append_ast_expr(writer, kw.value, PR_TEST);
    }

    private static void append_ast_call(StringBuilder writer, Call e) {
        boolean first;
        int i, arg_count, kw_count;

        append_ast_expr(writer, e.func, PR_ATOM);

        arg_count = LEN(e.args);
        kw_count = LEN(e.keywords);
        if (arg_count == 1 && kw_count == 0) {
            expr expr = e.args.get(0);
            if (expr instanceof GeneratorExp) {
                /* Special case: a single generator expression. */
                append_ast_genexp(writer, (GeneratorExp) expr);
                return;
            }
        }

        writer.append('(');

        first = true;
        for (i = 0; i < arg_count; i++) {
            APPEND_STR_IF(writer, !first, ", ");
            first = false;
            append_ast_expr(writer, e.args.get(i), PR_TEST);
        }

        for (i = 0; i < kw_count; i++) {
            APPEND_STR_IF(writer, !first, ", ");
            first = false;
            append_ast_keyword(writer, e.keywords.get(i));
        }

        writer.append(')');
    }

    private static String escape_braces(String orig) {
        return orig.replace("{", "{{").replace("}", "}}");
    }

    private static void append_fstring_unicode(StringBuilder writer, Object unicode) {
        writer.append(escape_braces((String) unicode));
    }

    private static void append_fstring_element(StringBuilder writer, expr e,
            boolean is_format_spec) {
        switch (e.kind()) {
        case Constant:
            append_fstring_unicode(writer, ((Constant) e).value);
            return;
        case JoinedStr:
            append_joinedstr(writer, (JoinedStr) e, is_format_spec);
            return;
        case TemplateStr:
            append_templatestr(writer, (TemplateStr) e);
            return;
        case FormattedValue:
            append_formattedvalue(writer, (FormattedValue) e);
            return;
        case Interpolation:
            append_interpolation(writer, (Interpolation) e);
            return;
        default:
            throw new IllegalStateException(
                    "unknown expression kind inside f-string or t-string");
        }
    }

    /* Build body separately to enable wrapping the entire stream of Strs,
       Constants and FormattedValues in one opening and one closing quote. */
    private static String build_ftstring_body(List<expr> values, boolean is_format_spec) {
        StringBuilder body_writer = new StringBuilder();

        int value_count = LEN(values);
        for (int i = 0; i < value_count; ++i) {
            append_fstring_element(body_writer, values.get(i), is_format_spec);
        }

        return body_writer.toString();
    }

    private static void append_templatestr(StringBuilder writer, TemplateStr e) {
        String body = build_ftstring_body(e.values, false);

        writer.append("t");
        append_repr(writer, body);
    }

    private static void append_joinedstr(StringBuilder writer, JoinedStr e,
            boolean is_format_spec) {
        String body = build_ftstring_body(e.values, is_format_spec);

        if (!is_format_spec) {
            writer.append("f");
            append_repr(writer, body);
        }
        else {
            writer.append(body);
        }
    }

    private static void append_interpolation_str(StringBuilder writer, Object str) {
        String s = (String) str;
        String outer_brace = "{";
        if (s.startsWith("{")) {
            /* Expression starts with a brace, split it with a space from the outer
               one. */
            outer_brace = "{ ";
        }
        writer.append(outer_brace);
        writer.append(s);
    }

    private static void append_interpolation_value(StringBuilder writer, expr e) {
        /* Grammar allows PR_TUPLE, but use >PR_TEST for adding parenthesis
           around a lambda with ':' */
        String temp_fv_str = expr_as_unicode(e, PR_TEST + 1);
        append_interpolation_str(writer, temp_fv_str);
    }

    private static void append_interpolation_conversion(StringBuilder writer, int conversion) {
        if (conversion < 0) {
            return;
        }

        String conversion_str;
        switch (conversion) {
        case 'a':
            conversion_str = "!a";
            break;
        case 'r':
            conversion_str = "!r";
            break;
        case 's':
            conversion_str = "!s";
            break;
        default:
            throw new IllegalStateException("unknown f-value conversion kind");
        }
        writer.append(conversion_str);
    }

    private static void append_interpolation_format_spec(StringBuilder writer, expr e) {
        if (e != null) {
            writer.append(':');
            append_fstring_element(writer, e, true);
        }
    }

    private static void append_interpolation(StringBuilder writer, Interpolation e) {
        append_interpolation_str(writer, e.str);

        append_interpolation_conversion(writer, e.conversion);

        append_interpolation_format_spec(writer, e.format_spec);

        writer.append("}");
    }

    private static void append_formattedvalue(StringBuilder writer, FormattedValue e) {
        append_interpolation_value(writer, e.value);

        append_interpolation_conversion(writer, e.conversion);

        append_interpolation_format_spec(writer, e.format_spec);

        writer.append('}');
    }

    private static void append_ast_constant(StringBuilder writer, Object constant) {
        if (constant instanceof PyTuple) {
            Object[] items = ((PyTuple) constant).items;
            int elem_count = items.length;
            writer.append('(');
            for (int i = 0; i < elem_count; i++) {
                APPEND_STR_IF(writer, i > 0, ", ");
                append_ast_constant(writer, items[i]);
            }

            APPEND_STR_IF(writer, elem_count == 1, ",");
            writer.append(')');
            return;
        }
        append_repr(writer, constant);
    }

    private static void append_ast_attribute(StringBuilder writer, Attribute e) {
        String period;
        expr v = e.value;
        append_ast_expr(writer, v, PR_ATOM);

        /* Special case: integers require a space for attribute access to be
           unambiguous. */
        if (v instanceof Constant && ((Constant) v).value instanceof BigInteger) {
            period = " .";
        }
        else {
            period = ".";
        }
        writer.append(period);

        writer.append(e.attr);
    }

    private static void append_ast_slice(StringBuilder writer, Slice e) {
        if (e.lower != null) {
            append_ast_expr(writer, e.lower, PR_TEST);
        }

        writer.append(':');

        if (e.upper != null) {
            append_ast_expr(writer, e.upper, PR_TEST);
        }

        if (e.step != null) {
            writer.append(':');
            append_ast_expr(writer, e.step, PR_TEST);
        }
    }

    private static void append_ast_subscript(StringBuilder writer, Subscript e) {
        append_ast_expr(writer, e.value, PR_ATOM);
        writer.append('[');
        append_ast_expr(writer, e.slice, PR_TUPLE);
        writer.append(']');
    }

    private static void append_ast_starred(StringBuilder writer, Starred e) {
        writer.append('*');
        append_ast_expr(writer, e.value, PR_EXPR);
    }

    private static void append_ast_yield(StringBuilder writer, Yield e) {
        if (e.value == null) {
            writer.append("(yield)");
            return;
        }

        writer.append("(yield ");
        append_ast_expr(writer, e.value, PR_TEST);
        writer.append(')');
    }

    private static void append_ast_yield_from(StringBuilder writer, YieldFrom e) {
        writer.append("(yield from ");
        append_ast_expr(writer, e.value, PR_TEST);
        writer.append(')');
    }

    private static void append_ast_await(StringBuilder writer, Await e, int level) {
        APPEND_STR_IF(writer, level > PR_AWAIT, "(");
        writer.append("await ");
        append_ast_expr(writer, e.value, PR_ATOM);
        APPEND_STR_IF(writer, level > PR_AWAIT, ")");
    }

    private static void append_named_expr(StringBuilder writer, NamedExpr e, int level) {
        APPEND_STR_IF(writer, level > PR_TUPLE, "(");
        append_ast_expr(writer, e.target, PR_ATOM);
        writer.append(" := ");
        append_ast_expr(writer, e.value, PR_ATOM);
        APPEND_STR_IF(writer, level > PR_TUPLE, ")");
    }

    private static void append_ast_expr(StringBuilder writer, expr e, int level) {
        switch (e.kind()) {
        case BoolOp:
            append_ast_boolop(writer, (BoolOp) e, level);
            return;
        case BinOp:
            append_ast_binop(writer, (BinOp) e, level);
            return;
        case UnaryOp:
            append_ast_unaryop(writer, (UnaryOp) e, level);
            return;
        case Lambda:
            append_ast_lambda(writer, (Lambda) e, level);
            return;
        case IfExp:
            append_ast_ifexp(writer, (IfExp) e, level);
            return;
        case Dict:
            append_ast_dict(writer, (Dict) e);
            return;
        case Set:
            append_ast_set(writer, (org.python.pegen.ast.Set) e);
            return;
        case GeneratorExp:
            append_ast_genexp(writer, (GeneratorExp) e);
            return;
        case ListComp:
            append_ast_listcomp(writer, (ListComp) e);
            return;
        case SetComp:
            append_ast_setcomp(writer, (SetComp) e);
            return;
        case DictComp:
            append_ast_dictcomp(writer, (DictComp) e);
            return;
        case Yield:
            append_ast_yield(writer, (Yield) e);
            return;
        case YieldFrom:
            append_ast_yield_from(writer, (YieldFrom) e);
            return;
        case Await:
            append_ast_await(writer, (Await) e, level);
            return;
        case Compare:
            append_ast_compare(writer, (Compare) e, level);
            return;
        case Call:
            append_ast_call(writer, (Call) e);
            return;
        case Constant: {
            Constant c = (Constant) e;
            if (c.value == Singleton.Ellipsis) {
                writer.append("...");
                return;
            }
            if (c.kind != null) {
                writer.append(c.kind);
            }
            append_ast_constant(writer, c.value);
            return;
        }
        case JoinedStr:
            append_joinedstr(writer, (JoinedStr) e, false);
            return;
        case TemplateStr:
            append_templatestr(writer, (TemplateStr) e);
            return;
        case FormattedValue:
            append_formattedvalue(writer, (FormattedValue) e);
            return;
        case Interpolation:
            append_interpolation(writer, (Interpolation) e);
            return;
        /* The following exprs can be assignment targets. */
        case Attribute:
            append_ast_attribute(writer, (Attribute) e);
            return;
        case Subscript:
            append_ast_subscript(writer, (Subscript) e);
            return;
        case Starred:
            append_ast_starred(writer, (Starred) e);
            return;
        case Slice:
            append_ast_slice(writer, (Slice) e);
            return;
        case Name:
            writer.append(((Name) e).id);
            return;
        case List:
            append_ast_list(writer, (org.python.pegen.ast.List) e);
            return;
        case Tuple:
            append_ast_tuple(writer, (Tuple) e, level);
            return;
        case NamedExpr:
            append_named_expr(writer, (NamedExpr) e, level);
            return;
        }
        throw new IllegalStateException("unknown expression kind");
    }

    private static String expr_as_unicode(expr e, int level) {
        StringBuilder writer = new StringBuilder();
        append_ast_expr(writer, e, level);
        return writer.toString();
    }

    public static String _PyAST_ExprAsUnicode(expr e) {
        return expr_as_unicode(e, PR_TEST);
    }
}
