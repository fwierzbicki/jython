package org.python.pegen.lexer;

import static org.python.pegen.ActionHelpers.E_COLUMNOVERFLOW;
import static org.python.pegen.ActionHelpers.E_DECODE;
import static org.python.pegen.ActionHelpers.E_DEDENT;
import static org.python.pegen.ActionHelpers.E_EOF;
import static org.python.pegen.ActionHelpers.E_EOFS;
import static org.python.pegen.ActionHelpers.E_EOLS;
import static org.python.pegen.ActionHelpers.E_ERROR;
import static org.python.pegen.ActionHelpers.E_LINECONT;
import static org.python.pegen.ActionHelpers.E_OK;
import static org.python.pegen.ActionHelpers.E_TOODEEP;
import static org.python.pegen.TokenTypes.COMMENT;
import static org.python.pegen.TokenTypes.DEDENT;
import static org.python.pegen.TokenTypes.DOT;
import static org.python.pegen.TokenTypes.ELLIPSIS;
import static org.python.pegen.TokenTypes.ENDMARKER;
import static org.python.pegen.TokenTypes.ERRORTOKEN;
import static org.python.pegen.TokenTypes.FSTRING_END;
import static org.python.pegen.TokenTypes.FSTRING_MIDDLE;
import static org.python.pegen.TokenTypes.FSTRING_START;
import static org.python.pegen.TokenTypes.INDENT;
import static org.python.pegen.TokenTypes.NAME;
import static org.python.pegen.TokenTypes.NEWLINE;
import static org.python.pegen.TokenTypes.NL;
import static org.python.pegen.TokenTypes.NUMBER;
import static org.python.pegen.TokenTypes.OP;
import static org.python.pegen.TokenTypes.STRING;
import static org.python.pegen.TokenTypes.TSTRING_END;
import static org.python.pegen.TokenTypes.TSTRING_MIDDLE;
import static org.python.pegen.TokenTypes.TSTRING_START;
import static org.python.pegen.TokenTypes.TYPE_COMMENT;
import static org.python.pegen.TokenTypes.TYPE_IGNORE;
import static org.python.pegen.TokenTypes._PyToken_OneChar;
import static org.python.pegen.TokenTypes._PyToken_ThreeChars;
import static org.python.pegen.TokenTypes._PyToken_TwoChars;
import static org.python.pegen.lexer.Helpers._PyTokenizer_indenterror;
import static org.python.pegen.lexer.Helpers._PyTokenizer_parser_warn;
import static org.python.pegen.lexer.Helpers._PyTokenizer_syntaxerror;
import static org.python.pegen.lexer.Helpers._PyTokenizer_syntaxerror_known_range;
import static org.python.pegen.lexer.Helpers._PyTokenizer_warn_invalid_escape_sequence;
import static org.python.pegen.lexer.State.EOF;
import static org.python.pegen.lexer.State.FSTRING;
import static org.python.pegen.lexer.State.INSIDE_FSTRING;
import static org.python.pegen.lexer.State.INSIDE_FSTRING_EXPR;
import static org.python.pegen.lexer.State.INSIDE_FSTRING_EXPR_AT_TOP;
import static org.python.pegen.lexer.State.MAXFSTRINGLEVEL;
import static org.python.pegen.lexer.State.MAXINDENT;
import static org.python.pegen.lexer.State.MAXLEVEL;
import static org.python.pegen.lexer.State.MAX_EXPR_NESTING;
import static org.python.pegen.lexer.State.NULL;
import static org.python.pegen.lexer.State.TOK_FSTRING_MODE;
import static org.python.pegen.lexer.State.TOK_REGULAR_MODE;
import static org.python.pegen.lexer.State.TSTRING;
import static org.python.pegen.lexer.State._PyLexer_token_setup;
import static org.python.pegen.lexer.State._PyLexer_type_comment_token_setup;

import java.nio.charset.StandardCharsets;

import org.python.pegen.lexer.State.tok_state;
import org.python.pegen.lexer.State.token;
import org.python.pegen.lexer.State.tokenizer_mode;

/**
 * A port of Parser/lexer/lexer.c: the tokenizer proper, {@link #_PyTokenizer_Get}.
 *
 * <p>C's gotos become labelled loops ({@code nextline}, {@code again}), a
 * jump variable ({@code f_string_quote}, {@code letter_quote}) and
 * {@link #tok_number_tail} ({@code fraction}, {@code exponent},
 * {@code imaginary}). Characters are the input's bytes, as in C.
 *
 * <p>Only the string tokenizers are ported, which hold the whole input in
 * one buffer, so it never moves: pointers into it, such as a
 * tokenizer_mode's {@code last_expr_start}, are indexes.
 */
public final class Lexer {

    private Lexer() {}

    /* Alternate tab spacing */
    private static final int ALTTABSIZE = 1;

    private static boolean is_potential_identifier_start(int c) {
        return (c >= 'a' && c <= 'z')
               || (c >= 'A' && c <= 'Z')
               || c == '_'
               || (c >= 128);
    }

    private static boolean is_potential_identifier_char(int c) {
        return (c >= 'a' && c <= 'z')
               || (c >= 'A' && c <= 'Z')
               || (c >= '0' && c <= '9')
               || c == '_'
               || (c >= 128);
    }

