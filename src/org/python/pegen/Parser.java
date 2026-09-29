package org.python.pegen;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.python.pegen.ast.Complex;
import org.python.pegen.ast.base.expr;

import static org.python.pegen.TokenTypes.*;

/**
 * Parser state and runtime support for {@link GeneratedParser}; a port of the
 * parts of CPython's Parser/pegen.c that generated rules call.
 *
 * <p>Fields keep their pegen.h names so that grammar actions (written in C and
 * translated mechanically) can refer to them. Methods are Java-named; each
 * one's comment gives the pegen.c function it ports.
 *
 * <p>This is an early skeleton: AST-producing functions return tokens or a
 * dummy value until the Python 3 AST exists, and errors are recorded only as
 * a message.
 */
public class Parser {

    /**
     * Recursion limit, as in pegen's generated C (MAXSTACK).
     *
     * <p>TODO(stack depth): on a 1 MB JVM thread stack (the Linux x64 default)
     * valid input nested just under this limit can overflow the Java stack
     * first. GeneratedParser.parse() then catches StackOverflowError and
     * reports the too-complex error, so input CPython accepts is rejected
     * (tests/pegen/pending/stack). Options: parse on a thread with a large
     * stack, or use a lower JVM-specific limit.
     */
    public static final int MAXSTACK = 6000;

    /** Start rules; the values of Py_single_input etc. in CPython's compile.h. */
    public static final int SINGLE_INPUT = 256;
    public static final int FILE_INPUT = 257;
    public static final int EVAL_INPUT = 258;
    public static final int FUNC_TYPE_INPUT = 345;

    /** A memoized rule result, chained per token (C: {@code Memo}). */
    public static final class Memo {
        final int type;
        public Object node;
        int mark;
        final Memo next;

        Memo(int type, Object node, int mark, Memo next) {
            this.type = type;
            this.node = node;
            this.mark = mark;
            this.next = next;
        }
    }

    /**
     * Stand-in for _PyPegen_dummy_name's result. It is a List so the value can
     * flow into variables of sequence type while actions are skipped.
     */
    private static final List<Object> DUMMY = Collections.emptyList();
    private static final Token DUMMY_TOKEN = new Token(NAME, "", 1, 0, 1, 0);

    private final TokenSource tok;
    public Token[] tokens = new Token[1];
    public int mark;
    public int fill;
    public int level;
    public boolean error_indicator;
    public boolean call_invalid_rules;
    public int start_rule;
    public Map<String, Integer> keywords = Collections.emptyMap();
    public String[] soft_keywords = new String[0];
    /**
     * C: p->feature_version, the minor version of Python 3 whose syntax is
     * accepted; CHECK_VERSION rejects newer constructs below it.
     */
    public int feature_version = 15;
    /** CPython's PyArena; unused on the JVM, kept so helper signatures match. */
    public final Object arena = null;

    /** C: whether a token has been read yet, for single_input's implied NEWLINE. */
    public boolean parsing_started;

    /**
     * The ENDMARKER held back when single_input turns it into NEWLINE. C's
     * tokenizer keeps returning ENDMARKER at end of input; a TokenSource
     * need not, so it is replayed from here.
     */
    private Token pendingEndmarker;

    /** Pending error (C: the exception set with PyErr_*): its type and message. */
    private String errorType;
    private String error;

    public Parser(TokenSource tok, int start_rule) {
        this.tok = tok;
        this.start_rule = start_rule;
    }

    /** _PyPegen_fill_token: append the next token, mapping keyword names. */
    public int fillToken() {
        Token t = nextFromSource();
        if (t == null) {
            return -1;
        }
        // If we have reached the end and we are in single input mode we need
        // to insert a newline and reset the parsing. (C also sets the
        // tokenizer's pending DEDENTs here; a TokenSource emits those before
        // ENDMARKER itself.)
        if (start_rule == SINGLE_INPUT && t.type == ENDMARKER && parsing_started) {
            pendingEndmarker = t;
            t = new Token(NEWLINE, "", t.lineno, t.col_offset, t.end_lineno, t.end_col_offset);
            parsing_started = false;
        } else {
            parsing_started = true;
        }
        if (t.type == NAME) {
            Integer kw = keywords.get(t.string);
            if (kw != null) {
                t.type = kw;
            }
        }
        if (fill == tokens.length) {
            tokens = Arrays.copyOf(tokens, tokens.length * 2);
        }
        tokens[fill++] = t;
        return 0;
    }

    private Token nextFromSource() {
        if (pendingEndmarker != null) {
            Token t = pendingEndmarker;
            pendingEndmarker = null;
            return t;
        }
        return tok.next();
    }

    /**
     * _PyPegen_run_parser: parse, then apply the checks made after a
     * successful parse. Returns null on failure, with the error (if any)
     * available from {@link #getError()}.
     *
     * <p>Not yet ported: the second pass with invalid_* rules enabled and the
     * syntax-error reporting after it (_Pypegen_set_syntax_error). Both only
     * change which error is reported, and need grammar actions.
     */
    public Object runParser(GeneratedParser parser) {
        Object res = parser.parse();
        if (res != null && errorOccurred()) {
            // Discard a result returned with an exception still pending.
            return null;
        }
        if (res == null) {
            return null;
        }
        if (start_rule == SINGLE_INPUT && badSingleStatement()) {
            raiseSyntaxError(null, "multiple statements found while compiling a single statement");
            return null;
        }
        return res;
    }

