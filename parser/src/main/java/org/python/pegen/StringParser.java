package org.python.pegen;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.python.pegen.ast.Bytes;

import static org.python.pegen.ActionHelpers.*;
import static org.python.pegen.TokenTypes.*;

/**
 * String literal decoding: a port of CPython's Parser/string_parser.c, with
 * the two decoders it calls, _PyUnicode_DecodeUnicodeEscapeInternal2
 * (Objects/unicodeobject.c) and _PyBytes_DecodeEscape2 (Objects/bytesobject.c).
 *
 * <p>C works on the literal's UTF-8 bytes, and the positions in its error
 * messages and warnings are offsets into them, so this port does too: a C
 * pointer into a buffer becomes an index into a byte[]. A NULL pointer out
 * parameter is -1.
 *
 * <p>A str decodes to a String and a bytes to an ast.Bytes. On failure these
 * return null with the exception pending in the Parser, as C does.
 */
public final class StringParser {

    private StringParser() {}

    //// STRING HANDLING FUNCTIONS ////

    /**
     * Issues the warning for an invalid escape sequence; the buffer starts at
     * s[buffer] and the escape's character is at s[first_invalid_escape].
     */
    private static int warn_invalid_escape_sequence(Parser p, byte[] s, int buffer,
            int first_invalid_escape, Token t) {
        if (p.call_invalid_rules) {
            // Do not report warnings if we are in the second pass of the parser
            // to avoid showing the warning twice.
            return 0;
        }
        int c = s[first_invalid_escape] & 0xff;
        if ((t.type == FSTRING_MIDDLE || t.type == FSTRING_END || t.type == TSTRING_MIDDLE
                || t.type == TSTRING_END) && (c == '{' || c == '}')) {
            // in this case the tokenizer has already emitted a warning,
            // see Parser/tokenizer/helpers.c:warn_invalid_escape_sequence
            return 0;
        }

        // C: %.3s of first_invalid_escape
        String escape = new String(s, first_invalid_escape,
                Math.min(3, s.length - first_invalid_escape), StandardCharsets.ISO_8859_1);
        boolean octal = ('4' <= c && c <= '7');
        String msg =
            octal
            ? formatMessage(
                  "\"\\%.3s\" is an invalid octal escape sequence. "
                  + "Such sequences will not work in the future. "
                  + "Did you mean \"\\\\%.3s\"? A raw string is also an option.",
                  escape, escape)
            : formatMessage(
                  "\"\\%c\" is an invalid escape sequence. "
                  + "Such sequences will not work in the future. "
                  + "Did you mean \"\\\\%c\"? A raw string is also an option.",
                  c, c);
        String category;
        if (p.feature_version >= 12) {
            category = PyExc_SyntaxWarning;
        } else {
            category = PyExc_DeprecationWarning;
        }

        // Calculate the lineno and the col_offset of the invalid escape sequence
        int start = buffer;
        int end = first_invalid_escape;
        int lineno = t.lineno;
        int col_offset = t.col_offset;
        while (start < end) {
            if (s[start] == '\n') {
                lineno++;
                col_offset = 0;
            } else {
                col_offset++;
            }
            start++;
        }

        // Count the number of quotes in the token
        byte first_quote = 0;
        if (lineno == t.lineno) {
            int quote_count = 0;
            byte[] tok = t.string.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < tok.length; i++) {
                if (tok[i] == '\'' || tok[i] == '\"') {
                    if (quote_count == 0) {
                        first_quote = tok[i];
                    }
                    if (tok[i] == first_quote) {
                        quote_count++;
                    }
                } else {
                    break;
                }
            }

            col_offset += quote_count;
        }

