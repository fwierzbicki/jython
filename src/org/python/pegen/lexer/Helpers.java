package org.python.pegen.lexer;

import static org.python.pegen.ActionHelpers.E_DECODE;
import static org.python.pegen.ActionHelpers.E_ERROR;
import static org.python.pegen.ActionHelpers.E_TABSPACE;
import static org.python.pegen.TokenTypes.ERRORTOKEN;
import static org.python.pegen.lexer.State.NULL;
import static org.python.pegen.lexer.State.STATE_NORMAL;
import static org.python.pegen.lexer.State.STATE_SEEK_CODING;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.python.pegen.Parser;
import org.python.pegen.PythonSyntaxError;
import org.python.pegen.lexer.State.tok_state;

/**
 * A port of Parser/tokenizer/helpers.c: the tokenizer's errors and warnings,
 * newline translation, and the PEP 263 encoding checks.
 *
 * <p>The error functions take Java format strings where C takes
 * PyUnicode_FromFormat ones; the conversions used (%c with an int code
 * point, %d, %s, %04X) mean the same in both.
 */
public final class Helpers {

    private Helpers() {}

    /** C: tok->lineno++; tok->col_offset = 0; (helpers.h ADVANCE_LINENO). */
    static void ADVANCE_LINENO(tok_state tok) {
        tok.lineno++;
        tok.col_offset = 0;
    }

    /** C: set_readline, which the encoding checks call with a cookie's encoding. */
    interface SetReadline {
        boolean set_readline(tok_state tok, String enc);
    }

    /* ############## ERRORS ############## */

    private static int _syntaxerror_range(tok_state tok, String format, int col_offset,
            int end_col_offset, Object... vargs) {
        // In release builds, we don't want to overwrite a previous error, but in debug builds we
        // want to fail if we are not doing it so we can fix it.
        assert tok.done != E_ERROR;
        if (tok.done == E_ERROR) {
            return ERRORTOKEN;
        }
        String errmsg = String.format(format, vargs);

        String errtext = decodeReplace(tok.input, tok.line_start, tok.cur - tok.line_start);

        if (col_offset == -1) {
            col_offset = errtext.codePointCount(0, errtext.length());
        }
        if (end_col_offset == -1) {
            end_col_offset = col_offset;
        }

        int line_len = strcspn_newline(tok.input, tok.line_start);
        if (line_len != tok.cur - tok.line_start) {
            errtext = decodeReplace(tok.input, tok.line_start, line_len);
        }

        tok.error = new PythonSyntaxError("SyntaxError", errmsg, tok.lineno, col_offset, errtext,
                tok.lineno, end_col_offset);
        tok.done = E_ERROR;
        return ERRORTOKEN;
    }

    /** C: strcspn(s, "\n"), the length up to a newline or the NUL. */
    private static int strcspn_newline(byte[] a, int s) {
        int i = s;
        while (a[i] != 0 && a[i] != '\n') {
            i++;
        }
        return i - s;
    }

    /** C: PyUnicode_DecodeUTF8(s, size, "replace"). */
    static String decodeReplace(byte[] a, int s, int size) {
        return new String(a, s, size, StandardCharsets.UTF_8);
    }

    public static int _PyTokenizer_syntaxerror(tok_state tok, String format, Object... vargs) {
        // These errors are cleaned on startup. Todo: Fix it.
        return _syntaxerror_range(tok, format, -1, -1, vargs);
    }

    public static int _PyTokenizer_syntaxerror_known_range(tok_state tok, int col_offset,
            int end_col_offset, String format, Object... vargs) {
        return _syntaxerror_range(tok, format, col_offset, end_col_offset, vargs);
    }

    static int _PyTokenizer_indenterror(tok_state tok) {
        tok.done = E_TABSPACE;
        tok.cur = tok.inp;
        return ERRORTOKEN;
    }

    /** Returns NULL (C: char *, NULL "as if it were EOF"). */
    static int _PyTokenizer_error_ret(tok_state tok) /* XXX */ {
        tok.decoding_erred = true;
        tok.buf = tok.cur = tok.inp = NULL;
        tok.start = NULL;
        tok.end = NULL;
        tok.done = E_DECODE;
        return NULL;                /* as if it were EOF */
    }