    /**
     * bad_single_statement: whether input remains after a single_input
     * statement. C scans the source text after the last token read for
     * anything but whitespace and comments; the equivalent here is any unread
     * token other than NEWLINE, DEDENT or ENDMARKER.
     */
    private boolean badSingleStatement() {
        for (;;) {
            Token t = nextFromSource();
            if (t == null || t.type == ENDMARKER) {
                return false;
            }
            if (t.type != NEWLINE && t.type != DEDENT) {
                return true;
            }
        }
    }

    /** _PyPegen_is_memoized: the memo for rule type at the current mark, or null. */
    public Memo isMemoized(int type) {
        if (mark == fill && fillToken() < 0) {
            error_indicator = true;
            return null;
        }
        for (Memo m = tokens[mark].memo; m != null; m = m.next) {
            if (m.type == type) {
                mark = m.mark;
                return m;
            }
        }
        return null;
    }

    /** _PyPegen_insert_memo */
    public void insertMemo(int mark, int type, Object node) {
        tokens[mark].memo = new Memo(type, node, this.mark, tokens[mark].memo);
    }

    /** _PyPegen_update_memo */
    public void updateMemo(int mark, int type, Object node) {
        for (Memo m = tokens[mark].memo; m != null; m = m.next) {
            if (m.type == type) {
                m.node = node;
                m.mark = this.mark;
                return;
            }
        }
        insertMemo(mark, type, node);
    }

    /** _PyPegen_expect_token */
    public Token expectToken(int type) {
        if (mark == fill && fillToken() < 0) {
            error_indicator = true;
            return null;
        }
        Token t = tokens[mark];
        if (t.type != type) {
            return null;
        }
        mark += 1;
        return t;
    }

    /** _PyPegen_expect_soft_keyword; the keyword is given without quotes. */
    public expr expectSoftKeyword(String keyword) {
        if (mark == fill && fillToken() < 0) {
            error_indicator = true;
            return null;
        }
        Token t = tokens[mark];
        if (t.type != NAME || !t.string.equals(keyword)) {
            return null;
        }
        return nameToken();
    }

    /** _PyPegen_expect_forced_token */
    public Token expectForcedToken(int type, String expected) {
        if (error_indicator) {
            return null;
        }
        if (mark == fill && fillToken() < 0) {
            error_indicator = true;
            return null;
        }
        Token t = tokens[mark];
        if (t.type != type) {
            raiseSyntaxError(t, "expected '" + expected + "'");
            return null;
        }
        mark += 1;
        return t;
    }

    /** _PyPegen_expect_forced_result */
    public Object expectForcedResult(Object result, String expected) {
        if (error_indicator) {
            return null;
        }
        if (result == null) {
            raiseSyntaxError(null, "expected (" + expected + ")");
            return null;
        }
        return result;
    }

    /**
     * _PyPegen_lookahead and its variants. Generated code passes the mark read
     * before the lookahead's item was parsed, so it can be restored here.
     */
    public boolean lookahead(boolean positive, int mark, boolean matched) {
        this.mark = mark;
        return matched == positive;
    }

    /** The C comma expression {@code (_opt_var = call, !p->error_indicator)}. */
    public boolean opt(Object ignored) {
        return !error_indicator;
    }

