package org.python.pegen;

import java.util.List;

import org.python.pegen.ast.*;
import org.python.pegen.ast.base.*;

/**
 * The helpers grammar actions call, under their C names: the macros and
 * inline functions of CPython's Parser/pegen.h, and the _PyPegen_* functions
 * of Parser/action_helpers.c, pegen.c and pegen_errors.c.
 *
 * <p>The pegen.h part is ported. The _PyPegen_* functions below "Not yet
 * ported" are stubs whose signatures were derived once from the C prototypes
 * (C types mapped as in src/pegen/tools/java_types.py); they return
 * null/0/false. Until they are ported, a parser generated with actions
 * compiles but builds no AST. Port each function in place, keeping its name.
 *
 * <p>GeneratedParser imports all of this statically.
 */
public final class ActionHelpers {

    private ActionHelpers() {}

    // ---- Constants ----

    /** pegen.h CURRENT_POS: "the position of the current token" in error locations. */
    public static final int CURRENT_POS = -5;

    /** pegen.h TARGETS_TYPE */
    public enum TARGETS_TYPE {
        STAR_TARGETS, DEL_TARGETS, FOR_TARGETS
    }

    public static final TARGETS_TYPE STAR_TARGETS = TARGETS_TYPE.STAR_TARGETS;
    public static final TARGETS_TYPE DEL_TARGETS = TARGETS_TYPE.DEL_TARGETS;
    public static final TARGETS_TYPE FOR_TARGETS = TARGETS_TYPE.FOR_TARGETS;

    /** Exception types, by name. TODO: Jython's exception types once errors are real. */
    public static final String PyExc_SyntaxError = "SyntaxError";
    public static final String PyExc_IndentationError = "IndentationError";

    /** CPython's singletons, as Constant values. */
    public static final Singleton Py_None = Singleton.None;
    public static final Singleton Py_True = Singleton.True;
    public static final Singleton Py_False = Singleton.False;
    public static final Singleton Py_Ellipsis = Singleton.Ellipsis;

    // ---- pegen.h structs (field types as JavaTypeMap maps them) ----

    public static final class CmpopExprPair {
        public cmpopType cmpop;
        public expr expr;
    }

    public static final class KeyValuePair {
        public expr key;
        public expr value;
    }

    public static final class KeyPatternPair {
        public expr key;
        public pattern pattern;
    }

    public static final class NameDefaultPair {
        public arg arg;
        public expr value;
    }

    public static final class SlashWithDefault {
        public List<arg> plain_names;
        /** asdl_seq* of NameDefaultPair's */
        public List<Object> names_with_defaults;
    }

    public static final class StarEtc {
        public arg vararg;
        /** asdl_seq* of NameDefaultPair's */
        public List<Object> kwonlyargs;
        public arg kwarg;
    }

    public static final class AugOperator {
        public operatorType kind;
    }

    public static final class KeywordOrStarred {
        public Object element;
        public int is_keyword;
    }

    public static final class ResultTokenWithMetadata {
        public Object result;
        public Object metadata;
    }

    // ---- pegen.h macros and inline functions ----
    // A macro's type argument becomes a cast at the call site; macros that use
    // the parser implicitly take it as their first argument.

    /** CHECK(type, result), i.e. CHECK_CALL */
    public static <T> T CHECK(Parser p, T result) {
        if (result == null) {
            p.error_indicator = true;
        }
        return result;
    }

    /** CHECK_NULL_ALLOWED(type, result), i.e. CHECK_CALL_NULL_ALLOWED */
    public static <T> T CHECK_NULL_ALLOWED(Parser p, T result) {
        if (result == null && p.errorOccurred()) {
            p.error_indicator = true;
        }
        return result;
    }

    /** CHECK_VERSION(type, version, msg, node), i.e. INVALID_VERSION_CHECK */
    public static <T> T CHECK_VERSION(Parser p, int version, String msg, T node) {
        if (node == null) {
            p.error_indicator = true;
            return null;
        }
        if (p.feature_version < version) {
            p.error_indicator = true;
            RAISE_SYNTAX_ERROR(p, "%s only supported in Python 3.%i and greater", msg, version);
            return null;
        }
        return node;
    }

    public static String NEW_TYPE_COMMENT(Parser p, Token tc) {
        if (tc == null) {
            return null;
        }
        String tco = _PyPegen_new_type_comment(p, tc.string);
        if (tco == null) {
            p.error_indicator = true; // Inline CHECK_CALL
            return null;
        }
        return tco;
    }

