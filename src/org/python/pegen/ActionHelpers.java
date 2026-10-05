package org.python.pegen;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.python.pegen.ast.*;
import org.python.pegen.ast.base.*;

import static org.python.pegen.AstFactory.*;
import static org.python.pegen.TokenTypes.*;

/**
 * The helpers grammar actions call, under their C names: the macros and
 * inline functions of CPython's Parser/pegen.h, and the _PyPegen_* functions
 * of Parser/action_helpers.c, pegen.c and pegen_errors.c.
 *
 * <p>The pegen.h part, action_helpers.c and pegen_errors.c are ported, each
 * in C's order. Signatures were derived from the C
 * prototypes (C types mapped as in src/pegen/tools/java_types.py).
 *
 * <p>GeneratedParser imports all of this statically.
 */
public final class ActionHelpers {

    private ActionHelpers() {}

    // ---- Constants ----

    /** pegen.h CURRENT_POS: "the position of the current token" in error locations. */
    public static final int CURRENT_POS = -5;

    /** pegen.h PyPARSE_* flags: bits of Parser.flags. */
    public static final int PyPARSE_DONT_IMPLY_DEDENT = 0x0002;
    public static final int PyPARSE_IGNORE_COOKIE = 0x0010;
    public static final int PyPARSE_BARRY_AS_BDFL = 0x0020;
    public static final int PyPARSE_TYPE_COMMENTS = 0x0040;
    public static final int PyPARSE_ALLOW_INCOMPLETE_INPUT = 0x0100;

    /** errcode.h: tokenizer and parser error codes (TokenSource.done(), Parser.errcode). */
    public static final int E_OK = 10;
    public static final int E_EOF = 11;
    public static final int E_INTR = 12;
    public static final int E_TOKEN = 13;
    public static final int E_SYNTAX = 14;
    public static final int E_NOMEM = 15;
    public static final int E_DONE = 16;
    public static final int E_ERROR = 17;
    public static final int E_TABSPACE = 18;
    public static final int E_OVERFLOW = 19;
    public static final int E_TOODEEP = 20;
    public static final int E_DEDENT = 21;
    public static final int E_DECODE = 22;
    public static final int E_EOFS = 23;
    public static final int E_EOLS = 24;
    public static final int E_LINECONT = 25;
    public static final int E_BADSINGLE = 27;
    public static final int E_INTERACT_STOP = 28;
    public static final int E_COLUMNOVERFLOW = 29;

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
    public static final String PyExc_ValueError = "ValueError";
    public static final String PyExc_UnicodeError = "UnicodeError";
    public static final String PyExc_SyntaxWarning = "SyntaxWarning";
    public static final String PyExc_DeprecationWarning = "DeprecationWarning";

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

    public static boolean _PyPegen_tokens_are_adjacent(Token a, Token b) {
        return (a.end_lineno == b.lineno) && (a.end_col_offset == b.col_offset);
    }

    /** PyPegen_first_item(seq, type), i.e. _PyPegen_seq_first_item */
    public static Object PyPegen_first_item(List<?> seq) {
        return _PyPegen_seq_first_item(seq);
    }

    /** PyPegen_last_item(seq, type), i.e. _PyPegen_seq_last_item */
    public static Object PyPegen_last_item(List<?> seq) {
        return _PyPegen_seq_last_item(seq);
    }

    /** pegen.h TOK_GET_STRING_PREFIX(tok): 't' inside a t-string, else 'f'. */
    public static char TOK_GET_STRING_PREFIX(TokenSource tok) {
        return tok.insideTstring() ? 't' : 'f';
    }

    /** lexer/state.h INSIDE_FSTRING(tok) */
    public static boolean INSIDE_FSTRING(TokenSource tok) {
        return tok.insideFstring();
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

    // ---- pegen_errors.c ----
    // In C's order. What C reads from p->tok comes from the TokenSource.

    // TOKENIZER ERRORS

    private static void raise_unclosed_parentheses_error(Parser p) {
        int error_lineno = p.tok.parenlinenostack(p.tok.level() - 1);
        int error_col = p.tok.parencolstack(p.tok.level() - 1);
        RAISE_ERROR_KNOWN_LOCATION(p, PyExc_SyntaxError,
                                   error_lineno, error_col, error_lineno, -1,
                                   "'%c' was never closed",
                                   p.tok.parenstack(p.tok.level() - 1));
    }

    public static int _Pypegen_tokenizer_error(Parser p) {
        if (p.errorOccurred()) {
            return -1;
        }

        String msg = null;
        String errtype = PyExc_SyntaxError;
        int col_offset = -1;
        p.error_indicator = true;
        switch (p.tok.done()) {
            case E_TOKEN:
                msg = "invalid token";
                break;
            case E_EOF:
                if (p.tok.level() != 0) {
                    raise_unclosed_parentheses_error(p);
                } else {
                    RAISE_SYNTAX_ERROR(p, "unexpected EOF while parsing");
                }
                return -1;
            case E_DEDENT:
                RAISE_INDENTATION_ERROR(p, "unindent does not match any outer indentation level");
                return -1;
            case E_INTR:
                if (!p.errorOccurred()) {
                    p.setError("KeyboardInterrupt", "");
                }
                return -1;
            case E_NOMEM:
                p.setError("MemoryError", "");
                return -1;
            case E_TABSPACE:
                errtype = "TabError";
                msg = "inconsistent use of tabs and spaces in indentation";
                break;
            case E_TOODEEP:
                errtype = PyExc_IndentationError;
                msg = "too many levels of indentation";
                break;
            case E_LINECONT: {
                // C: p->tok->cur - p->tok->buf - 1
                col_offset = p.tok.bufferOffset() - 1;
                msg = "unexpected character after line continuation character";
                break;
            }
            case E_COLUMNOVERFLOW:
                p.setError("OverflowError",
                        "Parser column offset overflow - source line is too big");
                return -1;
            default:
                msg = "unknown parsing error";
        }

        RAISE_ERROR_KNOWN_LOCATION(p, errtype, p.tok.lineno(),
                                   col_offset >= 0 ? col_offset : 0,
                                   p.tok.lineno(), -1, "%s", msg);
        return -1;
    }

    /**
     * _Pypegen_raise_decode_error: turns a pending UnicodeError or ValueError
     * from decoding a literal into a SyntaxError, "(unicode error) ..." or
     * "(value error) ...". Any other pending error is left as it is.
     */
    public static int _Pypegen_raise_decode_error(Parser p) {
        assert p.errorOccurred();
        String errtype = null;
        if (p.errorMatches(PyExc_UnicodeError)) {
            errtype = "unicode error";
        } else if (p.errorMatches(PyExc_ValueError)) {
            errtype = "value error";
        }
        if (errtype != null) {
            String errstr = p.errorMessage();
            p.clearError();
            RAISE_SYNTAX_ERROR(p, "(%s) %U", errtype, errstr);
        }
        return -1;
    }

    private static int _PyPegen_tokenize_full_source_to_check_for_errors(Parser p) {
        // Tokenize the whole input to see if there are any tokenization
        // errors such as mismatching parentheses. These will get priority
        // over generic syntax errors only if the line number of the error is
        // before the one that we had for the generic error.

        // We don't want to tokenize to the end for interactive input
        if (p.tok.interactive()) {
            return 0;
        }

        PythonSyntaxError saved = p.getError(); // PyErr_Fetch
        p.clearError();

        Token current_token = p.known_err_token != null ? p.known_err_token : p.tokens[p.fill - 1];
        int current_err_line = current_token.lineno;

        int ret = 0;

        for (;;) {
            Token new_token = p.tok.next();
            // (A null token is an error with nothing more to say: ERRORTOKEN.)
            int type = new_token == null ? ERRORTOKEN : new_token.type;
            if (type == ERRORTOKEN) {
                PythonSyntaxError tokenizer_error = p.tok.error();
                if (tokenizer_error != null) {
                    p.setError(tokenizer_error);
                    ret = -1;
                    break;
                }
                if (p.tok.level() != 0) {
                    int error_lineno = p.tok.parenlinenostack(p.tok.level() - 1);
                    if (current_err_line > error_lineno) {
                        raise_unclosed_parentheses_error(p);
                        ret = -1;
                        break;
                    }
                }
                break;
            } else if (type == ENDMARKER) {
                break;
            }
        }

        // exit:
        // If we're in an f-string, we want the syntax error in the expression part
        // to propagate, so that tokenizer errors (like expecting '}') that happen afterwards
        // do not swallow it.
        if (p.errorOccurred() && !p.tok.insideFstring()) {
            // The new error replaces the saved one.
        } else {
            p.setError(saved); // PyErr_Restore
        }
        return ret;
    }

    // PARSER ERRORS

    /** _PyPegen_raise_error: an error at the current token (use_mark) or the last token read. */
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
        Token t = p.known_err_token != null
                       ? p.known_err_token
                       : p.tokens[use_mark != 0 ? p.mark : p.fill - 1];
        int col_offset;
        int end_col_offset = -1;
        if (t.col_offset == -1) {
            if (p.tok.bufferOffset() == 0) { // C: p->tok->cur == p->tok->buf
                col_offset = 0;
            } else {
                // C: start = p->tok->buf ? p->tok->line_start : p->tok->buf
                col_offset = p.tok.cursorColumn();
            }
        } else {
            col_offset = t.col_offset + 1;
        }

        if (t.end_col_offset != -1) {
            end_col_offset = t.end_col_offset + 1;
        }

        _PyPegen_raise_error_known_location(p, errtype, t.lineno, col_offset, t.end_lineno,
                end_col_offset, errmsg, args);

        return null;
    }