    static int _PyTokenizer_warn_invalid_escape_sequence(tok_state tok,
            int first_invalid_escape_char) {
        if (!tok.report_warnings) {
            return 0;
        }

        String msg = String.format(
            "\"\\%c\" is an invalid escape sequence. "
            + "Such sequences will not work in the future. "
            + "Did you mean \"\\\\%c\"? A raw string is also an option.",
            (char) first_invalid_escape_char,
            (char) first_invalid_escape_char
        );

        if (!warnExplicit(tok, "SyntaxWarning", msg)) {
            /* Replace the SyntaxWarning exception with a SyntaxError
               to get a more accurate error report */
            return _PyTokenizer_syntaxerror(tok,
                "\"\\%c\" is an invalid escape sequence. "
                + "Did you mean \"\\\\%c\"? A raw string is also an option.",
                (char) first_invalid_escape_char,
                (char) first_invalid_escape_char);
        }

        return 0;
    }

    /**
     * C: PyErr_WarnExplicitObject(category, msg, tok->filename, tok->lineno,
     * tok->module, NULL); false where C's returns -1 with the warning raised
     * as an exception.
     */
    private static boolean warnExplicit(tok_state tok, String category, String msg) {
        return tok.warning_handler.warn(
                new Parser.ParserWarning(category, msg, tok.filename, tok.lineno, tok.module));
    }

    /**
     * C: _PyTokenizer_raise_init_error. C converts the pending exception (a
     * LookupError, SyntaxError, ValueError or UnicodeDecodeError) into a
     * SyntaxError; here the failure is the {@link DecodeError} given.
     */
    static PythonSyntaxError _PyTokenizer_raise_init_error(String filename, DecodeError e) {
        // Py_BuildValue("(OiiO)", filename, 0, -1, Py_None)
        PythonSyntaxError error =
                new PythonSyntaxError("SyntaxError", e.getMessage(), 0, -1, null, 0, 0);
        error.noEnd = true;
        return error;
    }

    static int _PyTokenizer_parser_warn(tok_state tok, String category, String format,
            Object... vargs) {
        if (!tok.report_warnings) {
            return 0;
        }

        String errmsg = String.format(format, vargs);

        if (!warnExplicit(tok, category, errmsg)) {
            /* Replace the DeprecationWarning exception with a SyntaxError
               to get a more accurate error report */
            _PyTokenizer_syntaxerror(tok, "%s", errmsg);
            tok.done = E_ERROR;
            return -1;
        }
        return 0;
    }

    /* ############## STRING MANIPULATION ############## */

    /**
     * C: PyUnicode_Decode(str, strlen(str), enc, NULL), then
     * PyUnicode_AsUTF8String: the NUL-terminated bytes at s, decoded from enc
     * and re-encoded as UTF-8 (NUL-terminated).
     */
    static byte[] _PyTokenizer_translate_into_utf8(byte[] a, int s, String enc)
            throws DecodeError {
        int len = 0;
        while (a[s + len] != 0) {
            len++;
        }
        String buf = PyUnicode_Decode(a, s, len, enc);
        byte[] utf8 = buf.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(utf8, utf8.length + 1);
    }

    /**
     * C: translate_newlines. Reads s up to its NUL (or the end of the array)
     * and returns the result NUL-terminated.
     */
    static byte[] _PyTokenizer_translate_newlines(byte[] a, int s, boolean exec_input,
            boolean preserve_crlf) {
        boolean skip_next_lf = false;
        int n = a.length;
        byte[] buf = new byte[n - s + 2];
        int current = 0;
        byte c = '\0';
        for (; s < n && a[s] != 0; s++, current++) {
            c = a[s];
            if (skip_next_lf) {
                skip_next_lf = false;
                if (c == '\n') {
                    c = ++s < n ? a[s] : 0;
                    if (c == 0)
                        break;
                }
            }
            if (!preserve_crlf && c == '\r') {
                skip_next_lf = true;
                c = '\n';
            }
            buf[current] = c;
        }
        /* If this is exec input, add a newline to the end of the string if
           there isn't one already. */
        if (exec_input && c != '\n' && c != '\0') {
            buf[current] = '\n';
            current++;
        }
        buf[current] = '\0';
        return Arrays.copyOf(buf, current + 1);
    }