    public static Object RAISE_ERROR_KNOWN_LOCATION(Parser p, Object errtype, int lineno,
            int col_offset, int end_lineno, int end_col_offset, String errmsg, Object... args) {
        int _col_offset = col_offset == CURRENT_POS ? CURRENT_POS : col_offset + 1;
        int _end_col_offset = end_col_offset == CURRENT_POS ? CURRENT_POS : end_col_offset + 1;
        _PyPegen_raise_error_known_location(p, errtype, lineno, _col_offset, end_lineno,
                _end_col_offset, errmsg, args);
        return null;
    }

    public static Object RAISE_SYNTAX_ERROR(Parser p, String msg, Object... args) {
        return _PyPegen_raise_error(p, PyExc_SyntaxError, 0, msg, args);
    }

    public static Object RAISE_INDENTATION_ERROR(Parser p, String msg, Object... args) {
        return _PyPegen_raise_error(p, PyExc_IndentationError, 0, msg, args);
    }

    public static Object RAISE_SYNTAX_ERROR_ON_NEXT_TOKEN(Parser p, String msg, Object... args) {
        return _PyPegen_raise_error(p, PyExc_SyntaxError, 1, msg, args);
    }

    public static Object RAISE_SYNTAX_ERROR_KNOWN_RANGE(Parser p, Object a, Object b, String msg,
            Object... args) {
        int[] start = location(a), end = location(b);
        return RAISE_ERROR_KNOWN_LOCATION(p, PyExc_SyntaxError, start[0], start[1], end[2], end[3],
                msg, args);
    }

    public static Object RAISE_SYNTAX_ERROR_KNOWN_LOCATION(Parser p, Object a, String msg,
            Object... args) {
        int[] loc = location(a);
        return RAISE_ERROR_KNOWN_LOCATION(p, PyExc_SyntaxError, loc[0], loc[1], loc[2], loc[3],
                msg, args);
    }

    public static Object RAISE_SYNTAX_ERROR_STARTING_FROM(Parser p, Object a, String msg,
            Object... args) {
        int[] loc = location(a);
        return RAISE_ERROR_KNOWN_LOCATION(p, PyExc_SyntaxError, loc[0], loc[1], CURRENT_POS,
                CURRENT_POS, msg, args);
    }

    /** RAISE_SYNTAX_ERROR_INVALID_TARGET(type, e), i.e. _RAISE_SYNTAX_ERROR_INVALID_TARGET */
    public static Object RAISE_SYNTAX_ERROR_INVALID_TARGET(Parser p, TARGETS_TYPE type, Object e) {
        expr invalid_target = CHECK_NULL_ALLOWED(p, _PyPegen_get_invalid_target((expr) e, type));
        if (invalid_target != null) {
            String msg;
            if (type == STAR_TARGETS || type == FOR_TARGETS) {
                msg = "cannot assign to %s";
            } else {
                msg = "cannot delete %s";
            }
            return RAISE_SYNTAX_ERROR_KNOWN_LOCATION(p, invalid_target, msg,
                    _PyPegen_get_expr_name(invalid_target));
        }
        return null;
    }

    /** PyPegen_first_item(seq, type), i.e. _PyPegen_seq_first_item */
    public static Object PyPegen_first_item(List<?> seq) {
        return seq.get(0);
    }

    /** PyPegen_last_item(seq, type), i.e. _PyPegen_seq_last_item */
    public static Object PyPegen_last_item(List<?> seq) {
        return seq.get(seq.size() - 1);
    }

    /** pycore_asdl.h asdl_seq_LEN */
    public static int asdl_seq_LEN(List<?> seq) {
        return seq == null ? 0 : seq.size();
    }

    /** pycore_asdl.h asdl_seq_GET */
    public static <T> T asdl_seq_GET(List<T> seq, int i) {
        return seq.get(i);
    }

    /**
     * C's implicit conversion of void * to another pointer type, for a
     * variable of an untyped rule passed as an argument: the cast is inferred
     * from the parameter type.
     */
    @SuppressWarnings("unchecked")
    public static <T> T fromVoidPtr(Object value) {
        return (T) value;
    }

    /** (a)->lineno, (a)->col_offset, (a)->end_lineno, (a)->end_col_offset for a token or node. */
    private static int[] location(Object node) {
        Located a = (Located) node;
        return new int[] {a.lineno(), a.col_offset(), a.end_lineno(), a.end_col_offset()};
    }

    // ---- pegen_errors.c (minimal) ----