    private static String get_error_line_from_tokenizer_buffers(Parser p, int lineno) {
        /* If the file descriptor is interactive, the source lines of the current
         * (multi-line) statement are stored in p->tok->interactive_src_start.
         * If not, we're parsing from a string, which means that the whole source
         * is stored in p->tok->str. */
        String line = p.tok.getLine(lineno);
        return line == null ? "" : line;
    }

    /** _PyPegen_raise_error_known_location; columns are 1-based UTF-8 byte offsets. */
    public static Object _PyPegen_raise_error_known_location(Parser p, Object errtype, int lineno,
            int col_offset, int end_lineno, int end_col_offset, String errmsg, Object... args) {
        // Bail out if we already have an error set.
        if (p.error_indicator && p.errorOccurred()) {
            return null;
        }
        String error_line = null;
        p.error_indicator = true;

        if (end_lineno == CURRENT_POS) {
            end_lineno = p.tok.lineno();
        }
        if (end_col_offset == CURRENT_POS) {
            end_col_offset = p.tok.cursorColumn();
        }

        String errstr = formatMessage(errmsg, args);

        if (p.tok.interactive()) {
            error_line = get_error_line_from_tokenizer_buffers(p, lineno);
        }
        // (C next reads the line from the file named p->tok->filename, for
        // file_input: _PyErr_ProgramDecodedTextObject. The parser here is
        // always given its source, so it takes the branches C takes when
        // parsing a string.)

        if (error_line == null) {
            String current_line = p.tok.currentLine();
            if (p.tok.lineno() <= lineno && current_line != null) {
                error_line = current_line;
            } else {
                error_line = get_error_line_from_tokenizer_buffers(p, lineno);
            }
        }

        int col_number = col_offset;
        int end_col_number = end_col_offset;

        col_number = _PyPegen_byte_offset_to_character_offset(error_line, col_offset);

        if (end_col_offset > 0) {
            end_col_number = _PyPegen_byte_offset_to_character_offset(error_line, end_col_offset);
        }

        p.setError(new PythonSyntaxError(String.valueOf(errtype), errstr, lineno, col_number,
                error_line, end_lineno, end_col_number));
        return null;
    }

    public static void _Pypegen_set_syntax_error(Parser p, Token last_token) {
        // Existing syntax error
        if (p.errorOccurred()) {
            // Prioritize tokenizer errors to custom syntax errors raised
            // on the second phase only if the errors come from the parser.
            boolean is_tok_ok = (p.tok.done() == E_DONE || p.tok.done() == E_OK);
            if (is_tok_ok && p.errorMatches(PyExc_SyntaxError)) {
                _PyPegen_tokenize_full_source_to_check_for_errors(p);
            }
            // Propagate the existing syntax error.
            return;
        }
        // Initialization error
        if (p.fill == 0) {
            RAISE_SYNTAX_ERROR(p, "error at start before reading any input");
            // (C goes on to read last_token, which doesn't exist.)
            return;
        }
        // Parser encountered EOF (End of File) unexpectedtly
        if (last_token.type == ERRORTOKEN && p.tok.done() == E_EOF) {
            if (p.tok.level() != 0) {
                raise_unclosed_parentheses_error(p);
            } else {
                RAISE_SYNTAX_ERROR(p, "unexpected EOF while parsing");
            }
            return;
        }
        // Indentation error in the tokenizer
        if (last_token.type == INDENT || last_token.type == DEDENT) {
            RAISE_INDENTATION_ERROR(p,
                    last_token.type == INDENT ? "unexpected indent" : "unexpected unindent");
            return;
        }
        // Unknown error (generic case)

        // Use the last token we found on the first pass to avoid reporting
        // incorrect locations for generic syntax errors just because we reached
        // further away when trying to find specific syntax errors in the second
        // pass.
        RAISE_SYNTAX_ERROR_KNOWN_LOCATION(p, last_token, "invalid syntax");
        // _PyPegen_tokenize_full_source_to_check_for_errors will override the existing
        // generic SyntaxError we just raised if errors are found.
        _PyPegen_tokenize_full_source_to_check_for_errors(p);
    }

    public static void _Pypegen_stack_overflow(Parser p) {
        p.error_indicator = true;
        p.setError("MemoryError",
            "Parser stack overflowed - Python source too complex to parse");
    }

    // ---- pegen.c: byte offsets to character offsets ----

    /**
     * _PyPegen_byte_offset_to_character_offset_raw: the number of characters
     * (code points) in the first col_offset bytes of str's UTF-8 encoding, or
     * of all of it and its terminating NUL if col_offset is past the end. A
     * character cut off at the end counts as one (decoding with "replace").
     */
    static int _PyPegen_byte_offset_to_character_offset_raw(String str, int col_offset) {
        byte[] data = str.getBytes(StandardCharsets.UTF_8);
        int len = data.length;
        if (col_offset > len + 1) {
            col_offset = len + 1;
        }
        assert col_offset >= 0;
        int n = Math.min(col_offset, len);
        int size = 0;
        for (int i = 0; i < n; size++) {
            int ch = data[i] & 0xff;
            if (ch < 0x80) {
                i += 1;
            } else if ((ch & 0xe0) == 0xc0) {
                i += 2;
            } else if ((ch & 0xf0) == 0xe0) {
                i += 3;
            } else if ((ch & 0xf8) == 0xf0) {
                i += 4;
            } else {
                i += 1;
            }
        }
        if (col_offset == len + 1) {
            size++; // the NUL
        }
        return size;
    }

    static int _PyPegen_byte_offset_to_character_offset(String line, int col_offset) {
        return _PyPegen_byte_offset_to_character_offset_raw(line, col_offset);
    }

    // ---- Formatting ----

