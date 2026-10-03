package org.python.pegen;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
 * <p>Errors are reported as in pegen_errors.c (ported in ActionHelpers): the
 * pending exception is a {@link PythonSyntaxError}, from {@link #getError()}.
 */
public class Parser {

    /**
     * Recursion limit, as in pegen's generated C (MAXSTACK).
     *
     * <p>C also stops when the C stack runs low
     * (_Py_ReachedRecursionLimitWithMargin); GeneratedParser.parse() stands
     * in for that by catching StackOverflowError. So that MAXSTACK, not the
     * caller's thread stack, is the limit in practice, {@link #runParser}
     * parses on a thread with a {@link LargeStack#STACK_SIZE} stack.
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
     * The recognizer's stand-in for _PyPegen_dummy_name's result (generate.py
     * --skip-actions). It is a List so the value can flow into variables of
     * sequence type.
     */
    private static final List<Object> DUMMY = Collections.emptyList();
    private static final Token DUMMY_TOKEN = new Token(NAME, "", 1, 0, 1, 0);

    /** C: p->tok; also read through the tokenizer-state macros in ActionHelpers. */
    final TokenSource tok;
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
    /** C: p->flags, the PyPARSE_* flags (e.g. ActionHelpers.PyPARSE_BARRY_AS_BDFL). */
    public int flags;

    /**
     * C: *p->errcode, the error code for the caller; E_EOF when interactive
     * input ends (see _PyPegen_interactive_exit).
     */
    public int errcode;

    /** One "# type: ignore" comment (C: an item of growable_comment_array). */
    public static final class TypeIgnoreComment {
        public final int lineno;
        /** The " <tag>" in "# type: ignore <tag>" */
        public final String comment;

        TypeIgnoreComment(int lineno, String comment) {
            this.lineno = lineno;
            this.comment = comment;
        }
    }

    /** C: p->type_ignore_comments, recorded by fillToken from TYPE_IGNORE tokens. */
    public final List<TypeIgnoreComment> type_ignore_comments = new ArrayList<>();

    /** A source range (C: pegen.h location). */
    public static final class Location {
        public int lineno;
        public int col_offset;
        public int end_lineno;
        public int end_col_offset;
    }

    /** C: p->last_stmt_location, kept by _PyPegen_register_stmts in the second pass. */
    public final Location last_stmt_location = new Location();

    /** CPython's PyArena; unused on the JVM, kept so helper signatures match. */
    public final Object arena = null;

    /** C: whether a token has been read yet, for single_input's implied NEWLINE. */
    public boolean parsing_started;

    /** C: p->known_err_token, the token _PyPegen_raise_error reports at when set. */
    public Token known_err_token;

    /** C: p->tok->filename, for warnings. */
    public String filename = "<unknown>";

    /** C: p->tok->module, for warnings; may be null. */
    public String module;

    /** The pending exception (C: the one set with PyErr_*), or null. */
    private PythonSyntaxError error;

    /** A warning issued while parsing: the arguments of C's PyErr_WarnExplicitObject. */
    public static final class ParserWarning {
        public final String category;
        public final String message;
        public final String filename;
        public final int lineno;
        public final String module;

        public ParserWarning(String category, String message, String filename, int lineno,
                String module) {
            this.category = category;
            this.message = message;
            this.filename = filename;
            this.lineno = lineno;
            this.module = module;
        }

        @Override
        public String toString() {
            return filename + ":" + lineno + ": " + category + ": " + message;
        }
    }

    /**
     * Where warnings go: the part of Python's warnings machinery that
     * PyErr_WarnExplicitObject runs. warn returns false when the warnings
     * filters turn the warning into an error ("error" action); the parser then
     * raises it as an exception of the warning's category.
     *
     * <p>It is called on {@link #runParser}'s parser thread, not the caller's,
     * so it can't rely on thread-local state such as Jython's ThreadState.
     */
    public interface WarningHandler {
        boolean warn(ParserWarning w);
    }

    /** Warnings recorded by the default handler, in order. */
    public final List<ParserWarning> warnings = new ArrayList<>();

    /** The warning handler; by default it records each warning in warnings. */
    public WarningHandler warning_handler = w -> {
        warnings.add(w);
        return true;
    };

    public Parser(TokenSource tok, int start_rule) {
        this.tok = tok;
        this.start_rule = start_rule;
    }

    /** _PyPegen_fill_token: append the next token, mapping keyword names. */
    public int fillToken() {
        Token t = nextFromSource();
        // Record and skip '# type: ignore' comments
        while (t != null && t.type == TYPE_IGNORE) {
            type_ignore_comments.add(new TypeIgnoreComment(t.lineno, t.string));
            t = nextFromSource();
        }
        if (t == null) {
            return -1;
        }
        // If we have reached the end and we are in single input mode we need to insert a newline and reset the parsing
        if (start_rule == SINGLE_INPUT && t.type == ENDMARKER && parsing_started) {
            t = new Token(NEWLINE, "", t.lineno, t.col_offset, t.end_lineno, t.end_col_offset); /* Add an extra newline */
            parsing_started = false;

            // C: if (p->tok->indent && !(p->flags & PyPARSE_DONT_IMPLY_DEDENT))
            //        { p->tok->pendin = -p->tok->indent; p->tok->indent = 0; }
            if ((flags & ActionHelpers.PyPARSE_DONT_IMPLY_DEDENT) == 0) {
                tok.implyDedents();
            }
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
        // initialize_token
        tokens[fill++] = t;

        if (t.type == ERRORTOKEN && tok.done() == ActionHelpers.E_DECODE) {
            adoptTokenizerError();
            return ActionHelpers._Pypegen_raise_decode_error(this);
        }

        if (t.type == ERRORTOKEN) {
            adoptTokenizerError();
            return ActionHelpers._Pypegen_tokenizer_error(this);
        }
        return 0;
    }

    /**
     * C's tokenizer sets its exceptions directly (PyErr_*); a TokenSource
     * hands its exception over through error(), which becomes the pending one.
     */
    private void adoptTokenizerError() {
        PythonSyntaxError e = tok.error();
        if (e != null && error == null) {
            error = e;
        }
    }

    private Token nextFromSource() {
        return tok.next();
    }

    /**
     * Parses (as _PyPegen_run_parser) on a thread with a
     * {@link LargeStack#STACK_SIZE} stack, so that input CPython accepts
     * doesn't overflow the caller's stack. Returns null on failure, with the
     * exception (if any) available from {@link #getError()}. The warning
     * handler runs on that thread too.
     */
    public Object runParser(GeneratedParser parser) {
        return LargeStack.call(() -> _PyPegen_run_parser(parser));
    }

    /**
     * _PyPegen_run_parser: parse, and on failure parse again with the invalid_*
     * rules enabled to find the best error. Returns null on failure, with the
     * exception (if any) available from {@link #getError()}.
     */
    private Object _PyPegen_run_parser(GeneratedParser parser) {
        Object res = parse(parser);
        assert level == 0 || errorMatches("MemoryError") || errorMatches("ValueError");
        if (res != null && errorOccurred()) {
            // Discard a result returned with an exception still pending
            // (e.g. a MemoryError from a recovered-from allocation failure).
            return null;
        }
        if (res == null) {
            if ((flags & ActionHelpers.PyPARSE_ALLOW_INCOMPLETE_INPUT) != 0 && _is_end_of_source()) {
                clearError();
                return ActionHelpers._PyPegen_raise_error(this, "IncompleteInputError", 0,
                        "incomplete input");
            }
            if (errorOccurred() && !errorMatches("SyntaxError")) {
                return null;
            }
            // Make a second parser pass. In this pass we activate heavier and slower checks
            // to produce better error messages and more complete diagnostics. Extra "invalid_*"
            // rules will be active during parsing.
            Token last_token = tokens[fill - 1];
            reset_parser_state_for_error_pass();
            parse(parser);

            // Set SyntaxErrors accordingly depending on the parser/tokenizer status at the failure
            // point.
            ActionHelpers._Pypegen_set_syntax_error(this, last_token);

            // Set the metadata in the exception from p->last_stmt_location
            if (errorMatches("SyntaxError")) {
                _PyPegen_set_syntax_error_metadata();
            }
            return null;
        }

        if (start_rule == SINGLE_INPUT && badSingleStatement()) {
            return ActionHelpers.RAISE_SYNTAX_ERROR(this,
                    "multiple statements found while compiling a single statement");
        }
        return res;
    }

    /**
     * _PyPegen_parse (GeneratedParser.parse). An AstFactory constructor
     * throws AstValueError where C's sets a ValueError and returns NULL; it
     * becomes the pending ValueError here, which ends the parse as in C.
     */
    private Object parse(GeneratedParser parser) {
        try {
            return parser.parse();
        } catch (org.python.pegen.ast.AstValueError e) {
            error_indicator = true;
            setError("ValueError", e.getMessage());
            return null;
        }
    }

    /** reset_parser_state_for_error_pass */
    private void reset_parser_state_for_error_pass() {
        last_stmt_location.lineno = 0;
        last_stmt_location.col_offset = 0;
        last_stmt_location.end_lineno = 0;
        last_stmt_location.end_col_offset = 0;
        for (int i = 0; i < fill; i++) {
            tokens[i].memo = null;
        }
        mark = 0;
        call_invalid_rules = true;
        // (C also stops an interactive tokenizer asking for more input here:
        // tok->interactive_underflow = IUNDERFLOW_STOP.)
    }

    /** _is_end_of_source */
    private boolean _is_end_of_source() {
        int err = tok.done();
        return err == ActionHelpers.E_EOF || err == ActionHelpers.E_EOFS
                || err == ActionHelpers.E_EOLS;
    }

    /** _PyPegen_set_syntax_error_metadata: SyntaxError._metadata, from last_stmt_location. */
    private void _PyPegen_set_syntax_error_metadata() {
        error.metadata_location =
                new int[] {last_stmt_location.lineno, last_stmt_location.col_offset};
        error.metadata_source = tok.source();
    }

    /**
     * bad_single_statement: whether anything but whitespace and comments
     * follows a single_input statement in the source.
     */
    private boolean badSingleStatement() {
        String rest = tok.rest();
        if (rest == null) {
            return badSingleStatementTokens();
        }
        int cur = 0;
        char c = cur < rest.length() ? rest.charAt(cur) : 0;

        for (;;) {
            while (c == ' ' || c == '\t' || c == '\n' || c == '\014') {
                c = ++cur < rest.length() ? rest.charAt(cur) : 0;
            }

            if (c == 0) {
                return false;
            }

            if (c != '#') {
                return true;
            }

            /* Suck up comment. */
            while (c != 0 && c != '\n') {
                c = ++cur < rest.length() ? rest.charAt(cur) : 0;
            }
        }
    }

    /**
     * bad_single_statement for a TokenSource without the source text: any
     * unread token other than NEWLINE, DEDENT or ENDMARKER. (This reads
     * ahead, which C's scan of the text doesn't.)
     */
    private boolean badSingleStatementTokens() {
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
            ActionHelpers.RAISE_SYNTAX_ERROR_KNOWN_LOCATION(this, t, "expected '%s'", expected);
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
            ActionHelpers.RAISE_SYNTAX_ERROR(this, "expected (%s)", expected);
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

    /** _PyPegen_dummy_name, for the recognizer (generate.py --skip-actions). */
    public Object dummyName(Object... ignored) {
        return DUMMY;
    }

    /** _PyPegen_dummy_name where a Token is expected, for the recognizer. */
    public Token dummyToken() {
        return DUMMY_TOKEN;
    }

    /** PyErr_Occurred */
    public boolean errorOccurred() {
        return error != null;
    }

    /** The pending exception, or null. */
    public PythonSyntaxError getError() {
        return error;
    }

    /** PyErr_SetString: sets the pending exception; unlike raiseError, leaves error_indicator alone. */
    public void setError(String errtype, String msg) {
        error = new PythonSyntaxError(errtype, msg);
    }

    /** PyErr_SetObject / PyErr_Restore: makes e (which may be null) the pending exception. */
    public void setError(PythonSyntaxError e) {
        error = e;
    }

    /** The superclass of each exception type used here, standing in for Python's class hierarchy. */
    private static final Map<String, String> EXCEPTION_BASES = new HashMap<>();
    static {
        EXCEPTION_BASES.put("UnicodeDecodeError", "UnicodeError");
        EXCEPTION_BASES.put("UnicodeError", "ValueError");
        EXCEPTION_BASES.put("ValueError", "Exception");
        EXCEPTION_BASES.put("IncompleteInputError", "SyntaxError");
        EXCEPTION_BASES.put("TabError", "IndentationError");
        EXCEPTION_BASES.put("IndentationError", "SyntaxError");
        EXCEPTION_BASES.put("SyntaxError", "Exception");
        EXCEPTION_BASES.put("SystemError", "Exception");
        EXCEPTION_BASES.put("OverflowError", "Exception");
        EXCEPTION_BASES.put("MemoryError", "Exception");
        EXCEPTION_BASES.put("KeyboardInterrupt", "BaseException");
        EXCEPTION_BASES.put("Exception", "BaseException");
        EXCEPTION_BASES.put("SyntaxWarning", "Warning");
        EXCEPTION_BASES.put("DeprecationWarning", "Warning");
        EXCEPTION_BASES.put("Warning", "Exception");
    }

    /** PyErr_ExceptionMatches: whether the pending exception is errtype or a subclass of it. */
    public boolean errorMatches(String errtype) {
        for (String t = error == null ? null : error.type; t != null; t = EXCEPTION_BASES.get(t)) {
            if (t.equals(errtype)) {
                return true;
            }
        }
        return false;
    }

    /** str() of the pending exception's message, or null. */
    public String errorMessage() {
        return error == null ? null : error.msg;
    }

    /** PyErr_Clear */
    public void clearError() {
        error = null;
    }

    /**
     * _PyErr_EmitSyntaxWarning: issues a SyntaxWarning; one raised as an
     * error becomes a SyntaxError at the given location (1-based columns).
     */
    public int emitSyntaxWarning(String msg, int lineno, int col_offset, int end_lineno,
            int end_col_offset) {
        if (warnExplicit("SyntaxWarning", msg, lineno) < 0) {
            if (errorMatches("SyntaxWarning")) {
                /* Replace the SyntaxWarning exception with a SyntaxError
                   to get a more accurate error report */
                clearError();
                // C: _PyErr_RaiseSyntaxError, which leaves error_indicator alone.
                // Its text is read from the file named filename, if any.
                setError(new PythonSyntaxError("SyntaxError", msg, lineno, col_offset, null,
                        end_lineno, end_col_offset));
            }
            return -1;
        }
        return 0;
    }

    /**
     * PyErr_WarnExplicitObject(category, message, filename, lineno, module,
     * NULL): passes the warning to warning_handler; if that makes it an error,
     * sets it as the pending exception and returns -1.
     */
    public int warnExplicit(String category, String message, int lineno) {
        if (!warning_handler.warn(new ParserWarning(category, message, filename, lineno, module))) {
            setError(category, message);
            return -1;
        }
        return 0;
    }

    /** _Pypegen_stack_overflow (pegen_errors.c) */
    public void stackOverflow() {
        ActionHelpers._Pypegen_stack_overflow(this);
    }

    /** Records an error with no location, e.g. a ValueError, and sets error_indicator. */
    public void raiseError(String errtype, String msg) {
        error_indicator = true;
        setError(errtype, msg);
    }
}