    private static boolean Py_ISDIGIT(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean Py_ISXDIGIT(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean Py_ISALNUM(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static int Py_TOLOWER(int c) {
        return c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c;
    }

    static tokenizer_mode TOK_GET_MODE(tok_state tok) {
        return tok.tok_mode_stack[tok.tok_mode_stack_index];
    }

    private static tokenizer_mode TOK_NEXT_MODE(tok_state tok) {
        return tok.tok_mode_stack[++tok.tok_mode_stack_index];
    }

    private static int FTSTRING_MIDDLE(tokenizer_mode tok_mode) {
        return tok_mode.string_kind == TSTRING ? TSTRING_MIDDLE : FSTRING_MIDDLE;
    }

    private static int FTSTRING_END(tokenizer_mode tok_mode) {
        return tok_mode.string_kind == TSTRING ? TSTRING_END : FSTRING_END;
    }

    private static char TOK_GET_STRING_PREFIX(tok_state tok) {
        return TOK_GET_MODE(tok).string_kind == TSTRING ? 't' : 'f';
    }

    /* Spaces in this constant are treated as "zero or more spaces or tabs" when
       tokenizing. */
    private static final byte[] type_comment_prefix = "# type: ".getBytes(StandardCharsets.US_ASCII);

    private static boolean contains_null_bytes(byte[] a, int str, int size) {
        for (int i = str; i < str + size; i++) {
            if (a[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /* Get next char, updating state; error code goes into tok->done */
    static int tok_nextc(tok_state tok) {
        boolean rc;
        for (;;) {
            if (tok.cur != tok.inp) {
                if (Integer.compareUnsigned(tok.col_offset, Integer.MAX_VALUE) >= 0) {
                    tok.done = E_COLUMNOVERFLOW;
                    return EOF;
                }
                tok.col_offset++;
                return tok.input[tok.cur++] & 0xff; /* Fast path */
            }
            if (tok.done != E_OK) {
                return EOF;
            }
            rc = tok.underflow.underflow(tok);
            if (!rc) {
                tok.cur = tok.inp;
                return EOF;
            }
            tok.line_start = tok.cur;

            if (contains_null_bytes(tok.input, tok.line_start, tok.inp - tok.line_start)) {
                _PyTokenizer_syntaxerror(tok, "source code cannot contain null bytes");
                tok.cur = tok.inp;
                return EOF;
            }
        }
    }

    /* Back-up one character */
    static void tok_backup(tok_state tok, int c) {
        if (c != EOF) {
            if (--tok.cur < tok.buf) {
                throw new IllegalStateException("tokenizer beginning of buffer");
            }
            if ((tok.input[tok.cur] & 0xff) != (c & 0xff)) {
                throw new IllegalStateException("tok_backup: wrong character");
            }
            tok.col_offset--;
        }
    }

    private static int set_ftstring_expr(tok_state tok, token token, char c) {
        assert token != null;
        assert c == '}' || c == ':' || c == '!';
        tokenizer_mode tok_mode = TOK_GET_MODE(tok);

        if (!(tok_mode.in_debug || tok_mode.string_kind == TSTRING) || token.metadata != null) {
            return 0;
        }
        byte[] a = tok.input;
        int expression = tok_mode.last_expr_start;
        assert expression != NULL;
        assert expression <= tok.start;
        int expression_size = tok.start - expression;
        String res;

        // Look for a # character outside of string literals
        boolean hash_detected = false;
        boolean in_string = false;
        byte quote_char = 0;

        for (int i = 0; i < expression_size; i++) {
            byte ch = a[expression + i];

            // Skip escaped characters
            if (ch == '\\') {
                i++;
                continue;
            }

            // Handle quotes
            if (ch == '"' || ch == '\'') {
                // The following if/else block works becase there is an off number
                // of quotes in STRING tokens and the lexer only ever reaches this
                // function with valid STRING tokens.
                // For example: """hello"""
                // First quote: in_string = 1
                // Second quote: in_string = 0
                // Third quote: in_string = 1
                if (!in_string) {
                    in_string = true;
                    quote_char = ch;
                }
                else if (ch == quote_char) {
                    in_string = false;
                }
                continue;
            }

            // Check for # outside strings
            if (ch == '#' && !in_string) {
                hash_detected = true;
                break;
            }
        }
        // If we found a # character in the expression, we need to handle comments
        if (hash_detected) {
            // Allocate buffer for processed result
            byte[] result = new byte[expression_size + 1];

            int i = 0;  // Input position
            int j = 0;  // Output position
            in_string = false;     // Whether we're in a string
            quote_char = 0;    // Current string quote char

            // Process each character
            while (i < expression_size) {
                byte ch = a[expression + i];

                // Copy escaped characters without interpreting the escaped
                // character as a quote or comment marker.
                if (ch == '\\') {
                    result[j++] = ch;
                    i++;
                    if (i < expression_size) {
                        result[j++] = a[expression + i];
                    }
                }
                // Handle string quotes
                else if (ch == '"' || ch == '\'') {
                    // See comment above to understand this part
                    if (!in_string) {
                        in_string = true;
                        quote_char = ch;
                    } else if (ch == quote_char) {
                        in_string = false;
                    }
                    result[j++] = ch;
                }
                // Skip comments
                else if (ch == '#' && !in_string) {
                    while (i < expression_size &&
                           a[expression + i] != '\n') {
                        i++;
                    }
                    if (i < expression_size) {
                        result[j++] = '\n';
                    }
                }
                // Copy other chars
                else {
                    result[j++] = ch;
                }
                i++;
            }

            res = new String(result, 0, j, StandardCharsets.UTF_8);
        } else {
            res = new String(a, expression, expression_size, StandardCharsets.UTF_8);
        }

        token.metadata = res;
        return 0;
    }

    private static boolean lookahead(tok_state tok, String test) {
        int s = 0;
        boolean res = false;
        while (true) {
            int c = tok_nextc(tok);
            if (s == test.length()) {
                res = !is_potential_identifier_char(c);
            }
            else if (c == test.charAt(s)) {
                s++;
                continue;
            }

            tok_backup(tok, c);
            while (s != 0) {
                tok_backup(tok, test.charAt(--s));
            }
            return res;
        }
    }

    private static boolean verify_end_of_number(tok_state tok, int c, String kind) {
        if (tok.tok_extra_tokens) {
            // When we are parsing extra tokens, we don't want to emit warnings
            // about invalid literals, because we want to be a bit more liberal.
            return true;
        }
        /* Emit a deprecation warning only if the numeric literal is immediately
         * followed by one of keywords which can occur after a numeric literal
         * in valid code: "and", "else", "for", "if", "in", "is" and "or".
         * It allows to gradually deprecate existing valid code without adding
         * warning before error in most cases of invalid numeric literal (which
         * would be confusing and break existing tests).
         * Raise a syntax error with slightly better message than plain
         * "invalid syntax" if the numeric literal is immediately followed by
         * other keyword or identifier.
         */
        boolean r = false;
        if (c == 'a') {
            r = lookahead(tok, "nd");
        }
        else if (c == 'e') {
            r = lookahead(tok, "lse");
        }
        else if (c == 'f') {
            r = lookahead(tok, "or");
        }
        else if (c == 'i') {
            int c2 = tok_nextc(tok);
            if (c2 == 'f' || c2 == 'n' || c2 == 's') {
                r = true;
            }
            tok_backup(tok, c2);
        }
        else if (c == 'o') {
            r = lookahead(tok, "r");
        }
        else if (c == 'n') {
            r = lookahead(tok, "ot");
        }
        if (r) {
            tok_backup(tok, c);
            if (_PyTokenizer_parser_warn(tok, "SyntaxWarning",
                    "invalid %s literal", kind) != 0)
            {
                return false;
            }
            tok_nextc(tok);
        }
        else /* In future releases, only error will remain. */
        if (c < 128 && is_potential_identifier_char(c)) {
            tok_backup(tok, c);
            _PyTokenizer_syntaxerror(tok, "invalid %s literal", kind);
            return false;
        }
        return true;
    }

    /* Verify that the identifier follows PEP 3131. */
    private static boolean verify_identifier(tok_state tok) {
        if (tok.tok_extra_tokens) {
            return true;
        }
        if (tok.decoding_erred)
            return false;
        String s = new String(tok.input, tok.start, tok.cur - tok.start, StandardCharsets.UTF_8);
        int length = s.codePointCount(0, s.length());
        int invalid = _PyUnicode_ScanIdentifier(s);
        assert invalid >= 0;
        assert length > 0;
        if (invalid < length) {
            int ch = s.codePointAt(s.offsetByCodePoints(0, invalid));
            if (invalid + 1 < length) {
                /* Determine the offset in UTF-8 encoded input */
                String prefix = s.substring(0, s.offsetByCodePoints(0, invalid + 1));
                tok.cur = tok.start + prefix.getBytes(StandardCharsets.UTF_8).length;
            }
            if (UnicodeTables._PyUnicode_IsPrintable(ch)) {
                _PyTokenizer_syntaxerror(tok, "invalid character '%c' (U+%04X)", ch, ch);
            }
            else {
                _PyTokenizer_syntaxerror(tok, "invalid non-printable character U+%04X", ch);
            }
            return false;
        }
        return true;
    }

    /**
     * Objects/unicodeobject.c _PyUnicode_ScanIdentifier: the length of the
     * identifier at the start of s, in code points.
     */
    static int _PyUnicode_ScanIdentifier(String s) {
        int len = s.codePointCount(0, s.length());
        if (len == 0) {
            /* an empty string is not a valid identifier */
            return 0;
        }

        int ch = s.codePointAt(0);
        /* PEP 3131 says that the first character must be in
           XID_Start and subsequent characters in XID_Continue,
           and for the ASCII range, the 2.x rules apply (i.e
           start with letters and underscore, continue with
           letters, digits, underscore). However, given the current
           definition of XID_Start and XID_Continue, it is sufficient
           to check just for these, except that _ must be allowed
           as starting an identifier.  */
        if (!UnicodeTables._PyUnicode_IsXidStart(ch) && ch != 0x5F /* LOW LINE */) {
            return 0;
        }

        int i, offset = Character.charCount(ch);
        for (i = 1; i < len; i++) {
            ch = s.codePointAt(offset);
            if (!UnicodeTables._PyUnicode_IsXidContinue(ch)) {
                return i;
            }
            offset += Character.charCount(ch);
        }
        return i;
    }

    private static int tok_decimal_tail(tok_state tok) {
        int c;

        while (true) {
            do {
                c = tok_nextc(tok);
            } while (Py_ISDIGIT(c));
            if (c != '_') {
                break;
            }
            c = tok_nextc(tok);
            if (!Py_ISDIGIT(c)) {
                tok_backup(tok, c);
                _PyTokenizer_syntaxerror(tok, "invalid decimal literal");
                return 0;
            }
        }
        return c;
    }

    private static int tok_continuation_line(tok_state tok) {
        int c = tok_nextc(tok);
        if (c == '\r') {
            c = tok_nextc(tok);
        }
        if (c != '\n') {
            tok.done = E_LINECONT;
            return -1;
        }
        c = tok_nextc(tok);
        if (c == EOF) {
            tok.done = E_EOF;
            tok.cur = tok.inp;
            return -1;
        } else {
            tok_backup(tok, c);
        }
        return c;
    }

    private static int maybe_raise_syntax_error_for_string_prefixes(tok_state tok,
            boolean saw_b, boolean saw_r, boolean saw_u, boolean saw_f, boolean saw_t) {
        // Supported: rb, rf, rt (in any order)
        // Unsupported: ub, ur, uf, ut, bf, bt, ft (in any order)

        if (saw_u && saw_b) {
            return RETURN_SYNTAX_ERROR(tok, "u", "b");
        }
        if (saw_u && saw_r) {
            return RETURN_SYNTAX_ERROR(tok, "u", "r");
        }
        if (saw_u && saw_f) {
            return RETURN_SYNTAX_ERROR(tok, "u", "f");
        }
        if (saw_u && saw_t) {
            return RETURN_SYNTAX_ERROR(tok, "u", "t");
        }

        if (saw_b && saw_f) {
            return RETURN_SYNTAX_ERROR(tok, "b", "f");
        }
        if (saw_b && saw_t) {
            return RETURN_SYNTAX_ERROR(tok, "b", "t");
        }

        if (saw_f && saw_t) {
            return RETURN_SYNTAX_ERROR(tok, "f", "t");
        }

        return 0;
    }

    private static int RETURN_SYNTAX_ERROR(tok_state tok, String PREFIX1, String PREFIX2) {
        _PyTokenizer_syntaxerror_known_range(
            tok, tok.start + 1 - tok.line_start,
            tok.cur - tok.line_start,
            "'" + PREFIX1 + "' and '" + PREFIX2 + "' prefixes are incompatible");
        return -1;
    }

    // Targets of tok_get_normal_mode's gotos into the string code.
    private static final int NO_JUMP = 0;
    private static final int F_STRING_QUOTE = 1;
    private static final int LETTER_QUOTE = 2;

    private static int tok_get_normal_mode(tok_state tok, tokenizer_mode current_tok,
            token token) {
        int c;
        boolean blankline, nonascii;

        int p_start = NULL;
        int p_end = NULL;
      nextline:
        for (;;) {
        tok.start = NULL;
        tok.starting_col_offset = -1;
        blankline = false;


        /* Get indentation level */
        if (tok.atbol) {
            int col = 0;
            int altcol = 0;
            tok.atbol = false;
            int cont_line_col = 0;
            for (;;) {
                c = tok_nextc(tok);
                if (c == ' ') {
                    col++; altcol++;
                }
                else if (c == '\t') {
                    col = (col / tok.tabsize + 1) * tok.tabsize;
                    altcol = (altcol / ALTTABSIZE + 1) * ALTTABSIZE;
                }
                else if (c == '\014')  {/* Control-L (formfeed) */
                    col = altcol = 0; /* For Emacs users */
                }
                else if (c == '\\') {
                    // Indentation cannot be split over multiple physical lines
                    // using backslashes. This means that if we found a backslash
                    // preceded by whitespace, **the first one we find** determines
                    // the level of indentation of whatever comes next.
                    cont_line_col = cont_line_col != 0 ? cont_line_col : col;
                    if ((c = tok_continuation_line(tok)) == -1) {
                        return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                    }
                }
                else if (c == EOF && tok.error != null) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                }
                else {
                    break;
                }
            }
            tok_backup(tok, c);
            if (c == '#' || c == '\n' || c == '\r') {
                /* Lines with only whitespace and/or comments
                   shouldn't affect the indentation and are
                   not passed to the parser as NEWLINE tokens,
                   except *totally* empty lines in interactive
                   mode, which signal the end of a command group. */
                if (col == 0 && c == '\n' && tok.prompt != null) {
                    blankline = false; /* Let it through */
                }
                else if (tok.prompt != null && tok.lineno == 1) {
                    /* In interactive mode, if the first line contains
                       only spaces and/or a comment, let it through. */
                    blankline = false;
                    col = altcol = 0;
                }
                else {
                    blankline = true; /* Ignore completely */
                }
                /* We can't jump back right here since we still
                   may need to skip to the end of a comment */
            }
            if (!blankline && tok.level == 0) {
                col = cont_line_col != 0 ? cont_line_col : col;
                altcol = cont_line_col != 0 ? cont_line_col : altcol;
                if (col == tok.indstack[tok.indent]) {
                    /* No change */
                    if (altcol != tok.altindstack[tok.indent]) {
                        return MAKE_TOKEN(tok, token, _PyTokenizer_indenterror(tok), p_start, p_end);
                    }
                }
                else if (col > tok.indstack[tok.indent]) {
                    /* Indent -- always one */
                    if (tok.indent+1 >= MAXINDENT) {
                        tok.done = E_TOODEEP;
                        tok.cur = tok.inp;
                        return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                    }
                    if (altcol <= tok.altindstack[tok.indent]) {
                        return MAKE_TOKEN(tok, token, _PyTokenizer_indenterror(tok), p_start, p_end);
                    }
                    tok.pendin++;
                    tok.indstack[++tok.indent] = col;
                    tok.altindstack[tok.indent] = altcol;
                }
                else /* col < tok->indstack[tok->indent] */ {
                    /* Dedent -- any number, must be consistent */
                    while (tok.indent > 0 &&
                        col < tok.indstack[tok.indent]) {
                        tok.pendin--;
                        tok.indent--;
                    }
                    if (col != tok.indstack[tok.indent]) {
                        tok.done = E_DEDENT;
                        tok.cur = tok.inp;
                        return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                    }
                    if (altcol != tok.altindstack[tok.indent]) {
                        return MAKE_TOKEN(tok, token, _PyTokenizer_indenterror(tok), p_start, p_end);
                    }
                }
            }
        }

        tok.start = tok.cur;
        tok.starting_col_offset = tok.col_offset;

        /* Return pending indents/dedents */
        if (tok.pendin != 0) {
            if (tok.pendin < 0) {
                if (tok.tok_extra_tokens) {
                    p_start = tok.cur;
                    p_end = tok.cur;
                }
                tok.pendin++;
                return MAKE_TOKEN(tok, token, DEDENT, p_start, p_end);
            }
            else {
                if (tok.tok_extra_tokens) {
                    p_start = tok.buf;
                    p_end = tok.cur;
                }
                tok.pendin--;
                return MAKE_TOKEN(tok, token, INDENT, p_start, p_end);
            }
        }

        /* Peek ahead at the next character */
        c = tok_nextc(tok);
        tok_backup(tok, c);

      again:
        for (;;) {
        tok.start = NULL;
        /* Skip spaces */
        do {
            c = tok_nextc(tok);
        } while (c == ' ' || c == '\t' || c == '\014');

        /* Set start of current token */
        tok.start = tok.cur == NULL ? NULL : tok.cur - 1;
        tok.starting_col_offset = tok.col_offset - 1;

        /* Skip comment, unless it's a type comment */
        if (c == '#') {

            int p = NULL;
            int prefix, type_start;
            int current_starting_col_offset;

            while (c != EOF && c != '\n' && c != '\r') {
                c = tok_nextc(tok);
            }

            if (tok.tok_extra_tokens) {
                p = tok.start;
            }

            if (tok.type_comments) {
                p = tok.start;
                current_starting_col_offset = tok.starting_col_offset;
                prefix = 0;
                while (prefix < type_comment_prefix.length && p < tok.cur) {
                    if (type_comment_prefix[prefix] == ' ') {
                        while (tok.at(p) == ' ' || tok.at(p) == '\t') {
                            p++;
                            current_starting_col_offset++;
                        }
                    } else if (type_comment_prefix[prefix] == tok.at(p)) {
                        p++;
                        current_starting_col_offset++;
                    } else {
                        break;
                    }

                    prefix++;
                }

                /* This is a type comment if we matched all of type_comment_prefix. */
                if (prefix == type_comment_prefix.length) {
                    boolean is_type_ignore;
                    // +6 in order to skip the word 'ignore'
                    int ignore_end = p + 6;
                    final int ignore_end_col_offset = current_starting_col_offset + 6;
                    tok_backup(tok, c);  /* don't eat the newline or EOF */

                    type_start = p;

                    /* A TYPE_IGNORE is "type: ignore" followed by the end of the token
                     * or anything ASCII and non-alphanumeric. */
                    is_type_ignore = (
                        tok.cur >= ignore_end && memcmp_ignore(tok, p)
                        && !(tok.cur > ignore_end
                             && (tok.at(ignore_end) >= 128 || Py_ISALNUM(tok.at(ignore_end)))));

                    if (is_type_ignore) {
                        p_start = ignore_end;
                        p_end = tok.cur;

                        /* If this type ignore is the only thing on the line, consume the newline also. */
                        if (blankline) {
                            tok_nextc(tok);
                            tok.atbol = true;
                        }
                        return _PyLexer_type_comment_token_setup(tok, token, TYPE_IGNORE,
                                ignore_end_col_offset, tok.col_offset, p_start, p_end);
                    } else {
                        p_start = type_start;
                        p_end = tok.cur;
                        return _PyLexer_type_comment_token_setup(tok, token, TYPE_COMMENT,
                                current_starting_col_offset, tok.col_offset, p_start, p_end);
                    }
                }
            }
            if (tok.tok_extra_tokens) {
                tok_backup(tok, c);  /* don't eat the newline or EOF */
                p_start = p;
                p_end = tok.cur;
                tok.comment_newline = blankline;
                return MAKE_TOKEN(tok, token, COMMENT, p_start, p_end);
            }
        }

        // C: if (tok->done == E_INTERACT_STOP), which only the interactive
        // tokenizer sets.

        /* Check for EOF and errors now */
        if (c == EOF) {
            if (tok.level != 0) {
                return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
            }
            return MAKE_TOKEN(tok, token, tok.done == E_EOF ? ENDMARKER : ERRORTOKEN, p_start, p_end);
        }

        int jump = NO_JUMP;

        /* Identifier (most frequent token!) */
        nonascii = false;
        if (is_potential_identifier_start(c)) {
            /* Process the various legal combinations of b"", r"", u"", and f"". */
            boolean saw_b = false, saw_r = false, saw_u = false, saw_f = false, saw_t = false;
            while (true) {
                if (!saw_b && (c == 'b' || c == 'B')) {
                    saw_b = true;
                }
                /* Since this is a backwards compatibility support literal we don't
                   want to support it in arbitrary order like byte literals. */
                else if (!saw_u && (c == 'u'|| c == 'U')) {
                    saw_u = true;
                }
                /* ur"" and ru"" are not supported */
                else if (!saw_r && (c == 'r' || c == 'R')) {
                    saw_r = true;
                }
                else if (!saw_f && (c == 'f' || c == 'F')) {
                    saw_f = true;
                }
                else if (!saw_t && (c == 't' || c == 'T')) {
                    saw_t = true;
                }
                else {
                    break;
                }
                c = tok_nextc(tok);
                if (c == '"' || c == '\'') {
                    // Raise error on incompatible string prefixes:
                    int status = maybe_raise_syntax_error_for_string_prefixes(
                        tok, saw_b, saw_r, saw_u, saw_f, saw_t);
                    if (status < 0) {
                        return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                    }

                    // Handle valid f or t string creation:
                    if (saw_f || saw_t) {
                        jump = F_STRING_QUOTE;
                    } else {
                        jump = LETTER_QUOTE;
                    }
                    break;
                }
            }
            if (jump == NO_JUMP) {
                while (is_potential_identifier_char(c)) {
                    if (c >= 128) {
                        nonascii = true;
                    }
                    c = tok_nextc(tok);
                }
                tok_backup(tok, c);
                if (nonascii && !verify_identifier(tok)) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                }

                p_start = tok.start;
                p_end = tok.cur;

                return MAKE_TOKEN(tok, token, NAME, p_start, p_end);
            }
        }

        if (jump == NO_JUMP) {
            if (c == '\r') {
                c = tok_nextc(tok);
            }

            /* Newline */
            if (c == '\n') {
                tok.atbol = true;
                if (blankline || tok.level > 0) {
                    if (tok.tok_extra_tokens) {
                        if (tok.comment_newline) {
                            tok.comment_newline = false;
                        }
                        p_start = tok.start;
                        p_end = tok.cur;
                        return MAKE_TOKEN(tok, token, NL, p_start, p_end);
                    }
                    continue nextline;
                }
                if (tok.comment_newline && tok.tok_extra_tokens) {
                    tok.comment_newline = false;
                    p_start = tok.start;
                    p_end = tok.cur;
                    return MAKE_TOKEN(tok, token, NL, p_start, p_end);
                }
                p_start = tok.start;
                p_end = tok.cur - 1; /* Leave '\n' out of the string */
                tok.cont_line = false;
                return MAKE_TOKEN(tok, token, NEWLINE, p_start, p_end);
            }

            /* Period or number starting with period? */
            if (c == '.') {
                c = tok_nextc(tok);
                if (Py_ISDIGIT(c)) {
                    // C: goto fraction;
                    return tok_number_tail(tok, token, c, true);
                } else if (c == '.') {
                    c = tok_nextc(tok);
                    if (c == '.') {
                        p_start = tok.start;
                        p_end = tok.cur;
                        return MAKE_TOKEN(tok, token, ELLIPSIS, p_start, p_end);
                    }
                    else {
                        tok_backup(tok, c);
                    }
                    tok_backup(tok, '.');
                }
                else {
                    tok_backup(tok, c);
                }
                p_start = tok.start;
                p_end = tok.cur;
                return MAKE_TOKEN(tok, token, DOT, p_start, p_end);
            }

            /* Number */
            if (Py_ISDIGIT(c)) {
                return tok_number(tok, token, c);
            }
        }

        // f_string_quote:
        if (jump != LETTER_QUOTE
            && ((Py_TOLOWER(tok.at(tok.start)) == 'f' || Py_TOLOWER(tok.at(tok.start)) == 'r' || Py_TOLOWER(tok.at(tok.start)) == 't')
            && (c == '\'' || c == '"'))) {

            int quote = c;
            int quote_size = 1;             /* 1 or 3 */

            /* Nodes of type STRING, especially multi line strings
               must be handled differently in order to get both
               the starting line number and the column offset right.
               (cf. issue 16806) */
            tok.first_lineno = tok.lineno;
            tok.multi_line_start = tok.line_start;

            /* Find the quote size and start of string */
            int after_quote = tok_nextc(tok);
            if (after_quote == quote) {
                int after_after_quote = tok_nextc(tok);
                if (after_after_quote == quote) {
                    quote_size = 3;
                }
                else {
                    // TODO: Check this
                    tok_backup(tok, after_after_quote);
                    tok_backup(tok, after_quote);
                }
            }
            if (after_quote != quote) {
                tok_backup(tok, after_quote);
            }


            p_start = tok.start;
            p_end = tok.cur;
            if (tok.tok_mode_stack_index + 1 >= MAXFSTRINGLEVEL) {
                return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "too many nested f-strings or t-strings"), p_start, p_end);
            }
            tokenizer_mode the_current_tok = TOK_NEXT_MODE(tok);
            the_current_tok.kind = TOK_FSTRING_MODE;
            the_current_tok.quote = (char) quote;
            the_current_tok.quote_size = quote_size;
            the_current_tok.start = tok.start;
            the_current_tok.multi_line_start = tok.line_start;
            the_current_tok.first_line = tok.lineno;
            the_current_tok.start_offset = -1;
            the_current_tok.multi_line_start_offset = -1;
            the_current_tok.last_expr_start = NULL;
            the_current_tok.last_expr_start_offset = -1;
            the_current_tok.in_format_spec = false;
            the_current_tok.in_debug = false;

            int string_kind = FSTRING;
            switch (tok.at(tok.start)) {
                case 'T':
                case 't':
                    the_current_tok.raw = Py_TOLOWER(tok.at(tok.start + 1)) == 'r';
                    string_kind = TSTRING;
                    break;
                case 'F':
                case 'f':
                    the_current_tok.raw = Py_TOLOWER(tok.at(tok.start + 1)) == 'r';
                    break;
                case 'R':
                case 'r':
                    the_current_tok.raw = true;
                    if (Py_TOLOWER(tok.at(tok.start + 1)) == 't') {
                        string_kind = TSTRING;
                    }
                    break;
                default:
                    throw new IllegalStateException("unreachable");
            }

            the_current_tok.string_kind = string_kind;
            the_current_tok.curly_bracket_depth = 0;
            the_current_tok.curly_bracket_expr_start_depth = -1;
            return string_kind == TSTRING ? MAKE_TOKEN(tok, token, TSTRING_START, p_start, p_end) : MAKE_TOKEN(tok, token, FSTRING_START, p_start, p_end);
        }

        // letter_quote:
        /* String */
        if (c == '\'' || c == '"') {
            int quote = c;
            int quote_size = 1;             /* 1 or 3 */
            int end_quote_size = 0;
            boolean has_escaped_quote = false;

            /* Nodes of type STRING, especially multi line strings
               must be handled differently in order to get both
               the starting line number and the column offset right.
               (cf. issue 16806) */
            tok.first_lineno = tok.lineno;
            tok.multi_line_start = tok.line_start;

            /* Find the quote size and start of string */
            c = tok_nextc(tok);
            if (c == quote) {
                c = tok_nextc(tok);
                if (c == quote) {
                    quote_size = 3;
                }
                else {
                    end_quote_size = 1;     /* empty string found */
                }
            }
            if (c != quote) {
                tok_backup(tok, c);
            }

            /* Get rest of string */
            while (end_quote_size != quote_size) {
                c = tok_nextc(tok);
                if (tok.done == E_ERROR) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                }
                if (tok.done == E_DECODE) {
                    break;
                }
                if (c == EOF || (quote_size == 1 && c == '\n')) {
                    assert tok.multi_line_start != NULL;
                    // shift the tok_state's location into
                    // the start of string, and report the error
                    // from the initial quote character
                    tok.cur = tok.start;
                    tok.cur++;
                    tok.line_start = tok.multi_line_start;
                    int start = tok.lineno;
                    tok.lineno = tok.first_lineno;

                    if (INSIDE_FSTRING(tok)) {
                        /* When we are in an f-string, before raising the
                         * unterminated string literal error, check whether
                         * does the initial quote matches with f-strings quotes
                         * and if it is, then this must be a missing '}' token
                         * so raise the proper error */
                        tokenizer_mode the_current_tok = TOK_GET_MODE(tok);
                        if (the_current_tok.quote == quote &&
                            the_current_tok.quote_size == quote_size) {
                            int level = tok.level - the_current_tok.curly_bracket_depth
                                        + the_current_tok.curly_bracket_expr_start_depth;
                            assert level >= 0 && level < tok.level;
                            assert tok.parenstack[level] == '{';
                            int lineno = tok.parenlinenostack[level];
                            if (lineno != tok.lineno) {
                                return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                                    "%c-string: expecting '}' to close '{' on line %d",
                                    TOK_GET_STRING_PREFIX(tok), lineno), p_start, p_end);
                            }
                            return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                                "%c-string: expecting '}'", TOK_GET_STRING_PREFIX(tok)), p_start, p_end);
                        }
                    }

                    if (quote_size == 3) {
                        _PyTokenizer_syntaxerror(tok, "unterminated triple-quoted string literal"
                                         + " (detected at line %d)", start);
                        if (c != '\n') {
                            tok.done = E_EOFS;
                        }
                        return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                    }
                    else {
                        if (has_escaped_quote) {
                            _PyTokenizer_syntaxerror(
                                tok,
                                "unterminated string literal (detected at line %d); "
                                + "perhaps you escaped the end quote?",
                                start
                            );
                        } else {
                            _PyTokenizer_syntaxerror(
                                tok, "unterminated string literal (detected at line %d)", start
                            );
                        }
                        if (c != '\n') {
                            tok.done = E_EOLS;
                        }
                        return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                    }
                }
                if (c == quote) {
                    end_quote_size += 1;
                }
                else {
                    end_quote_size = 0;
                    if (c == '\\') {
                        c = tok_nextc(tok);  /* skip escaped char */
                        if (c == quote) {  /* but record whether the escaped char was a quote */
                            has_escaped_quote = true;
                        }
                        if (c == '\r') {
                            c = tok_nextc(tok);
                        }
                    }
                }
            }

            p_start = tok.start;
            p_end = tok.cur;
            return MAKE_TOKEN(tok, token, STRING, p_start, p_end);
        }

        /* Line continuation */
        if (c == '\\') {
            if ((c = tok_continuation_line(tok)) == -1) {
                return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
            }
            tok.cont_line = true;
            continue again; /* Read next line */
        }

        /* Punctuation character */
        boolean is_punctuation = (c == ':' || c == '}' || c == '!' || c == '{');
        if (is_punctuation && INSIDE_FSTRING(tok) && INSIDE_FSTRING_EXPR(current_tok)) {
            /* This code block gets executed before the curly_bracket_depth is incremented
             * by the `{` case, so for ensuring that we are on the 0th level, we need
             * to adjust it manually */
            int cursor = current_tok.curly_bracket_depth - (c != '{' ? 1 : 0);
            boolean in_format_spec = current_tok.in_format_spec;
             boolean cursor_in_format_with_debug =
                 cursor == 1 && (current_tok.in_debug || in_format_spec);
             boolean cursor_valid = cursor == 0 || cursor_in_format_with_debug;
            if ((cursor_valid) && c != '{' && set_ftstring_expr(tok, token, (char) c) != 0) {
                return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
            }

            if (c == ':' && cursor == current_tok.curly_bracket_expr_start_depth) {
                current_tok.kind = TOK_FSTRING_MODE;
                current_tok.in_format_spec = true;
                p_start = tok.start;
                p_end = tok.cur;
                return MAKE_TOKEN(tok, token, _PyToken_OneChar(c), p_start, p_end);
            }
        }

        /* Check for two-character token */
        {
            int c2 = tok_nextc(tok);
            int current_token = _PyToken_TwoChars(c, c2);
            if (c == '<' && c2 == '>' && !tok.barry_as_bdfl) {
                current_token = OP;
            }
            if (current_token != OP) {
                int c3 = tok_nextc(tok);
                int current_token3 = _PyToken_ThreeChars(c, c2, c3);
                if (current_token3 != OP) {
                    current_token = current_token3;
                }
                else {
                    tok_backup(tok, c3);
                }
                p_start = tok.start;
                p_end = tok.cur;
                return MAKE_TOKEN(tok, token, current_token, p_start, p_end);
            }
            tok_backup(tok, c2);
        }

        /* Keep track of parentheses nesting level */
        switch (c) {
        case '(':
        case '[':
        case '{':
            if (tok.level >= MAXLEVEL) {
                return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "too many nested parentheses"), p_start, p_end);
            }
            tok.parenstack[tok.level] = (char) c;
            tok.parenlinenostack[tok.level] = tok.lineno;
            tok.parencolstack[tok.level] = tok.start - tok.line_start;
            tok.level++;
            if (INSIDE_FSTRING(tok)) {
                current_tok.curly_bracket_depth++;
            }
            break;
        case ')':
        case ']':
        case '}':
            if (INSIDE_FSTRING(tok) && current_tok.curly_bracket_depth == 0 && c == '}') {
                return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                    "%c-string: single '}' is not allowed", TOK_GET_STRING_PREFIX(tok)), p_start, p_end);
            }
            if (!tok.tok_extra_tokens && tok.level == 0) {
                return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "unmatched '%c'", c), p_start, p_end);
            }
            if (tok.level > 0) {
                tok.level--;
                int opening = tok.parenstack[tok.level];
                if (!tok.tok_extra_tokens && !((opening == '(' && c == ')') ||
                                                (opening == '[' && c == ']') ||
                                                (opening == '{' && c == '}'))) {
                    /* If the opening bracket belongs to an f-string's expression
                    part (e.g. f"{)}") and the closing bracket is an arbitrary
                    nested expression, then instead of matching a different
                    syntactical construct with it; we'll throw an unmatched
                    parentheses error. */
                    if (INSIDE_FSTRING(tok) && opening == '{') {
                        assert current_tok.curly_bracket_depth >= 0;
                        int previous_bracket = current_tok.curly_bracket_depth - 1;
                        if (previous_bracket == current_tok.curly_bracket_expr_start_depth) {
                            return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                                "%c-string: unmatched '%c'", TOK_GET_STRING_PREFIX(tok), c), p_start, p_end);
                        }
                    }
                    if (tok.parenlinenostack[tok.level] != tok.lineno) {
                        return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                                "closing parenthesis '%c' does not match "
                                + "opening parenthesis '%c' on line %d",
                                c, opening, tok.parenlinenostack[tok.level]), p_start, p_end);
                    }
                    else {
                        return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                                "closing parenthesis '%c' does not match "
                                + "opening parenthesis '%c'",
                                c, opening), p_start, p_end);
                    }
                }
            }

            if (INSIDE_FSTRING(tok)) {
                current_tok.curly_bracket_depth--;
                if (current_tok.curly_bracket_depth < 0) {
                    return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "%c-string: unmatched '%c'",
                        TOK_GET_STRING_PREFIX(tok), c), p_start, p_end);
                }
                if (c == '}' && current_tok.curly_bracket_depth == current_tok.curly_bracket_expr_start_depth) {
                    current_tok.curly_bracket_expr_start_depth--;
                    current_tok.kind = TOK_FSTRING_MODE;
                    current_tok.in_format_spec = false;
                    current_tok.in_debug = false;
                }
            }
            break;
        default:
            break;
        }

        if (!UnicodeTables._PyUnicode_IsPrintable(c)) {
            return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "invalid non-printable character U+%04X", c), p_start, p_end);
        }

        if( c == '=' && INSIDE_FSTRING_EXPR_AT_TOP(current_tok)) {
            current_tok.in_debug = true;
        }

        /* Punctuation character */
        p_start = tok.start;
        p_end = tok.cur;
        return MAKE_TOKEN(tok, token, _PyToken_OneChar(c), p_start, p_end);
        } // again
        } // nextline
    }

    /** C: memcmp(p, "ignore", 6) == 0. */
    private static boolean memcmp_ignore(tok_state tok, int p) {
        byte[] ignore = {'i', 'g', 'n', 'o', 'r', 'e'};
        for (int i = 0; i < 6; i++) {
            if (tok.input[p + i] != ignore[i]) {
                return false;
            }
        }
        return true;
    }

    /** C: MAKE_TOKEN(token_type), with tok_get_normal_mode's p_start and p_end. */
    private static int MAKE_TOKEN(tok_state tok, token token, int token_type, int p_start,
            int p_end) {
        return _PyLexer_token_setup(tok, token, token_type, p_start, p_end);
    }

    /** tok_get_normal_mode's Number case, from its first digit c. */
    private static int tok_number(tok_state tok, token token, int c) {
        if (c == '0') {
            /* Hex, octal or binary -- maybe. */
            c = tok_nextc(tok);
            if (c == 'x' || c == 'X') {
                /* Hex */
                c = tok_nextc(tok);
                do {
                    if (c == '_') {
                        c = tok_nextc(tok);
                    }
                    if (!Py_ISXDIGIT(c)) {
                        tok_backup(tok, c);
                        return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "invalid hexadecimal literal"), NULL, NULL);
                    }
                    do {
                        c = tok_nextc(tok);
                    } while (Py_ISXDIGIT(c));
                } while (c == '_');
                if (!verify_end_of_number(tok, c, "hexadecimal")) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
                }
            }
            else if (c == 'o' || c == 'O') {
                /* Octal */
                c = tok_nextc(tok);
                do {
                    if (c == '_') {
                        c = tok_nextc(tok);
                    }
                    if (c < '0' || c >= '8') {
                        if (Py_ISDIGIT(c)) {
                            return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                                    "invalid digit '%c' in octal literal", c), NULL, NULL);
                        }
                        else {
                            tok_backup(tok, c);
                            return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "invalid octal literal"), NULL, NULL);
                        }
                    }
                    do {
                        c = tok_nextc(tok);
                    } while ('0' <= c && c < '8');
                } while (c == '_');
                if (Py_ISDIGIT(c)) {
                    return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                            "invalid digit '%c' in octal literal", c), NULL, NULL);
                }
                if (!verify_end_of_number(tok, c, "octal")) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
                }
            }
            else if (c == 'b' || c == 'B') {
                /* Binary */
                c = tok_nextc(tok);
                do {
                    if (c == '_') {
                        c = tok_nextc(tok);
                    }
                    if (c != '0' && c != '1') {
                        if (Py_ISDIGIT(c)) {
                            return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "invalid digit '%c' in binary literal", c), NULL, NULL);
                        }
                        else {
                            tok_backup(tok, c);
                            return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "invalid binary literal"), NULL, NULL);
                        }
                    }
                    do {
                        c = tok_nextc(tok);
                    } while (c == '0' || c == '1');
                } while (c == '_');
                if (Py_ISDIGIT(c)) {
                    return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "invalid digit '%c' in binary literal", c), NULL, NULL);
                }
                if (!verify_end_of_number(tok, c, "binary")) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
                }
            }
            else {
                boolean nonzero = false;
                /* maybe old-style octal; c is first char of it */
                /* in any case, allow '0' as a literal */
                while (true) {
                    if (c == '_') {
                        c = tok_nextc(tok);
                        if (!Py_ISDIGIT(c)) {
                            tok_backup(tok, c);
                            return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "invalid decimal literal"), NULL, NULL);
                        }
                    }
                    if (c != '0') {
                        break;
                    }
                    c = tok_nextc(tok);
                }
                int zeros_end = tok.cur;
                if (Py_ISDIGIT(c)) {
                    nonzero = true;
                    c = tok_decimal_tail(tok);
                    if (c == 0) {
                        return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
                    }
                }
                if (c == '.') {
                    c = tok_nextc(tok);
                    // C: goto fraction;
                    return tok_number_tail(tok, token, c, true);
                }
                else if (c == 'e' || c == 'E') {
                    // C: goto exponent;
                    return tok_number_tail(tok, token, c, false);
                }
                else if (c == 'j' || c == 'J') {
                    // C: goto imaginary;
                    return tok_number_tail(tok, token, c, false);
                }
                else if (nonzero && !tok.tok_extra_tokens) {
                    /* Old-style octal: now disallowed. */
                    tok_backup(tok, c);
                    return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror_known_range(
                            tok, tok.start + 1 - tok.line_start,
                            zeros_end - tok.line_start,
                            "leading zeros in decimal integer "
                            + "literals are not permitted; "
                            + "use an 0o prefix for octal integers"), NULL, NULL);
                }
                if (!verify_end_of_number(tok, c, "decimal")) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
                }
            }
        }
        else {
            /* Decimal */
            c = tok_decimal_tail(tok);
            if (c == 0) {
                return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
            }
            return tok_number_tail(tok, token, c, false);
        }
        tok_backup(tok, c);
        return MAKE_TOKEN(tok, token, NUMBER, tok.start, tok.cur);
    }

    /**
     * The rest of a decimal number, after its integer part: c is the next
     * character. at_fraction is C's "goto fraction", with c the character
     * after the '.'; C's "goto exponent" and "goto imaginary" arrive with c
     * the 'e' or 'j', where this code finds them anyway.
     */
    private static int tok_number_tail(tok_state tok, token token, int c, boolean at_fraction) {
        /* Accept floating-point numbers. */
        if (!at_fraction && c == '.') {
            c = tok_nextc(tok);
            at_fraction = true;
        }
        if (at_fraction) {
            // fraction:
            /* Fraction */
            if (Py_ISDIGIT(c)) {
                c = tok_decimal_tail(tok);
                if (c == 0) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
                }
            }
        }
        if (c == 'e' || c == 'E') {
            int e;
            // exponent:
            e = c;
            /* Exponent part */
            c = tok_nextc(tok);
            if (c == '+' || c == '-') {
                c = tok_nextc(tok);
                if (!Py_ISDIGIT(c)) {
                    tok_backup(tok, c);
                    return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok, "invalid decimal literal"), NULL, NULL);
                }
            } else if (!Py_ISDIGIT(c)) {
                tok_backup(tok, c);
                if (!verify_end_of_number(tok, e, "decimal")) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
                }
                tok_backup(tok, e);
                return MAKE_TOKEN(tok, token, NUMBER, tok.start, tok.cur);
            }
            c = tok_decimal_tail(tok);
            if (c == 0) {
                return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
            }
        }
        if (c == 'j' || c == 'J') {
            /* Imaginary part */
            // imaginary:
            c = tok_nextc(tok);
            if (!verify_end_of_number(tok, c, "imaginary")) {
                return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
            }
        }
        else if (!verify_end_of_number(tok, c, "decimal")) {
            return MAKE_TOKEN(tok, token, ERRORTOKEN, NULL, NULL);
        }
        tok_backup(tok, c);
        return MAKE_TOKEN(tok, token, NUMBER, tok.start, tok.cur);
    }

    private static int tok_get_fstring_mode(tok_state tok, tokenizer_mode current_tok,
            token token) {
        int p_start = NULL;
        int p_end = NULL;
        int end_quote_size = 0;
        boolean unicode_escape = false;

        tok.start = tok.cur;
        tok.first_lineno = tok.lineno;
        tok.starting_col_offset = tok.col_offset;

        // If we start with a bracket, we defer to the normal mode as there is nothing for us to tokenize
        // before it.
        int start_char = tok_nextc(tok);
        if (start_char == '{') {
            int peek1 = tok_nextc(tok);
            tok_backup(tok, peek1);
            if (peek1 != '{') {
                current_tok.last_expr_start = tok.cur;
            }
            tok_backup(tok, start_char);
            if (peek1 != '{') {
                current_tok.curly_bracket_expr_start_depth++;
                if (current_tok.curly_bracket_expr_start_depth >= MAX_EXPR_NESTING) {
                    return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                        "%c-string: expressions nested too deeply", TOK_GET_STRING_PREFIX(tok)), p_start, p_end);
                }
                TOK_GET_MODE(tok).kind = TOK_REGULAR_MODE;
                return tok_get_normal_mode(tok, current_tok, token);
            }
        }
        else {
            tok_backup(tok, start_char);
        }

        // Check if we are at the end of the string
        boolean f_string_middle = false;
        for (int i = 0; i < current_tok.quote_size; i++) {
            int quote = tok_nextc(tok);
            if (quote != current_tok.quote) {
                tok_backup(tok, quote);
                f_string_middle = true; // C: goto f_string_middle;
                break;
            }
        }

        if (!f_string_middle) {
            p_start = tok.start;
            p_end = tok.cur;
            tok.tok_mode_stack_index--;
            return MAKE_TOKEN(tok, token, FTSTRING_END(current_tok), p_start, p_end);
        }

    // f_string_middle:

        // TODO: This is a bit of a hack, but it works for now. We need to find a better way to handle
        // this.
        tok.multi_line_start = tok.line_start;
        while (end_quote_size != current_tok.quote_size) {
            int c = tok_nextc(tok);
            if (tok.done == E_ERROR || tok.done == E_DECODE) {
                return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
            }
            boolean in_format_spec = (
                    current_tok.in_format_spec
                    &&
                    INSIDE_FSTRING_EXPR(current_tok)
            );

           if (c == EOF || (current_tok.quote_size == 1 && c == '\n')) {
                if (tok.decoding_erred) {
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                }

                // If we are in a format spec and we found a newline,
                // it means that the format spec ends here and we should
                // return to the regular mode.
                if (in_format_spec && c == '\n') {
                    if (current_tok.quote_size == 1) {
                        return MAKE_TOKEN(tok, token,
                            _PyTokenizer_syntaxerror(
                                tok,
                                "%c-string: newlines are not allowed in format specifiers for single quoted %c-strings",
                                TOK_GET_STRING_PREFIX(tok), TOK_GET_STRING_PREFIX(tok)
                            ), p_start, p_end
                        );
                    }
                    tok_backup(tok, c);
                    TOK_GET_MODE(tok).kind = TOK_REGULAR_MODE;
                    current_tok.in_format_spec = false;
                    p_start = tok.start;
                    p_end = tok.cur;
                    return MAKE_TOKEN(tok, token, FTSTRING_MIDDLE(current_tok), p_start, p_end);
                }

                assert tok.multi_line_start != NULL;
                // shift the tok_state's location into
                // the start of string, and report the error
                // from the initial quote character
                tok.cur = current_tok.start;
                tok.cur++;
                tok.line_start = current_tok.multi_line_start;
                int start = tok.lineno;

                tokenizer_mode the_current_tok = TOK_GET_MODE(tok);
                tok.lineno = the_current_tok.first_line;

                if (current_tok.quote_size == 3) {
                    _PyTokenizer_syntaxerror(tok,
                                        "unterminated triple-quoted %c-string literal"
                                        + " (detected at line %d)",
                                        TOK_GET_STRING_PREFIX(tok), start);
                    if (c != '\n') {
                        tok.done = E_EOFS;
                    }
                    return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                }
                else {
                    return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                                        "unterminated %c-string literal (detected at"
                                        + " line %d)", TOK_GET_STRING_PREFIX(tok), start), p_start, p_end);
                }
            }

            if (c == current_tok.quote) {
                end_quote_size += 1;
                continue;
            } else {
                end_quote_size = 0;
            }

            if (c == '{') {
                int peek = tok_nextc(tok);
                if (peek != '{' || in_format_spec) {
                    tok_backup(tok, peek);
                    current_tok.last_expr_start = tok.cur;
                    tok_backup(tok, c);
                    current_tok.curly_bracket_expr_start_depth++;
                    if (current_tok.curly_bracket_expr_start_depth >= MAX_EXPR_NESTING) {
                        return MAKE_TOKEN(tok, token, _PyTokenizer_syntaxerror(tok,
                            "%c-string: expressions nested too deeply", TOK_GET_STRING_PREFIX(tok)), p_start, p_end);
                    }
                    TOK_GET_MODE(tok).kind = TOK_REGULAR_MODE;
                    current_tok.in_format_spec = false;
                    p_start = tok.start;
                    p_end = tok.cur;
                } else {
                    p_start = tok.start;
                    p_end = tok.cur - 1;
                }
                return MAKE_TOKEN(tok, token, FTSTRING_MIDDLE(current_tok), p_start, p_end);
            } else if (c == '}') {
                if (unicode_escape) {
                    p_start = tok.start;
                    p_end = tok.cur;
                    return MAKE_TOKEN(tok, token, FTSTRING_MIDDLE(current_tok), p_start, p_end);
                }
                int peek = tok_nextc(tok);

                // The tokenizer can only be in the format spec if we have already completed the expression
                // scanning (indicated by the end of the expression being set) and we are not at the top level
                // of the bracket stack (-1 is the top level). Since format specifiers can't legally use double
                // brackets, we can bypass it here.
                int cursor = current_tok.curly_bracket_depth;
                if (peek == '}' && !in_format_spec && cursor == 0) {
                    p_start = tok.start;
                    p_end = tok.cur - 1;
                } else {
                    tok_backup(tok, peek);
                    tok_backup(tok, c);
                    TOK_GET_MODE(tok).kind = TOK_REGULAR_MODE;
                    current_tok.in_format_spec = false;
                    p_start = tok.start;
                    p_end = tok.cur;
                }
                return MAKE_TOKEN(tok, token, FTSTRING_MIDDLE(current_tok), p_start, p_end);
            } else if (c == '\\') {
                int peek = tok_nextc(tok);
                if (peek == '\r') {
                    peek = tok_nextc(tok);
                }
                // Special case when the backslash is right before a curly
                // brace. We have to restore and return the control back
                // to the loop for the next iteration.
                if (peek == '{' || peek == '}') {
                    if (!current_tok.raw) {
                        if (_PyTokenizer_warn_invalid_escape_sequence(tok, peek) != 0) {
                            return MAKE_TOKEN(tok, token, ERRORTOKEN, p_start, p_end);
                        }
                    }
                    tok_backup(tok, peek);
                    continue;
                }

                if (!current_tok.raw) {
                    if (peek == 'N') {
                        /* Handle named unicode escapes (\N{BULLET}) */
                        peek = tok_nextc(tok);
                        if (peek == '{') {
                            unicode_escape = true;
                        } else {
                            tok_backup(tok, peek);
                        }
                    }
                } /* else {
                    skip the escaped character
                }*/
            }
        }

        // Backup the f-string quotes to emit a final FSTRING_MIDDLE and
        // add the quotes to the FSTRING_END in the next tokenizer iteration.
        for (int i = 0; i < current_tok.quote_size; i++) {
            tok_backup(tok, current_tok.quote);
        }
        p_start = tok.start;
        p_end = tok.cur;
        return MAKE_TOKEN(tok, token, FTSTRING_MIDDLE(current_tok), p_start, p_end);
    }

    private static int tok_get(tok_state tok, token token) {
        tokenizer_mode current_tok = TOK_GET_MODE(tok);
        if (current_tok.kind == TOK_REGULAR_MODE) {
            return tok_get_normal_mode(tok, current_tok, token);
        } else {
            return tok_get_fstring_mode(tok, current_tok, token);
        }
    }

    public static int _PyTokenizer_Get(tok_state tok, token token) {
        int result = tok_get(tok, token);
        if (tok.decoding_erred) {
            result = ERRORTOKEN;
            tok.done = E_DECODE;
        }
        return result;
    }
}