    /**
     * _PyPegen_raise_error: an error at the current token (use_mark) or the
     * last token read. TODO: port fully (known_err_token, tokenizer position
     * for col_offset -1) with the rest of pegen_errors.c.
     */
    public static Object _PyPegen_raise_error(Parser p, Object errtype, int use_mark, String errmsg,
            Object... args) {
        // Bail out if we already have an error set.
        if (p.error_indicator && p.errorOccurred()) {
            return null;
        }
        if (p.fill == 0) {
            _PyPegen_raise_error_known_location(p, errtype, 0, 0, 0, -1, errmsg, args);
            return null;
        }
        if (use_mark != 0 && p.mark == p.fill && p.fillToken() < 0) {
            p.error_indicator = true;
            return null;
        }
        Token t = p.tokens[use_mark != 0 ? p.mark : p.fill - 1];
        int col_offset = t.col_offset == -1 ? 0 : t.col_offset + 1;
        int end_col_offset = t.end_col_offset == -1 ? -1 : t.end_col_offset + 1;
        _PyPegen_raise_error_known_location(p, errtype, t.lineno, col_offset, t.end_lineno,
                end_col_offset, errmsg, args);
        return null;
    }

    /** _PyPegen_raise_error_known_location; columns are 1-based. */
    public static Object _PyPegen_raise_error_known_location(Parser p, Object errtype, int lineno,
            int col_offset, int end_lineno, int end_col_offset, String errmsg, Object... args) {
        p.raiseError(String.valueOf(errtype), formatMessage(errmsg, args), lineno, col_offset,
                end_lineno, end_col_offset);
        return null;
    }