    /**
     * _PyPegen_new_identifier: the identifier for a NAME token's text,
     * NFKC-normalized if it is not ASCII (as the language reference requires).
     */
    public String newIdentifier(String n) {
        String id = n;
        if (!isAscii(id)) {
            id = Normalizer.normalize(id, Normalizer.Form.NFKC);
        }
        for (String forbidden : new String[] {"None", "True", "False"}) {
            if (id.equals(forbidden)) {
                raiseError("ValueError",
                        "identifier field can't represent '" + forbidden + "' constant");
                error_indicator = true;
                return null;
            }
        }
        return id.intern();
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /** _PyPegen_name_from_token */
    private expr nameFromToken(Token t) {
        if (t == null) {
            return null;
        }
        String id = newIdentifier(t.string);
        if (id == null) {
            error_indicator = true;
            return null;
        }
        return AstFactory._PyAST_Name(id, AstFactory.Load, t.lineno, t.col_offset, t.end_lineno,
                t.end_col_offset, arena);
    }

    /** _PyPegen_name_token */
    public expr nameToken() {
        Token t = expectToken(NAME);
        return nameFromToken(t);
    }

    /** _PyPegen_number_token */
    public expr numberToken() {
        Token t = expectToken(NUMBER);
        if (t == null) {
            return null;
        }
        String num_raw = t.string;
        if (feature_version < 6 && num_raw.indexOf('_') >= 0) {
            error_indicator = true;
            return (expr) ActionHelpers.RAISE_SYNTAX_ERROR(this,
                    "Underscores in numeric literals are only supported in Python 3.6 and greater");
        }
        Object c;
        try {
            c = parsenumber(num_raw);
        } catch (IntDigitLimitError e) {
            // Intentionally omitting columns to avoid a wall of 1000s of '^'s
            // on the error message.
            error_indicator = true;
            ActionHelpers.RAISE_ERROR_KNOWN_LOCATION(this, ActionHelpers.PyExc_SyntaxError,
                    t.lineno, -1, t.end_lineno, -1,
                    "%S - Consider hexadecimal for huge integer literals "
                            + "to avoid decimal conversion limits.",
                    e.getMessage());
            return null;
        }
        return AstFactory._PyAST_Constant(c, null, t.lineno, t.col_offset, t.end_lineno,
                t.end_col_offset, arena);
    }

    /** CPython's default limit on decimal digits converted to an int (sys.get_int_max_str_digits). */
    static final int MAX_STR_DIGITS = 4300;

    /** The ValueError PyLong_FromString raises over MAX_STR_DIGITS. */
    static final class IntDigitLimitError extends Exception {
        IntDigitLimitError(int digits) {
            super("Exceeds the limit (" + MAX_STR_DIGITS + " digits) for integer string "
                    + "conversion: value has " + digits + " digits; use "
                    + "sys.set_int_max_str_digits() to increase the limit");
        }
    }

    /**
     * parsenumber / parsenumber_raw: the value of a NUMBER token's text: an
     * int (BigInteger), a float (Double) or, with a j suffix, a complex.
     */
    static Object parsenumber(String s) throws IntDigitLimitError {
        s = s.replace("_", "");
        char last = s.charAt(s.length() - 1);
        if (last == 'j' || last == 'J') {
            return new Complex(0.0, Double.parseDouble(s.substring(0, s.length() - 1)));
        }
        if (s.length() > 1 && s.charAt(0) == '0') {
            char radix = Character.toLowerCase(s.charAt(1));
            if (radix == 'x') {
                return new BigInteger(s.substring(2), 16);
            } else if (radix == 'o') {
                return new BigInteger(s.substring(2), 8);
            } else if (radix == 'b') {
                return new BigInteger(s.substring(2), 2);
            }
        }
        if (isDecimalDigits(s)) {
            if (s.length() > MAX_STR_DIGITS) {
                throw new IntDigitLimitError(s.length());
            }
            return new BigInteger(s);
        }
        return Double.parseDouble(s);
    }

    private static boolean isDecimalDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** _PyPegen_string_token */
    public Token stringToken() {
        return expectToken(STRING);
    }

    /** _PyPegen_soft_keyword_token (which, unlike other token functions, does not rewind on failure) */
    public expr softKeywordToken() {
        Token t = expectToken(NAME);
        if (t == null) {
            return null;
        }
        for (String kw : soft_keywords) {
            if (kw.equals(t.string)) {
                return nameFromToken(t);
            }
        }
        return null;
    }

    /** _PyPegen_get_last_nonnwhitespace_token */
    public Token getLastNonWhitespaceToken() {
        Token token = null;
        for (int m = mark - 1; m >= 0; m--) {
            token = tokens[m];
            if (token.type != ENDMARKER && (token.type < NEWLINE || token.type > DEDENT)) {
                break;
            }
        }
        return token;
    }

    /** _PyPegen_dummy_name */
    public Object dummyName(Object... ignored) {
        return DUMMY;
    }

    /** _PyPegen_dummy_name, where a Token is expected. */
    public Token dummyToken() {
        return DUMMY_TOKEN;
    }

    /** PyErr_Occurred */
    public boolean errorOccurred() {
        return error != null;
    }

    /** The pending error as "Type: message", or null. */
    public String getError() {
        return error == null ? null : errorType + ": " + error;
    }

    /** _Pypegen_stack_overflow */
    public void stackOverflow() {
        error_indicator = true;
        errorType = "MemoryError";
        error = "Parser stack overflowed - Python source too complex to parse";
    }

    /** RAISE_SYNTAX_ERROR_KNOWN_LOCATION; t may be null for "no location". */
    public void raiseSyntaxError(Token t, String msg) {
        error_indicator = true;
        if (error == null) {
            errorType = "SyntaxError";
            error = t == null ? msg : msg + " at " + t.lineno + ":" + t.col_offset;
        }
    }

    /** Records an error with no location, e.g. a ValueError. */
    public void raiseError(String errtype, String msg) {
        error_indicator = true;
        errorType = errtype;
        error = msg;
    }

    /**
     * Records an error of the given type (e.g. "IndentationError") at a
     * location; the counterpart of _PyPegen_raise_error_known_location.
     * Columns are 1-based as in C; CURRENT_POS and -1 mean "unknown".
     * TODO: build a real exception, with the source line, when errors are ported.
     */
    public void raiseError(String errtype, String msg, int lineno, int col_offset,
            int end_lineno, int end_col_offset) {
        error_indicator = true;
        errorType = errtype;
        error = msg + " at " + lineno + ":" + col_offset;
    }
}