    /* ############## ENCODING STUFF ############## */

    /*
     * C: _PyTokenizer_check_bom with string_tokenizer.c's buf_getc and
     * buf_ungetc, which read tok->str. See whether the input starts with a
     * BOM; if it does, record utf-8 as the encoding. Return 1 on success, 0
     * on failure.
     */
    static int _PyTokenizer_check_bom(tok_state tok) {
        int ch1, ch2, ch3;
        ch1 = tok.at(tok.str++);
        tok.decoding_state = STATE_SEEK_CODING;
        if (ch1 == 0xEF) {
            ch2 = tok.at(tok.str++);
            if (ch2 != 0xBB) {
                tok.str -= 2;
                return 1;
            }
            ch3 = tok.at(tok.str++);
            if (ch3 != 0xBF) {
                tok.str -= 3;
                return 1;
            }
        } else {
            // C: EOF from buf_getc is never seen here: it returns the NUL.
            tok.str--;
            return 1;
        }
        tok.encoding = "utf-8";
        /* No need to set_readline: input is already utf-8 */
        return 1;
    }

    private static String get_normal_name(String s)  /* for utf-8 and latin-1 */ {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12 && i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_')
                sb.append('-');
            else
                sb.append(Character.toLowerCase(c));
        }
        String buf = sb.toString();
        if (buf.equals("utf-8") ||
            buf.startsWith("utf-8-"))
            return "utf-8";
        else if (buf.equals("latin-1") ||
                 buf.equals("iso-8859-1") ||
                 buf.equals("iso-latin-1") ||
                 buf.startsWith("latin-1-") ||
                 buf.startsWith("iso-8859-1-") ||
                 buf.startsWith("iso-latin-1-"))
            return "iso-8859-1";
        else
            return s;
    }

    private static boolean Py_ISALNUM(int c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    /* Return the coding spec in S, or null if none is found.  */
    private static String get_coding_spec(byte[] a, int s, int size) {
        int i;
        /* Coding spec must be in a comment, and that comment must be
         * the only statement on the source code line. */
        for (i = 0; i < size - 6; i++) {
            if (a[s + i] == '#')
                break;
            if (a[s + i] != ' ' && a[s + i] != '\t' && a[s + i] != '\014')
                return null;
        }
        for (; i < size - 6; i++) { /* XXX inefficient search */
            int t = s + i;
            if (a[t] == 'c' && a[t + 1] == 'o' && a[t + 2] == 'd' && a[t + 3] == 'i'
                    && a[t + 4] == 'n' && a[t + 5] == 'g') {
                int begin;
                t += 6;
                if (a[t] != ':' && a[t] != '=')
                    continue;
                do {
                    t++;
                } while (a[t] == ' ' || a[t] == '\t');

                begin = t;
                while (Py_ISALNUM(a[t]) ||
                       a[t] == '-' || a[t] == '_' || a[t] == '.')
                    t++;

                if (begin < t) {
                    String r = new String(a, begin, t - begin, StandardCharsets.ISO_8859_1);
                    return get_normal_name(r);
                }
            }
        }
        return null;
    }

    /*
     * Check whether the line contains a coding spec. If it does, invoke the
     * set_readline function for the new encoding. This function receives the
     * tok_state and the new encoding. Return 1 on success, 0 on failure. The
     * line is at index line of tok.input.
     */
    static int _PyTokenizer_check_coding_spec(int line, int size, tok_state tok,
            SetReadline set_readline) {
        byte[] a = tok.input;
        String cs;
        if (tok.cont_line) {
            /* It's a continuation line, so it can't be a coding spec. */
            tok.decoding_state = STATE_NORMAL;
            return 1;
        }
        cs = get_coding_spec(a, line, size);
        if (cs == null) {
            int i;
            for (i = 0; i < size; i++) {
                if (a[line + i] == '#' || a[line + i] == '\n' || a[line + i] == '\r')
                    break;
                if (a[line + i] != ' ' && a[line + i] != '\t' && a[line + i] != '\014') {
                    /* Stop checking coding spec after a line containing
                     * anything except a comment. */
                    tok.decoding_state = STATE_NORMAL;
                    break;
                }
            }
            return 1;
        }
        tok.decoding_state = STATE_NORMAL;
        if (tok.encoding == null) {
            if (!cs.equals("utf-8") && !set_readline.set_readline(tok, cs)) {
                _PyTokenizer_error_ret(tok);
                return 0;
            }
            tok.encoding = cs;
        } else {                /* then, compare cs with BOM */
            if (!tok.encoding.equals(cs)) {
                tok.line_start = line;
                tok.cur = line;
                _PyTokenizer_syntaxerror_known_range(tok, 0, size,
                            "encoding problem: %s with BOM", cs);
                _PyTokenizer_error_ret(tok);
                return 0;
            }
        }
        return 1;
    }

    /*
     * Check whether the characters at s start a valid UTF-8 sequence. Return
     * the number of characters forming the sequence if yes, 0 if not. The
     * special cases match those in stringlib/codecs.h:utf8_decode.
     */
    static int valid_utf8(byte[] a, int s) {
        int expected = 0;
        int length;
        int c0 = a[s] & 0xff;
        if (c0 < 0x80) {
            /* single-byte code */
            return 1;
        }
        else if (c0 < 0xE0) {
            /* \xC2\x80-\xDF\xBF -- 0080-07FF */
            if (c0 < 0xC2) {
                /* invalid sequence
                   \x80-\xBF -- continuation byte
                   \xC0-\xC1 -- fake 0000-007F */
                return 0;
            }
            expected = 1;
        }
        else if (c0 < 0xF0) {
            /* \xE0\xA0\x80-\xEF\xBF\xBF -- 0800-FFFF */
            int c1 = a[s + 1] & 0xff;
            if (c0 == 0xE0 && c1 < 0xA0) {
                /* invalid sequence
                   \xE0\x80\x80-\xE0\x9F\xBF -- fake 0000-0800 */
                return 0;
            }
            else if (c0 == 0xED && c1 >= 0xA0) {
                /* Decoding UTF-8 sequences in range \xED\xA0\x80-\xED\xBF\xBF
                   will result in surrogates in range D800-DFFF. Surrogates are
                   not valid UTF-8 so they are rejected.
                   See https://www.unicode.org/versions/Unicode5.2.0/ch03.pdf
                   (table 3-7) and http://www.rfc-editor.org/rfc/rfc3629.txt */
                return 0;
            }
            expected = 2;
        }
        else if (c0 < 0xF5) {
            /* \xF0\x90\x80\x80-\xF4\x8F\xBF\xBF -- 10000-10FFFF */
            int c1 = a[s + 1] & 0xff;
            if (c1 < 0x90 ? c0 == 0xF0 : c0 == 0xF4) {
                /* invalid sequence -- one of:
                   \xF0\x80\x80\x80-\xF0\x8F\xBF\xBF -- fake 0000-FFFF
                   \xF4\x90\x80\x80- -- 110000- overflow */
                return 0;
            }
            expected = 3;
        }
        else {
            /* invalid start byte */
            return 0;
        }
        length = expected + 1;
        for (int i = 1; i <= expected; i++) {
            int ci = a[s + i] & 0xff;
            if (ci < 0x80 || ci >= 0xC0) {
                return 0;
            }
        }
        return length;
    }

    /** Checks the NUL-terminated input at index line of tok.input is UTF-8. */
    static int _PyTokenizer_ensure_utf8(int line, tok_state tok, int lineno) {
        byte[] a = tok.input;
        int badchar = NULL;
        int c;
        int length;
        int col_offset = 0;
        int line_start = line;
        for (c = line; a[c] != 0; c += length) {
            if ((length = valid_utf8(a, c)) == 0) {
                badchar = c;
                break;
            }
            col_offset++;
            if (a[c] == '\n') {
                lineno++;
                col_offset = 0;
                line_start = c + 1;
            }
        }
        if (badchar != NULL) {
            tok.lineno = lineno;
            tok.line_start = line_start;
            tok.cur = badchar;
            _PyTokenizer_syntaxerror_known_range(tok,
                    col_offset + 1, col_offset + 1,
                    "Non-UTF-8 code starting with '\\x%02x'"
                    + "%s%s on line %d, "
                    + "but no encoding declared; "
                    + "see https://peps.python.org/pep-0263/ for details",
                    a[badchar] & 0xff,
                    tok.filename != null ? " in file " : "",
                    tok.filename != null ? tok.filename : "",
                    lineno);
            return 0;
        }
        return 1;
    }

    /* ############## DECODING ############## */

    /**
     * A failed decode: C's pending LookupError or UnicodeDecodeError, whose
     * str() is the message.
     */
    static final class DecodeError extends Exception {
        private static final long serialVersionUID = 1L;

        DecodeError(String message) {
            super(message);
        }
    }

    /**
     * C: PyUnicode_Decode(s, size, encoding, NULL), with Java's charsets
     * standing in for Python's codecs. The messages are those of Python's
     * LookupError and UnicodeDecodeError.
     */
    static String PyUnicode_Decode(byte[] a, int s, int size, String encoding)
            throws DecodeError {
        Charset charset;
        try {
            charset = Charset.forName(encoding);
        } catch (IllegalArgumentException e) {
            throw new DecodeError("unknown encoding: " + encoding);
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(a, s, size);
        CharBuffer out = CharBuffer.allocate(size + 1);
        CoderResult r = decoder.decode(in, out, true);
        if (!r.isError()) {
            r = decoder.flush(out);
        }
        if (r.isError()) {
            // in is at the input that failed.
            int position = in.position() - s;
            throw new DecodeError(decodeErrorMessage(charset, a, s, size, position));
        }
        out.flip();
        return out.toString();
    }

    /** The message of the UnicodeDecodeError Python's codec would raise. */
    private static String decodeErrorMessage(Charset charset, byte[] a, int s, int size,
            int position) {
        int b = a[s + position] & 0xff;
        if (charset.equals(StandardCharsets.UTF_8)) {
            // Objects/unicodeobject.c, the "utf-8" errors of utf8_decode.
            int n = valid_utf8_prefix(a, s + position, size - position);
            String reason;
            int end;
            if (n < 0) {
                reason = "unexpected end of data";
                end = size;
            } else {
                reason = n == 0 ? "invalid start byte" : "invalid continuation byte";
                end = position + Math.max(n, 1);
            }
            if (end - position > 1) {
                return String.format("'utf-8' codec can't decode bytes in position %d-%d: %s",
                        position, end - 1, reason);
            }
            return String.format("'utf-8' codec can't decode byte 0x%02x in position %d: %s",
                    b, position, reason);
        }
        if (charset.equals(StandardCharsets.US_ASCII)) {
            return String.format(
                    "'ascii' codec can't decode byte 0x%02x in position %d: ordinal not in range(128)",
                    b, position);
        }
        return String.format(
                "'charmap' codec can't decode byte 0x%02x in position %d: character maps to <undefined>",
                b, position);
    }

    /**
     * For a UTF-8 sequence that fails at s: how many bytes form its valid
     * prefix (0 for an invalid start byte), or -1 if the input ends inside
     * it.
     */
    private static int valid_utf8_prefix(byte[] a, int s, int remaining) {
        int c0 = a[s] & 0xff;
        int expected;
        if (c0 >= 0xC2 && c0 < 0xE0) {
            expected = 1;
        } else if (c0 >= 0xE0 && c0 < 0xF0) {
            expected = 2;
        } else if (c0 >= 0xF0 && c0 < 0xF5) {
            expected = 3;
        } else {
            return 0;
        }
        for (int i = 1; i <= expected; i++) {
            if (i >= remaining) {
                return -1;
            }
            int ci = a[s + i] & 0xff;
            int lo = 0x80, hi = 0xBF;
            if (i == 1) {
                if (c0 == 0xE0) {
                    lo = 0xA0;
                } else if (c0 == 0xED) {
                    hi = 0x9F;
                } else if (c0 == 0xF0) {
                    lo = 0x90;
                } else if (c0 == 0xF4) {
                    hi = 0x8F;
                }
            }
            if (ci < lo || ci > hi) {
                return i;
            }
        }
        return expected + 1;
    }
}