    /** Expands the PyUnicode_FromFormat directives the grammar's messages use (%s %U %i %d %c %%). */
    static String formatMessage(String format, Object... args) {
        StringBuilder out = new StringBuilder();
        int arg = 0;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '%' && i + 1 < format.length()) {
                char d = format.charAt(++i);
                if (d == '%') {
                    out.append('%');
                } else {
                    out.append(arg < args.length ? String.valueOf(args[arg++]) : "%" + d);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---- Not yet ported: action_helpers.c and pegen.c ----

    public static arg _PyPegen_add_type_comment_to_arg(Parser p, arg arg1, Token arg2) {
        return null;
    }

    public static alias _PyPegen_alias_for_star(Parser p, int arg1, int arg2, int arg3, int arg4, Object arg5) {
        return null;
    }

    public static Object _PyPegen_arguments_parsing_error(Parser p, expr arg1) {
        return null;
    }

    public static AugOperator _PyPegen_augoperator(Parser p, operatorType type) {
        return null;
    }

    public static boolean _PyPegen_check_barry_as_flufl(Parser p, Token arg1) {
        return false;
    }

    public static ResultTokenWithMetadata _PyPegen_check_fstring_conversion(Parser p, Token arg1, expr t) {
        return null;
    }

    public static boolean _PyPegen_check_legacy_stmt(Parser p, expr t) {
        return false;
    }

    public static stmt _PyPegen_checked_from_import(Parser p, List<?> dots, expr module_name, List<alias> names, expr lazy_token, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static stmt _PyPegen_class_def_decorators(Parser p, List<expr> arg1, stmt arg2) {
        return null;
    }

    public static CmpopExprPair _PyPegen_cmpop_expr_pair(Parser p, cmpopType arg1, expr arg2) {
        return null;
    }

    public static expr _PyPegen_collect_call_seqs(Parser p, List<expr> arg1, List<?> arg2, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static expr _PyPegen_concatenate_strings(Parser p, List<expr> arg1, int arg2, int arg3, int arg4, int arg5, Object arg6) {
        return null;
    }

    public static expr _PyPegen_concatenate_tstrings(Parser p, List<expr> arg1, int arg2, int arg3, int arg4, int arg5, Object arg6) {
        return null;
    }

    public static expr _PyPegen_constant_from_string(Parser p, Token tok) {
        return null;
    }

    public static expr _PyPegen_constant_from_token(Parser p, Token tok) {
        return null;
    }

    public static expr _PyPegen_decoded_constant_from_token(Parser p, Token tok) {
        return null;
    }

    /** The parser's static dummy name (C: _PyRuntime.parser.dummy_name). */
    private static final Name DUMMY_NAME = new Name("", AstFactory.Load, 1, 0, 1, 0);

    /** Returns a dummy Name node, as a placeholder result (C: void *, but always this Name). */
    public static expr _PyPegen_dummy_name(Parser p, Object... args) {
        return DUMMY_NAME;
    }

    public static arguments _PyPegen_empty_arguments(Parser p) {
        return null;
    }

    public static expr _PyPegen_ensure_imaginary(Parser p, expr arg1) {
        return null;
    }

    public static expr _PyPegen_ensure_real(Parser p, expr arg1) {
        return null;
    }

    public static expr _PyPegen_formatted_value(Parser p, expr arg1, Token arg2, ResultTokenWithMetadata arg3, ResultTokenWithMetadata arg4, Token arg5, int arg6, int arg7, int arg8, int arg9, Object arg10) {
        return null;
    }

    public static stmt _PyPegen_function_def_decorators(Parser p, List<expr> arg1, stmt arg2) {
        return null;
    }

    public static List<cmpopType> _PyPegen_get_cmpops(Parser p, List<?> arg1) {
        return null;
    }

    public static String _PyPegen_get_expr_name(expr arg0) {
        return null;
    }

    public static List<expr> _PyPegen_get_exprs(Parser p, List<?> arg1) {
        return null;
    }

    public static expr _PyPegen_get_invalid_target(expr e, TARGETS_TYPE targets_type) {
        return null;
    }

    public static List<expr> _PyPegen_get_keys(Parser p, List<?> arg1) {
        return null;
    }

    public static expr _PyPegen_get_last_comprehension_item(comprehension comprehension) {
        return null;
    }

    public static List<expr> _PyPegen_get_pattern_keys(Parser p, List<?> arg1) {
        return null;
    }

    public static List<pattern> _PyPegen_get_patterns(Parser p, List<?> arg1) {
        return null;
    }

    public static List<expr> _PyPegen_get_values(Parser p, List<?> arg1) {
        return null;
    }

    public static List<stmt> _PyPegen_interactive_exit(Parser p) {
        return null;
    }

    public static expr _PyPegen_interpolation(Parser p, expr arg1, Token arg2, ResultTokenWithMetadata arg3, ResultTokenWithMetadata arg4, Token arg5, int arg6, int arg7, int arg8, int arg9, Object arg10) {
        return null;
    }

    public static expr _PyPegen_join_names_with_dot(Parser p, expr arg1, expr arg2) {
        return null;
    }

    public static List<Object> _PyPegen_join_sequences(Parser p, List<?> arg1, List<?> arg2) {
        return null;
    }

    public static expr _PyPegen_joined_str(Parser p, Token a, List<expr> raw_expressions, Token b) {
        return null;
    }

    public static KeyPatternPair _PyPegen_key_pattern_pair(Parser p, expr arg1, pattern arg2) {
        return null;
    }

    public static KeyValuePair _PyPegen_key_value_pair(Parser p, expr arg1, expr arg2) {
        return null;
    }

    public static KeywordOrStarred _PyPegen_keyword_or_starred(Parser p, Object arg1, int arg2) {
        return null;
    }

    public static arguments _PyPegen_make_arguments(Parser p, List<arg> arg1, SlashWithDefault arg2, List<arg> arg3, List<?> arg4, StarEtc arg5) {
        return null;
    }

    public static mod _PyPegen_make_module(Parser p, List<stmt> arg1) {
        return null;
    }

    public static List<String> _PyPegen_map_names_to_ids(Parser p, List<expr> arg1) {
        return null;
    }

    public static NameDefaultPair _PyPegen_name_default_pair(Parser p, arg arg1, expr arg2, Token arg3) {
        return null;
    }

    public static String _PyPegen_new_type_comment(Parser p, String arg1) {
        return null;
    }

    public static Object _PyPegen_nonparen_genexp_in_call(Parser p, expr args, List<comprehension> comprehensions) {
        return null;
    }

    public static Object _PyPegen_raise_error_for_missing_comma(Parser p, expr a, expr b) {
        return null;
    }

    public static List<stmt> _PyPegen_register_stmts(Parser p, List<stmt> stmts) {
        return null;
    }

    public static List<Object> _PyPegen_seq_append_to_end(Parser p, List<?> arg1, Object arg2) {
        return null;
    }

    public static int _PyPegen_seq_count_dots(List<?> arg0) {
        return 0;
    }

    public static List<keyword> _PyPegen_seq_delete_starred_exprs(Parser p, List<?> arg1) {
        return null;
    }

    public static List<expr> _PyPegen_seq_extract_starred_exprs(Parser p, List<?> arg1) {
        return null;
    }

    public static List<Object> _PyPegen_seq_flatten(Parser p, List<?> arg1) {
        return null;
    }

    public static List<Object> _PyPegen_seq_insert_in_front(Parser p, Object arg1, List<?> arg2) {
        return null;
    }

    public static expr _PyPegen_set_expr_context(Parser p, expr arg1, expr_contextType arg2) {
        return null;
    }

    public static ResultTokenWithMetadata _PyPegen_setup_full_format_spec(Parser p, Token arg1, List<expr> arg2, int arg3, int arg4, int arg5, int arg6, Object arg7) {
        return null;
    }

    public static List<Object> _PyPegen_singleton_seq(Parser p, Object arg1) {
        return null;
    }

    public static SlashWithDefault _PyPegen_slash_with_default(Parser p, List<arg> arg1, List<?> arg2) {
        return null;
    }

    public static StarEtc _PyPegen_star_etc(Parser p, arg arg1, List<?> arg2, arg arg3) {
        return null;
    }

    public static expr _PyPegen_template_str(Parser p, Token a, List<expr> raw_expressions, Token b) {
        return null;
    }
}