        if (p.warnExplicit(category, msg, lineno) < 0) {
            if (p.errorMatches(category)) {
                /* Replace the Syntax/DeprecationWarning exception with a SyntaxError
                   to get a more accurate error report */
                p.clearError();

                /* This is needed, in order for the SyntaxError to point to the token t,
                   since _PyPegen_raise_error uses p->tokens[p->fill - 1] for the
                   error location, if p->known_err_token is not set. */
                p.known_err_token = t;
                if (octal) {
                    RAISE_ERROR_KNOWN_LOCATION(p, PyExc_SyntaxError, lineno, col_offset - 1,
                        lineno, col_offset + 1,
                        "\"\\%.3s\" is an invalid octal escape sequence. "
                        + "Did you mean \"\\\\%.3s\"? A raw string is also an option.",
                        escape, escape);
                } else {
                    RAISE_ERROR_KNOWN_LOCATION(p, PyExc_SyntaxError, lineno, col_offset - 1,
                        lineno, col_offset + 1,
                        "\"\\%c\" is an invalid escape sequence. "
                        + "Did you mean \"\\\\%c\"? A raw string is also an option.",
                        c, c);
                }
            }
            return -1;
        }
        return 0;
    }

    /** Decodes the run of non-ASCII bytes at s[sPtr[0]], advancing sPtr[0] past it. */
    private static String decode_utf8(byte[] s, int[] sPtr, int end) {
        int t = sPtr[0];
        int i = t;
        while (i < end && (s[i] & 0x80) != 0) {
            i++;
        }
        sPtr[0] = i;
        return new String(s, t, i - t, StandardCharsets.UTF_8);
    }

    /** Copies the ASCII text into buf at p, returning the index after it. */
    private static int put(byte[] buf, int p, String text) {
        for (int i = 0; i < text.length(); i++) {
            buf[p++] = (byte) text.charAt(i);
        }
        return p;
    }

    private static String decode_unicode_with_escapes(Parser parser, byte[] src, int s, int len,
            Token t) {
        /* check for integer overflow */
        if (len > Integer.MAX_VALUE / 6) {
            return null;
        }
        /* "ä" (2 bytes) may become "\U000000E4" (10 bytes), or 1:5
           "\ä" (3 bytes) may become "\u005c\U000000E4" (16 bytes), or ~1:6 */
        byte[] buf = new byte[len * 6];
        int p = 0;
        int end = s + len;
        while (s < end) {
            if (src[s] == '\\') {
                buf[p++] = src[s++];
                if (s >= end || (src[s] & 0x80) != 0) {
                    p = put(buf, p, "u005c");
                    if (s >= end) {
                        break;
                    }
                }
            }
            if ((src[s] & 0x80) != 0) {
                int[] sPtr = {s};
                String w = decode_utf8(src, sPtr, end);
                s = sPtr[0];
                for (int i = 0; i < w.length(); ) {
                    int chr = w.codePointAt(i);
                    p = put(buf, p, String.format("\\U%08x", chr));
                    i += Character.charCount(chr);
                }
            } else {
                buf[p++] = src[s++];
            }
        }
        // C passes (buf, p - buf) on; the copy is that length, so that the
        // warning's %.3s can't read past it.
        buf = Arrays.copyOf(buf, p);

        int[] first_invalid_escape_char = new int[1];
        int[] first_invalid_escape_ptr = new int[1];
        String v = _PyUnicode_DecodeUnicodeEscapeInternal2(parser, buf, 0, buf.length,
                first_invalid_escape_char, first_invalid_escape_ptr);

        // HACK: later we can simply pass the line no, since we don't preserve the tokens
        // when we are decoding the string but we preserve the line numbers.
        if (v != null && first_invalid_escape_ptr[0] != -1 && t != null) {
            if (warn_invalid_escape_sequence(parser, buf, 0, first_invalid_escape_ptr[0], t) < 0) {
                return null;
            }
        }
        return v;
    }

    private static Bytes decode_bytes_with_escapes(Parser p, byte[] src, int s, int len, Token t) {
        int[] first_invalid_escape_char = new int[1];
        int[] first_invalid_escape_ptr = new int[1];
        Bytes result = _PyBytes_DecodeEscape2(p, src, s, len, first_invalid_escape_char,
                first_invalid_escape_ptr);
        if (result == null) {
            return null;
        }

        if (first_invalid_escape_ptr[0] != -1) {
            if (warn_invalid_escape_sequence(p, src, s, first_invalid_escape_ptr[0], t) < 0) {
                return null;
            }
        }
        return result;
    }

    /** Decodes len bytes of s from start, the contents of a str literal (or part of one). */
    public static String _PyPegen_decode_string(Parser p, int raw, byte[] s, int start, int len,
            Token t) {
        if (raw != 0) {
            // C: PyUnicode_DecodeUTF8Stateful. The bytes came from a String,
            // so they are valid UTF-8.
            return new String(s, start, len, StandardCharsets.UTF_8);
        }
        return decode_unicode_with_escapes(p, s, start, len, t);
    }

    /**
     * t.string must include the bracketing quote characters, and r, b &amp;/or
     * u prefixes (if any), and embedded escape sequences (if any). (f-strings
     * are handled by the parser) _PyPegen_parse_string parses it, and returns
     * the decoded value: a String, or for a bytes literal a Bytes.
     */
    public static Object _PyPegen_parse_string(Parser p, Token t) {
        byte[] buf = t.string.getBytes(StandardCharsets.UTF_8);
        int s = 0;

        int len;
        int quote = buf[s] & 0xff;
        boolean bytesmode = false;
        boolean rawmode = false;

        if (Py_ISALPHA(quote)) {
            while (!bytesmode || !rawmode) {
                if (quote == 'b' || quote == 'B') {
                    quote = buf[++s] & 0xff;
                    bytesmode = true;
                } else if (quote == 'u' || quote == 'U') {
                    quote = buf[++s] & 0xff;
                } else if (quote == 'r' || quote == 'R') {
                    quote = buf[++s] & 0xff;
                    rawmode = true;
                } else {
                    break;
                }
            }
        }

        if (quote != '\'' && quote != '\"') {
            PyErr_BadInternalCall(p);
            return null;
        }

        /* Skip the leading quote char. */
        s++;
        len = buf.length - s;
        // gh-120155: 's' contains at least the trailing quote,
        // so the code '--len' below is safe.
        assert len >= 1;

        // (C checks len > INT_MAX here; a Java array can't be that long.)
        if (buf[s + --len] != quote) {
            /* Last quote char must match the first. */
            PyErr_BadInternalCall(p);
            return null;
        }
        if (len >= 4 && buf[s] == quote && buf[s + 1] == quote) {
            /* A triple quoted string. We've already skipped one quote at
               the start and one at the end of the string. Now skip the
               two at the start. */
            s += 2;
            len -= 2;
            /* And check that the last two match. */
            if (buf[s + --len] != quote || buf[s + --len] != quote) {
                PyErr_BadInternalCall(p);
                return null;
            }
        }

        /* Avoid invoking escape decoding routines if possible. */
        rawmode = rawmode || strchr(buf, s, '\\') == -1;
        if (bytesmode) {
            /* Disallow non-ASCII characters. */
            for (int ch = s; ch < buf.length; ch++) {
                if ((buf[ch] & 0xff) >= 0x80) {
                    RAISE_SYNTAX_ERROR_KNOWN_LOCATION(p,
                                       t,
                                       "bytes can only contain ASCII "
                                       + "literal characters");
                    return null;
                }
            }
            if (rawmode) {
                return new Bytes(Arrays.copyOfRange(buf, s, s + len));
            }
            return decode_bytes_with_escapes(p, buf, s, len, t);
        }
        return _PyPegen_decode_string(p, rawmode ? 1 : 0, buf, s, len, t);
    }

    //// Helpers standing in for C library and CPython API calls ////

    /** Py_ISALPHA: an ASCII letter. */
    private static boolean Py_ISALPHA(int c) {
        return ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z');
    }

    /** strchr(s + start, c): the index of c from start to the end of s, or -1. */
    private static int strchr(byte[] s, int start, int c) {
        for (int i = start; i < s.length; i++) {
            if (s[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /** PyErr_BadInternalCall */
    private static void PyErr_BadInternalCall(Parser p) {
        p.setError("SystemError", "bad argument to internal function");
    }

    /** The largest code point (C: MAX_UNICODE). */
    private static final int MAX_UNICODE = 0x10ffff;

    /**
     * _PyUnicode_DecodeUnicodeEscapeInternal2 with errors and consumed NULL:
     * decodes the unicode-escape encoded bytes s[start:start+size]. The first
     * error raises UnicodeDecodeError ("strict"), so C's error handler
     * machinery is reduced to raise_unicode_decode_error.
     */
    private static String _PyUnicode_DecodeUnicodeEscapeInternal2(Parser p, byte[] src, int s,
            int size, int[] first_invalid_escape_char, int[] first_invalid_escape_ptr) {
        int starts = s;
        int end;

        // so we can remember if we've seen an invalid escape char or not
        first_invalid_escape_char[0] = -1;
        first_invalid_escape_ptr[0] = -1;

        if (size == 0) {
            return "";
        }
        StringBuilder writer = new StringBuilder(size);

        end = s + size;
        while (s < end) {
            int c = src[s++] & 0xff;
            int ch;
            int count;
            String message;

            /* Non-escape characters are interpreted as Unicode ordinals */
            if (c != '\\') {
                writer.appendCodePoint(c);
                continue;
            }

            int startinpos = s - starts - 1;
            /* \ - Escapes */
            if (s >= end) {
                message = "\\ at end of string";
                // goto incomplete (and, with consumed NULL, on to error)
                return raise_unicode_decode_error(p, src, starts, end, startinpos, s - starts,
                        message);
            }
            c = src[s++] & 0xff;

            switch (c) {

                /* \x escapes */
            case '\n': continue;
            case '\\': writer.append('\\'); continue;
            case '\'': writer.append('\''); continue;
            case '\"': writer.append('\"'); continue;
            case 'b': writer.append('\b'); continue;
            /* FF */
            case 'f': writer.append('\014'); continue;
            case 't': writer.append('\t'); continue;
            case 'n': writer.append('\n'); continue;
            case 'r': writer.append('\r'); continue;
            /* VT */
            case 'v': writer.append('\013'); continue;
            /* BEL, not classic C */
            case 'a': writer.append('\007'); continue;

                /* \OOO (octal) escapes */
            case '0': case '1': case '2': case '3':
            case '4': case '5': case '6': case '7':
                ch = c - '0';
                if (s < end && '0' <= src[s] && src[s] <= '7') {
                    ch = (ch << 3) + src[s++] - '0';
                    if (s < end && '0' <= src[s] && src[s] <= '7') {
                        ch = (ch << 3) + src[s++] - '0';
                    }
                }
                if (ch > 0377) {
                    if (first_invalid_escape_char[0] == -1) {
                        first_invalid_escape_char[0] = ch;
                        /* Back up 3 chars, since we've already incremented s. */
                        first_invalid_escape_ptr[0] = s - 3;
                    }
                }
                writer.appendCodePoint(ch);
                continue;

                /* hex escapes */
                /* \xXX */
            case 'x':
                count = 2;
                message = "truncated \\xXX escape";
                break;

                /* \\uXXXX */
            case 'u':
                count = 4;
                message = "truncated \\uXXXX escape";
                break;

                /* \UXXXXXXXX */
            case 'U':
                count = 8;
                message = "truncated \\UXXXXXXXX escape";
                break;

                /* \N{name} */
            case 'N':
                if (!UnicodeNames.available()) {
                    p.setError(PyExc_UnicodeError,
                            "\\N escapes not supported (can't load unicodedata module)");
                    return null;
                }

                message = "malformed \\N character escape";
                if (s < end && src[s] == '{') {
                    int start = ++s;
                    int namelen;
                    /* look for the closing brace */
                    while (s < end && src[s] != '}') {
                        s++;
                    }
                    if (s < end) {
                        namelen = s - start;
                        if (namelen != 0) {
                            /* found a name.  look it up in the unicode database */
                            s++;
                            ch = UnicodeNames.getcode(
                                    new String(src, start, namelen, StandardCharsets.ISO_8859_1));
                            if (ch >= 0) {
                                writer.appendCodePoint(ch);
                                continue;
                            }
                            message = "unknown Unicode character name";
                        }
                    }
                }
                // goto incomplete / goto error: the same, with consumed NULL
                return raise_unicode_decode_error(p, src, starts, end, startinpos, s - starts,
                        message);

            default:
                if (first_invalid_escape_char[0] == -1) {
                    first_invalid_escape_char[0] = c;
                    /* Back up one char, since we've already incremented s. */
                    first_invalid_escape_ptr[0] = s - 1;
                }
                writer.append('\\');
                writer.appendCodePoint(c);
                continue;
            }

            // hexescape:
            for (ch = 0; count != 0; ++s, --count) {
                if (s >= end) {
                    // goto incomplete
                    return raise_unicode_decode_error(p, src, starts, end, startinpos,
                            s - starts, message);
                }
                c = src[s] & 0xff;
                ch <<= 4;
                if (c >= '0' && c <= '9') {
                    ch += c - '0';
                } else if (c >= 'a' && c <= 'f') {
                    ch += c - ('a' - 10);
                } else if (c >= 'A' && c <= 'F') {
                    ch += c - ('A' - 10);
                } else {
                    // goto error
                    return raise_unicode_decode_error(p, src, starts, end, startinpos,
                            s - starts, message);
                }
            }

            /* when we get here, ch is a 32-bit unicode character */
            // (C's Py_UCS4 is unsigned.)
            if (Integer.compareUnsigned(ch, MAX_UNICODE) > 0) {
                message = "illegal Unicode character";
                return raise_unicode_decode_error(p, src, starts, end, startinpos, s - starts,
                        message);
            }

            writer.appendCodePoint(ch);
        }

        return writer.toString();
    }

    /**
     * unicode_decode_call_errorhandler_writer with the "strict" handler:
     * raises UnicodeDecodeError("unicodeescape", src[starts:end], startinpos,
     * endinpos, reason), whose message is formatted as UnicodeDecodeError_str
     * does. Returns null.
     */
    private static String raise_unicode_decode_error(Parser p, byte[] src, int starts, int end,
            int startinpos, int endinpos, String reason) {
        int len = end - starts;
        String msg;
        if ((startinpos >= 0 && startinpos < len) && (endinpos >= 0 && endinpos <= len)
                && endinpos == startinpos + 1) {
            int badbyte = src[starts + startinpos] & 0xff;
            msg = String.format("'%s' codec can't decode byte 0x%02x in position %d: %s",
                    "unicodeescape", badbyte, startinpos, reason);
        } else {
            msg = String.format("'%s' codec can't decode bytes in position %d-%d: %s",
                    "unicodeescape", startinpos, endinpos - 1, reason);
        }
        p.setError("UnicodeDecodeError", msg);
        return null;
    }

    /** _PyLong_DigitValue for a hex digit; 37 for anything else. */
    private static int digitValue(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else if (c >= 'a' && c <= 'z') {
            return c - 'a' + 10;
        } else if (c >= 'A' && c <= 'Z') {
            return c - 'A' + 10;
        }
        return 37;
    }

    /**
     * _PyBytes_DecodeEscape2 with errors NULL ("strict"): unescapes the
     * backslash-escaped bytes s[start:start+len].
     */
    private static Bytes _PyBytes_DecodeEscape2(Parser p, byte[] src, int s, int len,
            int[] first_invalid_escape_char, int[] first_invalid_escape_ptr) {
        byte[] writer = new byte[len];
        int o = 0;

        first_invalid_escape_char[0] = -1;
        first_invalid_escape_ptr[0] = -1;

        int end = s + len;
        while (s < end) {
            if (src[s] != '\\') {
                writer[o++] = src[s++];
                continue;
            }

            s++;
            if (s == end) {
                p.setError(PyExc_ValueError, "Trailing \\ in string");
                return null;
            }

            switch (src[s++]) {
            /* XXX This assumes ASCII! */
            case '\n': break;
            case '\\': writer[o++] = '\\'; break;
            case '\'': writer[o++] = '\''; break;
            case '\"': writer[o++] = '\"'; break;
            case 'b': writer[o++] = '\b'; break;
            case 'f': writer[o++] = '\014'; break; /* FF */
            case 't': writer[o++] = '\t'; break;
            case 'n': writer[o++] = '\n'; break;
            case 'r': writer[o++] = '\r'; break;
            case 'v': writer[o++] = '\013'; break; /* VT */
            case 'a': writer[o++] = '\007'; break; /* BEL, not classic C */
            case '0': case '1': case '2': case '3':
            case '4': case '5': case '6': case '7':
            {
                int c = src[s - 1] - '0';
                if (s < end && '0' <= src[s] && src[s] <= '7') {
                    c = (c << 3) + src[s++] - '0';
                    if (s < end && '0' <= src[s] && src[s] <= '7') {
                        c = (c << 3) + src[s++] - '0';
                    }
                }
                if (c > 0377) {
                    if (first_invalid_escape_char[0] == -1) {
                        first_invalid_escape_char[0] = c;
                        /* Back up 3 chars, since we've already incremented s. */
                        first_invalid_escape_ptr[0] = s - 3;
                    }
                }
                writer[o++] = (byte) c;
                break;
            }
            case 'x':
                if (s + 1 < end) {
                    int digit1, digit2;
                    digit1 = digitValue(src[s] & 0xff);
                    digit2 = digitValue(src[s + 1] & 0xff);
                    if (digit1 < 16 && digit2 < 16) {
                        writer[o++] = (byte) ((digit1 << 4) + digit2);
                        s += 2;
                        break;
                    }
                }
                /* invalid hexadecimal digits */

                p.setError(PyExc_ValueError,
                        "invalid \\x escape at position " + (s - 2 - (end - len)));
                return null;

            default:
                if (first_invalid_escape_char[0] == -1) {
                    first_invalid_escape_char[0] = src[s - 1] & 0xff;
                    /* Back up one char, since we've already incremented s. */
                    first_invalid_escape_ptr[0] = s - 1;
                }
                writer[o++] = '\\';
                s--;
            }
        }

        return new Bytes(Arrays.copyOf(writer, o));
    }
}