    /**
     * Expands the PyUnicode_FromFormat directives the C messages use: %s %U
     * %S %i %d %zd, %c (a character code) and %% , with an optional precision
     * (%.3s).
     */
    static String formatMessage(String format, Object... args) {
        StringBuilder out = new StringBuilder();
        int arg = 0;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%' || i + 1 == format.length()) {
                out.append(c);
                continue;
            }
            int start = i;
            char d = format.charAt(++i);
            if (d == '%') {
                out.append('%');
                continue;
            }
            int precision = -1;
            if (d == '.') {
                precision = 0;
                while (i + 1 < format.length() && Character.isDigit(d = format.charAt(++i))) {
                    precision = precision * 10 + (d - '0');
                }
            }
            if (d == 'z' && i + 1 < format.length()) {
                d = format.charAt(++i);
            }
            if (arg == args.length) {
                out.append(format, start, i + 1);
                continue;
            }
            Object value = args[arg++];
            String text;
            if (d == 'c' && value instanceof Number) {
                text = new String(Character.toChars(((Number) value).intValue()));
            } else {
                text = String.valueOf(value);
            }
            if (precision >= 0 && text.length() > precision) {
                text = text.substring(0, precision);
            }
            out.append(text);
        }
        return out.toString();
    }

    // ---- action_helpers.c ----
    // In C's order. C's asdl_seq becomes a java.util.List; a NULL sequence
    // stays null, as C passes it (CPython's AST conversion shows it as []).
    // The arena-allocated pegen.h structs are plain objects.

    /** The parser's static dummy name (C: _PyRuntime.parser.dummy_name). */
    private static final Name DUMMY_NAME = new Name("", AstFactory.Load, 1, 0, 1, 0);

    /** Returns a dummy Name node, as a placeholder result (C: void *, but always this Name). */
    public static expr _PyPegen_dummy_name(Parser p, Object... args) {
        return DUMMY_NAME;
    }

    /** The placeholder statement for voidAs. */
    private static final stmt DUMMY_STMT = new Pass(1, 0, 1, 0);

    /**
     * A void * rule result used as type in a default action (the generator's
     * void_cast). It may be the dummy name, which C reinterprets as whatever
     * type is expected; here a placeholder of that type stands in. Only
     * invalid_* rules produce such values, in the second pass, whose result
     * is discarded.
     */
    public static <T> T voidAs(Class<T> type, Object value) {
        if (value == DUMMY_NAME && !type.isInstance(value)) {
            if (type == stmt.class) {
                return type.cast(DUMMY_STMT);
            }
            throw new ClassCastException("no placeholder for the dummy name as " + type.getName());
        }
        return type.cast(value);
    }

    /** voidAs for a sequence type: the dummy name stands in as an empty sequence. */
    public static List<?> voidAsList(Object value) {
        if (value == DUMMY_NAME) {
            return new ArrayList<>(0);
        }
        return (List<?>) value;
    }

    /* Creates a single-element asdl_seq* that contains a */
    public static List<Object> _PyPegen_singleton_seq(Parser p, Object a) {
        assert a != null;
        List<Object> seq = new ArrayList<>(1);
        seq.add(a);
        return seq;
    }

    /* Creates a copy of seq and prepends a to it */
    public static List<Object> _PyPegen_seq_insert_in_front(Parser p, Object a, List<?> seq) {
        assert a != null;
        if (seq == null) {
            return _PyPegen_singleton_seq(p, a);
        }

        List<Object> new_seq = new ArrayList<>(asdl_seq_LEN(seq) + 1);
        new_seq.add(a);
        new_seq.addAll(seq);
        return new_seq;
    }

    /* Creates a copy of seq and appends a to it */
    public static List<Object> _PyPegen_seq_append_to_end(Parser p, List<?> seq, Object a) {
        assert a != null;
        if (seq == null) {
            return _PyPegen_singleton_seq(p, a);
        }

        List<Object> new_seq = new ArrayList<>(asdl_seq_LEN(seq) + 1);
        new_seq.addAll(seq);
        new_seq.add(a);
        return new_seq;
    }

    private static int _get_flattened_seq_size(List<?> seqs) {
        int size = 0;
        for (int i = 0, l = asdl_seq_LEN(seqs); i < l; i++) {
            List<?> inner_seq = (List<?>) seqs.get(i);
            size += asdl_seq_LEN(inner_seq);
        }
        return size;
    }

    /* Flattens an asdl_seq* of asdl_seq*s */
    public static List<Object> _PyPegen_seq_flatten(Parser p, List<?> seqs) {
        int flattened_seq_size = _get_flattened_seq_size(seqs);
        assert flattened_seq_size > 0;

        List<Object> flattened_seq = new ArrayList<>(flattened_seq_size);
        for (int i = 0, l = asdl_seq_LEN(seqs); i < l; i++) {
            List<?> inner_seq = (List<?>) seqs.get(i);
            for (int j = 0, li = asdl_seq_LEN(inner_seq); j < li; j++) {
                flattened_seq.add(inner_seq.get(j));
            }
        }
        assert flattened_seq.size() == flattened_seq_size;

        return flattened_seq;
    }

    public static Object _PyPegen_seq_last_item(List<?> seq) {
        int len = asdl_seq_LEN(seq);
        return seq.get(len - 1);
    }

    public static Object _PyPegen_seq_first_item(List<?> seq) {
        return seq.get(0);
    }

    /* Creates a new name of the form <first_name>.<second_name> */
    public static expr _PyPegen_join_names_with_dot(Parser p, expr first_name, expr second_name) {
        assert first_name != null && second_name != null;
        String uni = (((Name) first_name).id + "." + ((Name) second_name).id).intern();
        return _PyAST_Name(uni, Load, first_name.lineno, first_name.col_offset,
                second_name.end_lineno, second_name.end_col_offset, p.arena);
    }

    /* Counts the total number of dots in seq's tokens */
    public static int _PyPegen_seq_count_dots(List<?> seq) {
        int number_of_dots = 0;
        for (int i = 0, l = asdl_seq_LEN(seq); i < l; i++) {
            Token current_expr = (Token) seq.get(i);
            switch (current_expr.type) {
                case ELLIPSIS:
                    number_of_dots += 3;
                    break;
                case DOT:
                    number_of_dots += 1;
                    break;
                default:
                    throw new IllegalStateException("unreachable"); // Py_UNREACHABLE()
            }
        }

        return number_of_dots;
    }

    /* Creates an alias with '*' as the identifier name */
    public static alias _PyPegen_alias_for_star(Parser p, int lineno, int col_offset,
            int end_lineno, int end_col_offset, Object arena) {
        return _PyAST_alias("*", null, lineno, col_offset, end_lineno, end_col_offset, arena);
    }

    /* Creates a new asdl_seq* with the identifiers of all the names in seq */
    public static List<String> _PyPegen_map_names_to_ids(Parser p, List<expr> seq) {
        int len = asdl_seq_LEN(seq);
        assert len > 0;

        List<String> new_seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            expr e = seq.get(i);
            new_seq.add(((Name) e).id);
        }
        return new_seq;
    }

    /* Constructs a CmpopExprPair */
    public static CmpopExprPair _PyPegen_cmpop_expr_pair(Parser p, cmpopType cmpop, expr expr) {
        assert expr != null;
        CmpopExprPair a = new CmpopExprPair();
        a.cmpop = cmpop;
        a.expr = expr;
        return a;
    }

    public static List<cmpopType> _PyPegen_get_cmpops(Parser p, List<?> seq) {
        int len = asdl_seq_LEN(seq);
        assert len > 0;

        List<cmpopType> new_seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            CmpopExprPair pair = (CmpopExprPair) seq.get(i);
            new_seq.add(pair.cmpop);
        }
        return new_seq;
    }

    public static List<expr> _PyPegen_get_exprs(Parser p, List<?> seq) {
        int len = asdl_seq_LEN(seq);
        assert len > 0;

        List<expr> new_seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            CmpopExprPair pair = (CmpopExprPair) seq.get(i);
            new_seq.add(pair.expr);
        }
        return new_seq;
    }

    /* Creates an asdl_seq* where all the elements have been changed to have ctx as context */
    private static List<expr> _set_seq_context(Parser p, List<expr> seq, expr_contextType ctx) {
        int len = asdl_seq_LEN(seq);
        if (len == 0) {
            return null;
        }

        List<expr> new_seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            expr e = seq.get(i);
            expr new_e = _PyPegen_set_expr_context(p, e, ctx);
            if (new_e == null) {
                return null;
            }
            new_seq.add(new_e);
        }
        return new_seq;
    }

    private static expr _set_name_context(Parser p, expr e, expr_contextType ctx) {
        return _PyAST_Name(((Name) e).id, ctx, e.lineno, e.col_offset, e.end_lineno,
                e.end_col_offset, p.arena);
    }

    private static expr _set_tuple_context(Parser p, expr e, expr_contextType ctx) {
        List<expr> seq = _set_seq_context(p, ((Tuple) e).elts, ctx);
        if (seq == null && p.errorOccurred()) {
            return null;
        }
        return _PyAST_Tuple(seq, ctx, e.lineno, e.col_offset, e.end_lineno, e.end_col_offset,
                p.arena);
    }

    private static expr _set_list_context(Parser p, expr e, expr_contextType ctx) {
        List<expr> seq = _set_seq_context(p, ((org.python.pegen.ast.List) e).elts, ctx);
        if (seq == null && p.errorOccurred()) {
            return null;
        }
        return _PyAST_List(seq, ctx, e.lineno, e.col_offset, e.end_lineno, e.end_col_offset,
                p.arena);
    }

    private static expr _set_subscript_context(Parser p, expr e, expr_contextType ctx) {
        return _PyAST_Subscript(((Subscript) e).value, ((Subscript) e).slice,
                ctx, e.lineno, e.col_offset, e.end_lineno, e.end_col_offset, p.arena);
    }

    private static expr _set_attribute_context(Parser p, expr e, expr_contextType ctx) {
        return _PyAST_Attribute(((Attribute) e).value, ((Attribute) e).attr,
                ctx, e.lineno, e.col_offset, e.end_lineno, e.end_col_offset, p.arena);
    }

    private static expr _set_starred_context(Parser p, expr e, expr_contextType ctx) {
        expr inner = _PyPegen_set_expr_context(p, ((Starred) e).value, ctx);
        if (inner == null) {
            return null;
        }
        return _PyAST_Starred(inner, ctx, e.lineno, e.col_offset, e.end_lineno,
                e.end_col_offset, p.arena);
    }

    /* Creates an `expr_ty` equivalent to `expr` but with `ctx` as context */
    public static expr _PyPegen_set_expr_context(Parser p, expr expr, expr_contextType ctx) {
        assert expr != null;

        expr _new = null;
        switch (expr.kind()) {
            case Name:
                _new = _set_name_context(p, expr, ctx);
                break;
            case Tuple:
                _new = _set_tuple_context(p, expr, ctx);
                break;
            case List:
                _new = _set_list_context(p, expr, ctx);
                break;
            case Subscript:
                _new = _set_subscript_context(p, expr, ctx);
                break;
            case Attribute:
                _new = _set_attribute_context(p, expr, ctx);
                break;
            case Starred:
                _new = _set_starred_context(p, expr, ctx);
                break;
            default:
                _new = expr;
        }
        return _new;
    }

    /* Constructs a KeyValuePair that is used when parsing a dict's key value pairs */
    public static KeyValuePair _PyPegen_key_value_pair(Parser p, expr key, expr value) {
        KeyValuePair a = new KeyValuePair();
        a.key = key;
        a.value = value;
        return a;
    }

    /* Extracts all keys from an asdl_seq* of KeyValuePair*'s */
    public static List<expr> _PyPegen_get_keys(Parser p, List<?> seq) {
        int len = asdl_seq_LEN(seq);
        List<expr> new_seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            KeyValuePair pair = (KeyValuePair) seq.get(i);
            new_seq.add(pair.key);
        }
        return new_seq;
    }

    /* Extracts all values from an asdl_seq* of KeyValuePair*'s */
    public static List<expr> _PyPegen_get_values(Parser p, List<?> seq) {
        int len = asdl_seq_LEN(seq);
        List<expr> new_seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            KeyValuePair pair = (KeyValuePair) seq.get(i);
            new_seq.add(pair.value);
        }
        return new_seq;
    }

    /* Constructs a KeyPatternPair that is used when parsing mapping & class patterns */
    public static KeyPatternPair _PyPegen_key_pattern_pair(Parser p, expr key, pattern pattern) {
        KeyPatternPair a = new KeyPatternPair();
        a.key = key;
        a.pattern = pattern;
        return a;
    }

    /* Extracts all keys from an asdl_seq* of KeyPatternPair*'s */
    public static List<expr> _PyPegen_get_pattern_keys(Parser p, List<?> seq) {
        int len = asdl_seq_LEN(seq);
        List<expr> new_seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            KeyPatternPair pair = (KeyPatternPair) seq.get(i);
            new_seq.add(pair.key);
        }
        return new_seq;
    }

    /* Extracts all patterns from an asdl_seq* of KeyPatternPair*'s */
    public static List<pattern> _PyPegen_get_patterns(Parser p, List<?> seq) {
        int len = asdl_seq_LEN(seq);
        List<pattern> new_seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            KeyPatternPair pair = (KeyPatternPair) seq.get(i);
            new_seq.add(pair.pattern);
        }
        return new_seq;
    }

    /* Constructs a NameDefaultPair */
    public static NameDefaultPair _PyPegen_name_default_pair(Parser p, arg arg, expr value,
            Token tc) {
        NameDefaultPair a = new NameDefaultPair();
        a.arg = _PyPegen_add_type_comment_to_arg(p, arg, tc);
        if (a.arg == null) {
            return null;
        }
        a.value = value;
        return a;
    }

    /* Constructs a SlashWithDefault */
    public static SlashWithDefault _PyPegen_slash_with_default(Parser p, List<arg> plain_names,
            List<?> names_with_defaults) {
        SlashWithDefault a = new SlashWithDefault();
        a.plain_names = plain_names;
        a.names_with_defaults = fromVoidPtr(names_with_defaults);
        return a;
    }

    /* Constructs a StarEtc */
    public static StarEtc _PyPegen_star_etc(Parser p, arg vararg, List<?> kwonlyargs, arg kwarg) {
        StarEtc a = new StarEtc();
        a.vararg = vararg;
        a.kwonlyargs = fromVoidPtr(kwonlyargs);
        a.kwarg = kwarg;
        return a;
    }

    public static List<Object> _PyPegen_join_sequences(Parser p, List<?> a, List<?> b) {
        int first_len = asdl_seq_LEN(a);
        int second_len = asdl_seq_LEN(b);
        List<Object> new_seq = new ArrayList<>(first_len + second_len);

        for (int i = 0; i < first_len; i++) {
            new_seq.add(a.get(i));
        }
        for (int i = 0; i < second_len; i++) {
            new_seq.add(b.get(i));
        }

        return new_seq;
    }

    private static List<arg> _get_names(Parser p, List<?> names_with_defaults) {
        int len = asdl_seq_LEN(names_with_defaults);
        List<arg> seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            NameDefaultPair pair = (NameDefaultPair) names_with_defaults.get(i);
            seq.add(pair.arg);
        }
        return seq;
    }

    private static List<expr> _get_defaults(Parser p, List<?> names_with_defaults) {
        int len = asdl_seq_LEN(names_with_defaults);
        List<expr> seq = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            NameDefaultPair pair = (NameDefaultPair) names_with_defaults.get(i);
            seq.add(pair.value);
        }
        return seq;
    }

    // The _make_* functions return through an out parameter in C (and -1 on
    // failure); here they return the sequence (null on failure).

    private static List<arg> _make_posonlyargs(Parser p,
                      List<arg> slash_without_default,
                      SlashWithDefault slash_with_default) {
        if (slash_without_default != null) {
            return slash_without_default;
        }
        else if (slash_with_default != null) {
            List<arg> slash_with_default_names =
                    _get_names(p, slash_with_default.names_with_defaults);
            return fromVoidPtr(_PyPegen_join_sequences(
                    p,
                    slash_with_default.plain_names,
                    slash_with_default_names));
        }
        else {
            return new ArrayList<>(0);
        }
    }

    private static List<arg> _make_posargs(Parser p,
                  List<arg> plain_names,
                  List<?> names_with_default) {

        if (names_with_default != null) {
            if (plain_names != null) {
                List<arg> names_with_default_names = _get_names(p, names_with_default);
                return fromVoidPtr(_PyPegen_join_sequences(
                        p, plain_names, names_with_default_names));
            }
            else {
                return _get_names(p, names_with_default);
            }
        }
        else {
            if (plain_names != null) {
                // With the current grammar, we never get here.
                // If that has changed, remove the assert, and test thoroughly.
                assert false;
                return plain_names;
            }
            else {
                return new ArrayList<>(0);
            }
        }
    }

    private static List<expr> _make_posdefaults(Parser p,
                      SlashWithDefault slash_with_default,
                      List<?> names_with_default) {
        if (slash_with_default != null && names_with_default != null) {
            List<expr> slash_with_default_values =
                    _get_defaults(p, slash_with_default.names_with_defaults);
            List<expr> names_with_default_values = _get_defaults(p, names_with_default);
            return fromVoidPtr(_PyPegen_join_sequences(
                    p,
                    slash_with_default_values,
                    names_with_default_values));
        }
        else if (slash_with_default == null && names_with_default != null) {
            return _get_defaults(p, names_with_default);
        }
        else if (slash_with_default != null && names_with_default == null) {
            return _get_defaults(p, slash_with_default.names_with_defaults);
        }
        else {
            return new ArrayList<>(0);
        }
    }

    /* Constructs an arguments_ty object out of all the parsed constructs in the parameters rule */
    public static arguments _PyPegen_make_arguments(Parser p, List<arg> slash_without_default,
            SlashWithDefault slash_with_default, List<arg> plain_names,
            List<?> names_with_default, StarEtc star_etc) {
        List<arg> posonlyargs = _make_posonlyargs(p, slash_without_default, slash_with_default);

        List<arg> posargs = _make_posargs(p, plain_names, names_with_default);

        List<expr> posdefaults = _make_posdefaults(p, slash_with_default, names_with_default);

        arg vararg = null;
        if (star_etc != null && star_etc.vararg != null) {
            vararg = star_etc.vararg;
        }

        // _make_kwargs, inlined: its two out parameters
        List<arg> kwonlyargs;
        List<expr> kwdefaults;
        if (star_etc != null && star_etc.kwonlyargs != null) {
            kwonlyargs = _get_names(p, star_etc.kwonlyargs);
        }
        else {
            kwonlyargs = new ArrayList<>(0);
        }
        if (star_etc != null && star_etc.kwonlyargs != null) {
            kwdefaults = _get_defaults(p, star_etc.kwonlyargs);
        }
        else {
            kwdefaults = new ArrayList<>(0);
        }

        arg kwarg = null;
        if (star_etc != null && star_etc.kwarg != null) {
            kwarg = star_etc.kwarg;
        }

        return _PyAST_arguments(posonlyargs, posargs, vararg, kwonlyargs,
                                kwdefaults, kwarg, posdefaults, p.arena);
    }

    /* Constructs an empty arguments_ty object, that gets used when a function accepts no
     * arguments. */
    public static arguments _PyPegen_empty_arguments(Parser p) {
        List<arg> posonlyargs = new ArrayList<>(0);
        List<arg> posargs = new ArrayList<>(0);
        List<expr> posdefaults = new ArrayList<>(0);
        List<arg> kwonlyargs = new ArrayList<>(0);
        List<expr> kwdefaults = new ArrayList<>(0);

        return _PyAST_arguments(posonlyargs, posargs, null, kwonlyargs,
                                kwdefaults, null, posdefaults, p.arena);
    }

    /* Encapsulates the value of an operator_ty into an AugOperator struct */
    public static AugOperator _PyPegen_augoperator(Parser p, operatorType kind) {
        AugOperator a = new AugOperator();
        a.kind = kind;
        return a;
    }

    /* Construct a FunctionDef equivalent to function_def, but with decorators */
    public static stmt _PyPegen_function_def_decorators(Parser p, List<expr> decorators,
            stmt function_def) {
        assert function_def != null;
        if (function_def == DUMMY_STMT) {
            // voidAs's placeholder for the dummy name that invalid_def_raw
            // returns in the second pass. C reads that Name as a FunctionDef
            // regardless; the result is discarded either way.
            return function_def;
        }
        if (function_def.kind() == stmt.Kind.AsyncFunctionDef) {
            AsyncFunctionDef f = (AsyncFunctionDef) function_def;
            return _PyAST_AsyncFunctionDef(
                f.name,
                f.args,
                f.body, decorators,
                f.returns,
                f.type_comment,
                f.type_params,
                f.lineno, f.col_offset,
                f.end_lineno, f.end_col_offset, p.arena);
        }

        FunctionDef f = (FunctionDef) function_def;
        return _PyAST_FunctionDef(
            f.name,
            f.args,
            f.body, decorators,
            f.returns,
            f.type_comment,
            f.type_params,
            f.lineno, f.col_offset,
            f.end_lineno, f.end_col_offset, p.arena);
    }

    /* Construct a ClassDef equivalent to class_def, but with decorators */
    public static stmt _PyPegen_class_def_decorators(Parser p, List<expr> decorators,
            stmt class_def) {
        assert class_def != null;
        ClassDef c = (ClassDef) class_def;
        return _PyAST_ClassDef(
            c.name,
            c.bases, c.keywords,
            c.body, decorators,
            c.type_params,
            c.lineno, c.col_offset, c.end_lineno,
            c.end_col_offset, p.arena);
    }

    /* Construct a KeywordOrStarred */
    public static KeywordOrStarred _PyPegen_keyword_or_starred(Parser p, Object element,
            int is_keyword) {
        KeywordOrStarred a = new KeywordOrStarred();
        a.element = element;
        a.is_keyword = is_keyword;
        return a;
    }

    /* Get the number of starred expressions in an asdl_seq* of KeywordOrStarred*s */
    private static int _seq_number_of_starred_exprs(List<?> seq) {
        int n = 0;
        for (int i = 0, l = asdl_seq_LEN(seq); i < l; i++) {
            KeywordOrStarred k = (KeywordOrStarred) seq.get(i);
            if (k.is_keyword == 0) {
                n++;
            }
        }
        return n;
    }

    /* Extract the starred expressions of an asdl_seq* of KeywordOrStarred*s */
    public static List<expr> _PyPegen_seq_extract_starred_exprs(Parser p, List<?> kwargs) {
        int new_len = _seq_number_of_starred_exprs(kwargs);
        if (new_len == 0) {
            return null;
        }
        List<expr> new_seq = new ArrayList<>(new_len);

        for (int i = 0, len = asdl_seq_LEN(kwargs); i < len; i++) {
            KeywordOrStarred k = (KeywordOrStarred) kwargs.get(i);
            if (k.is_keyword == 0) {
                new_seq.add((expr) k.element);
            }
        }
        return new_seq;
    }

    /* Return a new asdl_seq* with only the keywords in kwargs */
    public static List<keyword> _PyPegen_seq_delete_starred_exprs(Parser p, List<?> kwargs) {
        int len = asdl_seq_LEN(kwargs);
        int new_len = len - _seq_number_of_starred_exprs(kwargs);
        if (new_len == 0) {
            return null;
        }
        List<keyword> new_seq = new ArrayList<>(new_len);

        for (int i = 0; i < len; i++) {
            KeywordOrStarred k = (KeywordOrStarred) kwargs.get(i);
            if (k.is_keyword != 0) {
                new_seq.add((keyword) k.element);
            }
        }
        return new_seq;
    }

    public static expr _PyPegen_ensure_imaginary(Parser p, expr exp) {
        if (exp.kind() != expr.Kind.Constant || !(((Constant) exp).value instanceof Complex)) {
            RAISE_SYNTAX_ERROR_KNOWN_LOCATION(p, exp,
                    "imaginary number required in complex literal");
            return null;
        }
        return exp;
    }

    public static expr _PyPegen_ensure_real(Parser p, expr exp) {
        if (exp.kind() != expr.Kind.Constant || ((Constant) exp).value instanceof Complex) {
            RAISE_SYNTAX_ERROR_KNOWN_LOCATION(p, exp, "real number required in complex literal");
            return null;
        }
        return exp;
    }

    public static mod _PyPegen_make_module(Parser p, List<stmt> a) {
        List<type_ignore> type_ignores = null;
        int num = p.type_ignore_comments.size();
        if (num > 0) {
            // Turn the raw (comment, lineno) pairs into TypeIgnore objects in the arena
            type_ignores = new ArrayList<>(num);
            for (int i = 0; i < num; i++) {
                String tag = _PyPegen_new_type_comment(p, p.type_ignore_comments.get(i).comment);
                if (tag == null) {
                    return null;
                }
                type_ignore ti = _PyAST_TypeIgnore(p.type_ignore_comments.get(i).lineno,
                                                   tag, p.arena);
                type_ignores.add(ti);
            }
        }
        return _PyAST_Module(a, type_ignores, p.arena);
    }

    /** C decodes the comment's UTF-8 bytes; here it is already a String. */
    public static String _PyPegen_new_type_comment(Parser p, String s) {
        return s;
    }

    public static arg _PyPegen_add_type_comment_to_arg(Parser p, arg a, Token tc) {
        if (tc == null) {
            return a;
        }
        String tco = _PyPegen_new_type_comment(p, tc.string);
        if (tco == null) {
            return null;
        }
        return _PyAST_arg(a.arg, a.annotation, tco,
                          a.lineno, a.col_offset, a.end_lineno, a.end_col_offset,
                          p.arena);
    }

    /* Checks if the NOTEQUAL token is valid given the current parser flags
    false indicates success and true indicates failure (an exception may be set)
    (C returns an int: 0, or nonzero for failure) */
    public static boolean _PyPegen_check_barry_as_flufl(Parser p, Token t) {
        assert t.string != null;
        assert t.type == NOTEQUAL;

        String tok_str = t.string;
        if ((p.flags & PyPARSE_BARRY_AS_BDFL) != 0 && !tok_str.equals("<>")) {
            RAISE_SYNTAX_ERROR(p, "with Barry as BDFL, use '<>' instead of '!='");
            return true;
        }
        if ((p.flags & PyPARSE_BARRY_AS_BDFL) == 0) {
            return !tok_str.equals("!=");
        }
        return false;
    }

    public static boolean _PyPegen_check_legacy_stmt(Parser p, expr name) {
        if (name.kind() != expr.Kind.Name) {
            return false;
        }
        String[] candidates = {"print", "exec"};
        for (int i = 0; i < 2; i++) {
            if (((Name) name).id.equals(candidates[i])) {
                return true;
            }
        }
        return false;
    }

    public static Object _PyPegen_raise_error_for_missing_comma(Parser p, expr a, expr b) {
        // Don't raise for legacy statements like "print x" or "exec x"
        if (_PyPegen_check_legacy_stmt(p, a)) {
            return null;
        }
        // Only raise inside parentheses/brackets (level > 0)
        if (p.tokens[p.mark - 1].level == 0) {
            return null;
        }
        // For multi-line expressions (like string concatenations), point to the
        // last line instead of the first for a more helpful error message.
        // Use a->col_offset as the starting column since all strings in the
        // concatenation typically share the same indentation.
        if (a.end_lineno > a.lineno) {
            return RAISE_ERROR_KNOWN_LOCATION(
                p, PyExc_SyntaxError, a.end_lineno, a.col_offset,
                a.end_lineno, a.end_col_offset,
                "invalid syntax. Perhaps you forgot a comma?"
            );
        }
        return RAISE_ERROR_KNOWN_LOCATION(
            p, PyExc_SyntaxError, a.lineno, a.col_offset,
            b.end_lineno, b.end_col_offset,
            "invalid syntax. Perhaps you forgot a comma?"
        );
    }

    private static ResultTokenWithMetadata result_token_with_metadata(Parser p, Object result,
            Object metadata) {
        ResultTokenWithMetadata res = new ResultTokenWithMetadata();
        res.metadata = metadata;
        res.result = result;
        return res;
    }

    public static ResultTokenWithMetadata _PyPegen_check_fstring_conversion(Parser p,
            Token conv_token, expr conv) {
        if (conv_token.lineno != conv.lineno || conv_token.end_col_offset != conv.col_offset) {
            return (ResultTokenWithMetadata) RAISE_SYNTAX_ERROR_KNOWN_RANGE(p,
                conv_token, conv,
                "%c-string: conversion type must come right after the exclamation mark",
                TOK_GET_STRING_PREFIX(p.tok)
            );
        }

        String id = ((Name) conv).id;
        int first = id.codePointAt(0);
        if (id.codePointCount(0, id.length()) > 1 ||
                !(first == 's' || first == 'r' || first == 'a')) {
            RAISE_SYNTAX_ERROR_KNOWN_LOCATION(p, conv,
                    "%c-string: invalid conversion character %R: expected 's', 'r', or 'a'",
                    TOK_GET_STRING_PREFIX(p.tok),
                    PyUnicode_Repr(id));
            return null;
        }

        return result_token_with_metadata(p, conv, conv_token.metadata);
    }

    /** Whether e is a Constant holding the empty str. */
    private static boolean isEmptyStrConstant(expr e) {
        return e.kind() == expr.Kind.Constant
                && ((Constant) e).value instanceof String
                && ((String) ((Constant) e).value).isEmpty();
    }

    public static ResultTokenWithMetadata _PyPegen_setup_full_format_spec(Parser p, Token colon,
            List<expr> spec, int lineno, int col_offset, int end_lineno, int end_col_offset,
            Object arena) {
        if (spec == null) {
            return null;
        }

        // This is needed to keep compatibility with 3.11, where an empty format
        // spec is parsed as an *empty* JoinedStr node, instead of having an empty
        // constant in it.
        int n_items = asdl_seq_LEN(spec);
        int non_empty_count = 0;
        for (int i = 0; i < n_items; i++) {
            expr item = spec.get(i);
            non_empty_count += isEmptyStrConstant(item) ? 0 : 1;
        }
        if (non_empty_count != n_items) {
            List<expr> resized_spec = new ArrayList<>(non_empty_count);
            for (int i = 0; i < n_items; i++) {
                expr item = spec.get(i);
                if (isEmptyStrConstant(item)) {
                    continue;
                }
                resized_spec.add(item);
            }
            assert resized_spec.size() == non_empty_count;
            spec = resized_spec;
        }
        expr res;
        int n = asdl_seq_LEN(spec);
        if (n == 0 || (n == 1 && spec.get(0).kind() == expr.Kind.Constant)) {
            res = _PyAST_JoinedStr(spec, lineno, col_offset, end_lineno,
                                        end_col_offset, p.arena);
        } else {
            res = _PyPegen_concatenate_strings(p, spec,
                                 lineno, col_offset, end_lineno,
                                 end_col_offset, arena);
        }
        if (res == null) {
            return null;
        }
        return result_token_with_metadata(p, res, colon.metadata);
    }

    public static String _PyPegen_get_expr_name(expr e) {
        assert e != null;
        switch (e.kind()) {
            case Attribute:
                return "attribute";
            case Subscript:
                return "subscript";
            case Starred:
                return "starred";
            case Name:
                return "name";
            case List:
                return "list";
            case Tuple:
                return "tuple";
            case Lambda:
                return "lambda";
            case Call:
                return "function call";
            case BoolOp:
            case BinOp:
            case UnaryOp:
                return "expression";
            case GeneratorExp:
                return "generator expression";
            case Yield:
            case YieldFrom:
                return "yield expression";
            case Await:
                return "await expression";
            case ListComp:
                return "list comprehension";
            case SetComp:
                return "set comprehension";
            case DictComp:
                return "dict comprehension";
            case Dict:
                return "dict literal";
            case Set:
                return "set display";
            case JoinedStr:
            case FormattedValue:
                return "f-string expression";
            case TemplateStr:
            case Interpolation:
                return "t-string expression";
            case Constant: {
                Object value = ((Constant) e).value;
                if (value == Py_None) {
                    return "None";
                }
                if (value == Py_False) {
                    return "False";
                }
                if (value == Py_True) {
                    return "True";
                }
                if (value == Py_Ellipsis) {
                    return "ellipsis";
                }
                return "literal";
            }
            case Compare:
                return "comparison";
            case IfExp:
                return "conditional expression";
            case NamedExpr:
                return "named expression";
            default:
                // C sets a SystemError and returns NULL; with no parser to
                // hold the error, this internal error is thrown.
                throw new IllegalStateException(String.format(
                             "unexpected expression in assignment %d (line %d)",
                             e.kind().ordinal() + 1, e.lineno));
        }
    }

    public static expr _PyPegen_get_last_comprehension_item(comprehension comprehension) {
        if (comprehension.ifs == null || asdl_seq_LEN(comprehension.ifs) == 0) {
            return comprehension.iter;
        }
        return (expr) PyPegen_last_item(comprehension.ifs);
    }

    public static expr _PyPegen_collect_call_seqs(Parser p, List<expr> a, List<?> b,
            int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        int args_len = asdl_seq_LEN(a);
        int total_len = args_len;

        if (b == null) {
            return _PyAST_Call(_PyPegen_dummy_name(p), a, null, lineno, col_offset,
                            end_lineno, end_col_offset, arena);

        }

        List<expr> starreds = _PyPegen_seq_extract_starred_exprs(p, b);
        if (starreds == null && p.errorOccurred()) {
            return null;
        }

        List<keyword> keywords = _PyPegen_seq_delete_starred_exprs(p, b);
        if (keywords == null && p.errorOccurred()) {
            return null;
        }

        if (starreds != null) {
            total_len += asdl_seq_LEN(starreds);
        }

        List<expr> args = new ArrayList<>(total_len);

        int i = 0;
        for (i = 0; i < args_len; i++) {
            args.add(a.get(i));
        }
        for (; i < total_len; i++) {
            args.add(starreds.get(i - args_len));
        }

        return _PyAST_Call(_PyPegen_dummy_name(p), args, keywords, lineno,
                           col_offset, end_lineno, end_col_offset, arena);
    }

    // AST Error reporting helpers

    public static expr _PyPegen_get_invalid_target(expr e, TARGETS_TYPE targets_type) {
        if (e == null) {
            return null;
        }

        // We only need to visit List and Tuple nodes recursively as those
        // are the only ones that can contain valid names in targets when
        // they are parsed as expressions. Any other kind of expression
        // that is a container (like Sets or Dicts) is directly invalid and
        // we don't need to visit it recursively.

        switch (e.kind()) {
            case List:
                return VISIT_CONTAINER(((org.python.pegen.ast.List) e).elts, targets_type);
            case Tuple:
                return VISIT_CONTAINER(((Tuple) e).elts, targets_type);
            case Starred:
                if (targets_type == DEL_TARGETS) {
                    return e;
                }
                return _PyPegen_get_invalid_target(((Starred) e).value, targets_type);
            case Compare:
                // This is needed, because the `a in b` in `for a in b` gets parsed
                // as a comparison, and so we need to search the left side of the comparison
                // for invalid targets.
                if (targets_type == FOR_TARGETS) {
                    cmpopType cmpop = ((Compare) e).ops.get(0);
                    if (cmpop == In) {
                        return _PyPegen_get_invalid_target(((Compare) e).left, targets_type);
                    }
                    return null;
                }
                return e;
            case Name:
            case Subscript:
            case Attribute:
                return null;
            default:
                return e;
        }
    }

    /** The VISIT_CONTAINER macro of _PyPegen_get_invalid_target, and its "return NULL" after. */
    private static expr VISIT_CONTAINER(List<expr> elts, TARGETS_TYPE targets_type) {
        int len = asdl_seq_LEN(elts);
        for (int i = 0; i < len; i++) {
            expr other = elts.get(i);
            expr child = _PyPegen_get_invalid_target(other, targets_type);
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    public static Object _PyPegen_arguments_parsing_error(Parser p, expr e) {
        int kwarg_unpacking = 0;
        List<keyword> keywords = ((Call) e).keywords;
        for (int i = 0, l = asdl_seq_LEN(keywords); i < l; i++) {
            keyword keyword = keywords.get(i);
            if (keyword.arg == null) {
                kwarg_unpacking = 1;
            }
        }

        String msg = null;
        if (kwarg_unpacking != 0) {
            msg = "positional argument follows keyword argument unpacking";
        } else {
            msg = "positional argument follows keyword argument";
        }

        return RAISE_SYNTAX_ERROR(p, msg);
    }

    public static Object _PyPegen_nonparen_genexp_in_call(Parser p, expr args,
            List<comprehension> comprehensions) {
        /* The rule that calls this function is 'args for_if_clauses'.
           For the input f(L, x for x in y), L and x are in args and
           the for is parsed as a for_if_clause. We have to check if
           len <= 1, so that input like dict((a, b) for a, b in x)
           gets successfully parsed and then we pass the last
           argument (x in the above example) as the location of the
           error */
        List<expr> call_args = ((Call) args).args;
        int len = asdl_seq_LEN(call_args);
        if (len <= 1) {
            return null;
        }

        comprehension last_comprehension = (comprehension) PyPegen_last_item(comprehensions);

        return RAISE_SYNTAX_ERROR_KNOWN_RANGE(p,
            call_args.get(len - 1),
            _PyPegen_get_last_comprehension_item(last_comprehension),
            "Generator expression must be parenthesized"
        );
    }

    // Fstring stuff

    private static expr _PyPegen_decode_fstring_part(Parser p, int is_raw, expr constant,
            Token token) {
        assert ((Constant) constant).value instanceof String;

        byte[] bstr = ((String) ((Constant) constant).value).getBytes(StandardCharsets.UTF_8);

        int len;
        if (Arrays.equals(bstr, LBRACES) || Arrays.equals(bstr, RBRACES)) {
            len = 1;
        } else {
            len = bstr.length;
        }

        is_raw = is_raw != 0 || indexOf(bstr, (byte) '\\') == -1 ? 1 : 0;
        String str = StringParser._PyPegen_decode_string(p, is_raw, bstr, 0, len, token);
        if (str == null) {
            _Pypegen_raise_decode_error(p);
            return null;
        }
        return _PyAST_Constant(str, null, constant.lineno, constant.col_offset,
                               constant.end_lineno, constant.end_col_offset,
                               p.arena);
    }

    private static final byte[] LBRACES = {'{', '{'};
    private static final byte[] RBRACES = {'}', '}'};

    /** strchr: the index of c in s, or -1. */
    private static int indexOf(byte[] s, byte c) {
        for (int i = 0; i < s.length; i++) {
            if (s[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /** C: enum string_kind_t (lexer/state.h) */
    private enum string_kind_t {
        FSTRING, TSTRING
    }

    private static List<expr> _get_resized_exprs(Parser p, Token a, List<expr> raw_expressions,
            Token b, string_kind_t string_kind) {
        int n_items = asdl_seq_LEN(raw_expressions);
        int total_items = n_items;
        for (int i = 0; i < n_items; i++) {
            expr item = raw_expressions.get(i);
            if (item.kind() == expr.Kind.JoinedStr) {
                total_items += asdl_seq_LEN(((JoinedStr) item).values) - 1;
            }
        }

        String quote_str = a.string;
        int is_raw = (quote_str.indexOf('r') >= 0 || quote_str.indexOf('R') >= 0) ? 1 : 0;

        List<expr> seq = new ArrayList<>(total_items);

        for (int i = 0; i < n_items; i++) {
            expr item = raw_expressions.get(i);

            // This should correspond to a JoinedStr node of two elements
            // created _PyPegen_formatted_value. This situation can only be the result of
            // a (f|t)-string debug expression where the first element is a constant with the text and the second
            // a formatted value with the expression.
            if (item.kind() == expr.Kind.JoinedStr) {
                List<expr> values = ((JoinedStr) item).values;
                if (asdl_seq_LEN(values) != 2) {
                    p.setError("SystemError", String.format(
                                 string_kind == string_kind_t.TSTRING
                                 ? "unexpected TemplateStr node without debug data in t-string at line %d"
                                 : "unexpected JoinedStr node without debug data in f-string at line %d",
                                 item.lineno));
                    return null;
                }

                expr first = values.get(0);
                assert first.kind() == expr.Kind.Constant;
                seq.add(first);

                expr second = values.get(1);
                assert (string_kind == string_kind_t.TSTRING
                        && second.kind() == expr.Kind.Interpolation)
                        || second.kind() == expr.Kind.FormattedValue;
                seq.add(second);

                continue;
            }

            if (item.kind() == expr.Kind.Constant) {
                item = _PyPegen_decode_fstring_part(p, is_raw, item, b);
                if (item == null) {
                    return null;
                }

                /* Tokenizer emits string parts even when the underlying string
                might become an empty value (e.g. FSTRING_MIDDLE with the value \\n)
                so we need to check for them and simplify it here. */
                if (isEmptyStrConstant(item)) {
                    continue;
                }
            }
            seq.add(item);
        }

        // C copies into a resized sequence when items were dropped; the
        // ArrayList already has just the items added.
        return seq;
    }

    public static expr _PyPegen_template_str(Parser p, Token a, List<expr> raw_expressions,
            Token b) {

        List<expr> resized_exprs = _get_resized_exprs(p, a, raw_expressions, b,
                string_kind_t.TSTRING);
        if (resized_exprs == null) {
            return null;
        }
        return _PyAST_TemplateStr(resized_exprs, a.lineno, a.col_offset,
                                  b.end_lineno, b.end_col_offset,
                                  p.arena);
    }

    public static expr _PyPegen_joined_str(Parser p, Token a, List<expr> raw_expressions,
            Token b) {

        List<expr> resized_exprs = _get_resized_exprs(p, a, raw_expressions, b,
                string_kind_t.FSTRING);
        if (resized_exprs == null) {
            return null;
        }
        return _PyAST_JoinedStr(resized_exprs, a.lineno, a.col_offset,
                                b.end_lineno, b.end_col_offset,
                                p.arena);
    }

    public static expr _PyPegen_decoded_constant_from_token(Parser p, Token tok) {
        byte[] bstr = tok.string.getBytes(StandardCharsets.UTF_8);

        // Check if we're inside a raw f-string for format spec decoding
        int is_raw = 0;
        if (INSIDE_FSTRING(p.tok)) {
            is_raw = p.tok.fstringRaw() ? 1 : 0; // TOK_GET_MODE(p->tok)->raw
        }

        String str = StringParser._PyPegen_decode_string(p, is_raw, bstr, 0, bstr.length, tok);
        if (str == null) {
            return null;
        }
        return _PyAST_Constant(str, null, tok.lineno, tok.col_offset,
                               tok.end_lineno, tok.end_col_offset,
                               p.arena);
    }

    public static expr _PyPegen_constant_from_token(Parser p, Token tok) {
        String str = tok.string;
        return _PyAST_Constant(str, null, tok.lineno, tok.col_offset,
                               tok.end_lineno, tok.end_col_offset,
                               p.arena);
    }

    public static expr _PyPegen_constant_from_string(Parser p, Token tok) {
        String the_str = tok.string;
        Object s = StringParser._PyPegen_parse_string(p, tok);
        if (s == null) {
            _Pypegen_raise_decode_error(p);
            return null;
        }
        String kind = null;
        if (the_str != null && the_str.charAt(0) == 'u') {
            kind = p.newIdentifier("u");
            if (kind == null) {
                return null;
            }
        }
        return _PyAST_Constant(s, kind, tok.lineno, tok.col_offset, tok.end_lineno,
                tok.end_col_offset, p.arena);
    }

    private static int _get_interpolation_conversion(Parser p, Token debug,
            ResultTokenWithMetadata conversion, ResultTokenWithMetadata format) {
        if (conversion != null) {
            expr conversion_expr = (expr) conversion.result;
            assert conversion_expr.kind() == expr.Kind.Name;
            int first = ((Name) conversion_expr).id.codePointAt(0);
            return first;
        }
        else if (debug != null && format == null) {
            /* If no conversion is specified, use !r for debug expressions */
            return 'r';
        }
        return -1;
    }

    private static String _strip_interpolation_debug_expr(String exprstr) {
        int len = exprstr.length();

        /* Discard whitespace and explicit line continuations after the debug "="
           but preserve whitespace before it. */
        while (len > 0) {
            boolean has_newline = false;
            while (len > 0) {
                char c = exprstr.charAt(len - 1);
                if (!_PyUnicode_IsWhitespace(c)) {
                    break;
                }
                if (c == '\r' || c == '\n') {
                    has_newline = true;
                }
                len--;
            }
            if (!has_newline || len == 0 ||
                exprstr.charAt(len - 1) != '\\')
            {
                break;
            }
            len--;
        }

        /* Preserve unexpected metadata instead of dropping source text. */
        if (len == 0 || exprstr.charAt(len - 1) != '=') {
            return exprstr;
        }

        return exprstr.substring(0, len - 1);
    }

    public static expr _PyPegen_interpolation(Parser p, expr expression, Token debug,
            ResultTokenWithMetadata conversion, ResultTokenWithMetadata format,
            Token closing_brace, int lineno, int col_offset, int end_lineno, int end_col_offset,
            Object arena) {

        int conversion_val = _get_interpolation_conversion(p, debug, conversion, format);

        /* Find the non whitespace token after the "=" */
        int debug_end_line, debug_end_offset;
        Object debug_metadata;
        Object exprstr;

        if (conversion != null) {
            debug_end_line = ((expr) conversion.result).lineno;
            debug_end_offset = ((expr) conversion.result).col_offset;
            debug_metadata = exprstr = conversion.metadata;
        }
        else if (format != null) {
            debug_end_line = ((expr) format.result).lineno;
            debug_end_offset = ((expr) format.result).col_offset + 1;
            debug_metadata = exprstr = format.metadata;
        }
        else {
            debug_end_line = end_lineno;
            debug_end_offset = end_col_offset;
            debug_metadata = exprstr = closing_brace.metadata;
        }

        assert exprstr != null;
        String final_exprstr = debug != null
            ? _strip_interpolation_debug_expr((String) exprstr)
            : (String) exprstr;

        expr interpolation = _PyAST_Interpolation(
            expression, final_exprstr, conversion_val,
            format != null ? (expr) format.result : null,
            lineno, col_offset, end_lineno,
            end_col_offset, arena
        );

        if (interpolation == null || debug == null) {
            return interpolation;
        }

        expr debug_text = _PyAST_Constant(debug_metadata, null, lineno, col_offset + 1,
                debug_end_line, debug_end_offset - 1, p.arena);

        List<expr> values = new ArrayList<>(2);
        values.add(debug_text);
        values.add(interpolation);
        return _PyAST_JoinedStr(values, lineno, col_offset, debug_end_line, debug_end_offset,
                p.arena);
    }

    public static expr _PyPegen_formatted_value(Parser p, expr expression, Token debug,
            ResultTokenWithMetadata conversion, ResultTokenWithMetadata format,
            Token closing_brace, int lineno, int col_offset, int end_lineno, int end_col_offset,
            Object arena) {
        int conversion_val = _get_interpolation_conversion(p, debug, conversion, format);

        expr formatted_value = _PyAST_FormattedValue(
            expression, conversion_val, format != null ? (expr) format.result : null,
            lineno, col_offset, end_lineno,
            end_col_offset, arena
        );

        if (formatted_value == null || debug == null) {
            return formatted_value;
        }

        /* Find the non whitespace token after the "=" */
        int debug_end_line, debug_end_offset;
        Object debug_metadata;

        if (conversion != null) {
            debug_end_line = ((expr) conversion.result).lineno;
            debug_end_offset = ((expr) conversion.result).col_offset;
            debug_metadata = conversion.metadata;
        }
        else if (format != null) {
            debug_end_line = ((expr) format.result).lineno;
            debug_end_offset = ((expr) format.result).col_offset + 1;
            debug_metadata = format.metadata;
        }
        else {
            debug_end_line = end_lineno;
            debug_end_offset = end_col_offset;
            debug_metadata = closing_brace.metadata;
        }
        expr debug_text = _PyAST_Constant(debug_metadata, null, lineno, col_offset + 1,
                debug_end_line, debug_end_offset - 1, p.arena);

        List<expr> values = new ArrayList<>(2);
        values.add(debug_text);
        values.add(formatted_value);
        return _PyAST_JoinedStr(values, lineno, col_offset, debug_end_line, debug_end_offset,
                p.arena);
    }

    private static expr _build_concatenated_bytes(Parser p, List<expr> strings, int lineno,
            int col_offset, int end_lineno, int end_col_offset, Object arena) {
        int len = asdl_seq_LEN(strings);
        assert len > 0;

        /* Bytes literals never get a kind, but just for consistency
            since they are represented as Constant nodes, we'll mirror
            the same behavior as unicode strings for determining the
            kind. */
        String kind = ((Constant) strings.get(0)).kind;

        ByteArrayOutputStream writer = new ByteArrayOutputStream();
        for (int i = 0; i < len; i++) {
            expr elem = strings.get(i);
            byte[] bytes = ((Bytes) ((Constant) elem).value).toArray();
            writer.write(bytes, 0, bytes.length);
        }

        Bytes res = new Bytes(writer.toByteArray());
        return _PyAST_Constant(res, kind, lineno, col_offset, end_lineno, end_col_offset,
                p.arena);
    }

    private static expr _build_concatenated_unicode(Parser p, List<expr> strings, int lineno,
            int col_offset, int end_lineno, int end_col_offset, Object arena) {
        int len = asdl_seq_LEN(strings);
        assert len > 1;

        expr first = strings.get(0);

        /* When a string is getting concatenated, the kind of the string
            is determined by the first string in the concatenation
            sequence.

            u"abc" "def" -> u"abcdef"
            "abc" u"abc" ->  "abcabc" */
        String kind = ((Constant) first).kind;

        StringBuilder writer = new StringBuilder();

        for (int i = 0; i < len; i++) {
            expr current_elem = strings.get(i);
            assert current_elem.kind() == expr.Kind.Constant;

            writer.append((String) ((Constant) current_elem).value);
        }

        String _final = writer.toString();
        return _PyAST_Constant(_final, kind, lineno, col_offset,
                               end_lineno, end_col_offset, arena);
    }

    private static List<expr> _build_concatenated_str(Parser p, List<expr> strings,
            int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        int len = asdl_seq_LEN(strings);
        assert len > 0;

        int n_flattened_elements = 0;
        for (int i = 0; i < len; i++) {
            expr elem = strings.get(i);
            switch (elem.kind()) {
                case JoinedStr:
                    n_flattened_elements += asdl_seq_LEN(((JoinedStr) elem).values);
                    break;
                case TemplateStr:
                    n_flattened_elements += asdl_seq_LEN(((TemplateStr) elem).values);
                    break;
                default:
                    n_flattened_elements++;
                    break;
            }
        }


        List<expr> flattened = new ArrayList<>(n_flattened_elements);

        /* build flattened list */
        for (int i = 0; i < len; i++) {
            expr elem = strings.get(i);
            switch (elem.kind()) {
                case JoinedStr:
                    for (int j = 0; j < asdl_seq_LEN(((JoinedStr) elem).values); j++) {
                        expr subvalue = ((JoinedStr) elem).values.get(j);
                        if (subvalue == null) {
                            return null;
                        }
                        flattened.add(subvalue);
                    }
                    break;
                case TemplateStr:
                    for (int j = 0; j < asdl_seq_LEN(((TemplateStr) elem).values); j++) {
                        expr subvalue = ((TemplateStr) elem).values.get(j);
                        if (subvalue == null) {
                            return null;
                        }
                        flattened.add(subvalue);
                    }
                    break;
                default:
                    flattened.add(elem);
                    break;
            }
        }

        /* calculate folded element count */
        int n_elements = 0;
        boolean prev_is_constant = false;
        for (int i = 0; i < n_flattened_elements; i++) {
            expr elem = flattened.get(i);

            /* The concatenation of a FormattedValue and an empty Constant should
               lead to the FormattedValue itself. Thus, we will not take any empty
               constants into account, just as in `_PyPegen_joined_str` */
            if (isEmptyStrConstant(elem)) {
                continue;
            }

            if (!prev_is_constant || elem.kind() != expr.Kind.Constant) {
                n_elements++;
            }
            prev_is_constant = elem.kind() == expr.Kind.Constant;
        }

        List<expr> values = new ArrayList<>(n_elements);

        /* build folded list */
        for (int i = 0; i < n_flattened_elements; i++) {
            expr elem = flattened.get(i);

            /* if the current elem and the following are constants,
               fold them and all consequent constants */
            if (elem.kind() == expr.Kind.Constant) {
                if (i + 1 < n_flattened_elements &&
                    flattened.get(i + 1).kind() == expr.Kind.Constant) {
                    expr first_elem = elem;

                    /* When a string is getting concatenated, the kind of the string
                       is determined by the first string in the concatenation
                       sequence.

                       u"abc" "def" -> u"abcdef"
                       "abc" u"abc" ->  "abcabc" */
                    String kind = ((Constant) elem).kind;

                    StringBuilder writer = new StringBuilder();
                    expr last_elem = elem;
                    int j;
                    for (j = i; j < n_flattened_elements; j++) {
                        expr current_elem = flattened.get(j);
                        if (current_elem.kind() == expr.Kind.Constant) {
                            writer.append((String) ((Constant) current_elem).value);
                            last_elem = current_elem;
                        } else {
                            break;
                        }
                    }
                    i = j - 1;

                    String concat_str = writer.toString();
                    elem = _PyAST_Constant(concat_str, kind, first_elem.lineno,
                                           first_elem.col_offset,
                                           last_elem.end_lineno,
                                           last_elem.end_col_offset, p.arena);
                }

                /* Drop all empty contanst strings */
                if (isEmptyStrConstant(elem)) {
                    continue;
                }
            }

            values.add(elem);
        }

        assert values.size() == n_elements;
        return values;
    }

    private static expr _build_concatenated_joined_str(Parser p, List<expr> strings,
            int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        List<expr> values = _build_concatenated_str(p, strings, lineno,
            col_offset, end_lineno, end_col_offset, arena);
        if (values == null) {
            return null;
        }
        return _PyAST_JoinedStr(values, lineno, col_offset, end_lineno, end_col_offset, p.arena);
    }

    public static expr _PyPegen_concatenate_tstrings(Parser p, List<expr> strings,
            int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        List<expr> values = _build_concatenated_str(p, strings, lineno,
            col_offset, end_lineno, end_col_offset, arena);
        if (values == null) {
            return null;
        }
        return _PyAST_TemplateStr(values, lineno, col_offset, end_lineno,
            end_col_offset, arena);
    }

    public static expr _PyPegen_concatenate_strings(Parser p, List<expr> strings,
            int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        int len = asdl_seq_LEN(strings);
        assert len > 0;

        boolean f_string_found = false;
        boolean unicode_string_found = false;
        boolean bytes_found = false;

        int i = 0;
        for (i = 0; i < len; i++) {
            expr elem = strings.get(i);
            switch (elem.kind()) {
                case Constant:
                    if (((Constant) elem).value instanceof Bytes) {
                        bytes_found = true;
                    } else {
                        unicode_string_found = true;
                    }
                    break;
                case JoinedStr:
                    f_string_found = true;
                    break;
                case TemplateStr:
                    // python.gram handles this; we should never get here
                    assert false;
                    break;
                default:
                    f_string_found = true;
                    break;
            }
        }

        // Cannot mix unicode and bytes
        if ((unicode_string_found || f_string_found) && bytes_found) {
            RAISE_SYNTAX_ERROR(p, "cannot mix bytes and nonbytes literals");
            return null;
        }

        // If it's only bytes or only unicode string, do a simple concat
        if (!f_string_found) {
            if (len == 1) {
                return strings.get(0);
            }
            else if (bytes_found) {
                return _build_concatenated_bytes(p, strings, lineno, col_offset,
                    end_lineno, end_col_offset, arena);
            }
            else {
                return _build_concatenated_unicode(p, strings, lineno, col_offset,
                    end_lineno, end_col_offset, arena);
            }
        }

        return _build_concatenated_joined_str(p, strings, lineno,
            col_offset, end_lineno, end_col_offset, arena);
    }

    private static int _warn_relative_import_of_lazy(Parser p, List<?> dots, expr module) {
        // Warn about `from . lazy import x`: the whitespace between the dots and
        // the module name is insignificant, so this is parsed exactly like
        // `from .lazy import x` (an import of the relative module "lazy"), but it
        // is most likely a transposition of `lazy from . import x` (PEP 810).
        if (p.call_invalid_rules) {
            return 0;
        }

        // Only fire if there is whitespace between the last dot and the name,
        // i.e. not for the common `from .lazy import x` spelling.
        Token last_dot = (Token) dots.get(asdl_seq_LEN(dots) - 1);
        if (
            last_dot.end_lineno == module.lineno
            && last_dot.end_col_offset == module.col_offset
        ) {
            return 0;
        }

        int count = _PyPegen_seq_count_dots(dots);
        char[] dotchars = new char[count];
        Arrays.fill(dotchars, '.');
        String buf = new String(dotchars);

        String msg = formatMessage(
            "'from %s lazy import' is the same as 'from %slazy import'; "
            + "did you mean 'lazy from %s import'?",
            buf, buf, buf);

        return p.emitSyntaxWarning(msg,
                                   module.lineno,
                                   module.col_offset + 1,
                                   module.end_lineno,
                                   module.end_col_offset + 1);
    }

    public static stmt _PyPegen_checked_from_import(Parser p, List<?> dots, expr module_name,
            List<alias> names, expr lazy_token, int lineno, int col_offset, int end_lineno,
            int end_col_offset, Object arena) {
        String module = ((Name) module_name).id;
        int level = _PyPegen_seq_count_dots(dots);
        if (level == 0 && module.equals("__future__")) {
            if (lazy_token != null) {
                RAISE_SYNTAX_ERROR_KNOWN_LOCATION(p, lazy_token,
                    "lazy from __future__ import is not allowed");
                return null;
            }
            for (int i = 0; i < asdl_seq_LEN(names); i++) {
                alias alias = names.get(i);
                if (alias.name.equals("barry_as_FLUFL")) {
                    p.flags |= PyPARSE_BARRY_AS_BDFL;
                    p.tok.setBarryAsBdfl(true);
                }
            }
        }
        else if (
            level > 0
            && lazy_token == null
            && module.equals("lazy")
        ) {
            if (_warn_relative_import_of_lazy(p, dots, module_name) < 0) {
                return null;
            }
        }
        return _PyAST_ImportFrom(module, names, level, lazy_token != null ? 1 : 0, lineno,
                                 col_offset, end_lineno, end_col_offset, arena);
    }

    public static List<stmt> _PyPegen_register_stmts(Parser p, List<stmt> stmts) {
        if (!p.call_invalid_rules) {
            return stmts;
        }
        int len = asdl_seq_LEN(stmts);
        if (len == 0) {
            return stmts;
        }
        stmt last_stmt = stmts.get(len - 1);
        if (p.last_stmt_location.lineno > last_stmt.lineno) {
            return stmts;
        }
        p.last_stmt_location.lineno = last_stmt.lineno;
        p.last_stmt_location.col_offset = last_stmt.col_offset;
        p.last_stmt_location.end_lineno = last_stmt.end_lineno;
        p.last_stmt_location.end_col_offset = last_stmt.end_col_offset;
        return stmts;
    }

    // ---- pegen.c ----

    public static List<stmt> _PyPegen_interactive_exit(Parser p) {
        p.errcode = E_EOF;
        return null;
    }

    // ---- CPython API stand-ins ----

    /**
     * repr() of a str (PyObject_Repr, for %R), for the identifiers the
     * messages use: quoted, with backslashes and single quotes escaped.
     * TODO: escape non-printable characters as repr() does, if a message ever
     * needs it.
     */
    static String PyUnicode_Repr(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** _PyUnicode_IsWhitespace: Python's str.isspace() for one character. */
    static boolean _PyUnicode_IsWhitespace(int c) {
        return c == 0x85 || Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
