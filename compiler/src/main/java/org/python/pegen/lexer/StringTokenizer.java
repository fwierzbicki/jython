package org.python.pegen.lexer;

import static org.python.pegen.ActionHelpers.E_EOF;
import static org.python.pegen.lexer.Helpers.ADVANCE_LINENO;
import static org.python.pegen.lexer.Helpers._PyTokenizer_check_bom;
import static org.python.pegen.lexer.Helpers._PyTokenizer_check_coding_spec;
import static org.python.pegen.lexer.Helpers._PyTokenizer_ensure_utf8;
import static org.python.pegen.lexer.Helpers._PyTokenizer_error_ret;
import static org.python.pegen.lexer.Helpers._PyTokenizer_translate_into_utf8;
import static org.python.pegen.lexer.Helpers._PyTokenizer_translate_newlines;
import static org.python.pegen.lexer.State.NULL;
import static org.python.pegen.lexer.State.STATE_NORMAL;
import static org.python.pegen.lexer.State._PyTokenizer_tok_new;

import java.nio.charset.Charset;

import org.python.pegen.lexer.Helpers.DecodeError;
import org.python.pegen.lexer.State.tok_state;

/**
 * A port of Parser/tokenizer/string_tokenizer.c and utf8_tokenizer.c: the
 * tokenizers over a string held in memory, the only ones ported (no file,
 * readline or interactive input).
 *
 * <p>Where C returns NULL with an exception set, these throw it: a
 * {@link DecodeError} for C's LookupError or UnicodeDecodeError, or the
 * tokenizer's SyntaxError (from tok.error).
 */
final class StringTokenizer {

    private StringTokenizer() {}

    /** C: strchr(p, ch) in the input: the index of ch, or NULL at the NUL. */
    private static int strchr(tok_state tok, int p, int ch) {
        byte[] a = tok.input;
        for (; a[p] != 0; p++) {
            if (a[p] == ch) {
                return p;
            }
        }
        return ch == 0 ? p : NULL;
    }

    /* Both string_tokenizer.c's and utf8_tokenizer.c's (they are the same). */
    static boolean tok_underflow_string(tok_state tok) {
        int end = strchr(tok, tok.inp, '\n');
        if (end != NULL) {
            end++;
        }
        else {
            end = strchr(tok, tok.inp, '\0');
            if (end == tok.inp) {
                tok.done = E_EOF;
                return false;
            }
        }
        if (tok.start == NULL) {
            tok.buf = tok.cur;
        }
        tok.line_start = tok.cur;
        ADVANCE_LINENO(tok);
        tok.inp = end;
        return true;
    }

    /* Set the readline function for TOK to ENC. For the string-based
       tokenizer, this means to just record the encoding. */
    private static boolean buf_setreadl(tok_state tok, String enc) {
        tok.enc = enc;
        return true;
    }

    /*
     * Decode a byte string STR for use as the buffer of TOK. Look for
     * encoding declarations inside STR, and record them inside TOK. Returns
     * the index of the decoded string in tok.input, or NULL with the
     * SyntaxError in tok.error.
     */
    private static int decode_str(byte[] input, boolean single, tok_state tok,
            boolean preserve_crlf) throws DecodeError {
        byte[] utf8 = null;
        int str;
        int s;
        int[] newl = {NULL, NULL};
        int lineno = 0;
        tok.input = _PyTokenizer_translate_newlines(input, 0, single, preserve_crlf);
        str = 0;
        tok.enc = null;
        tok.str = str;
        if (_PyTokenizer_check_bom(tok) == 0)
            return _PyTokenizer_error_ret(tok);
        str = tok.str;             /* string after BOM if any */
        // C: if (tok->enc != NULL) { utf8 = _PyTokenizer_translate_into_utf8(...) }:
        // _PyTokenizer_check_bom only sets tok->encoding, so enc is still NULL.
        for (s = str;; s++) {
            if (tok.input[s] == '\0') break;
            else if (tok.input[s] == '\n') {
                assert lineno < 2;
                newl[lineno] = s;
                lineno++;
                if (lineno == 2) break;
            }
        }
        tok.enc = null;
        /* need to check line 1 and 2 separately since check_coding_spec
           assumes a single line as input */
        if (newl[0] != NULL) {
            tok.lineno = 1;
            if (_PyTokenizer_check_coding_spec(str, newl[0] - str, tok,
                    StringTokenizer::buf_setreadl) == 0) {
                return NULL;
            }
            if (tok.enc == null && tok.decoding_state != STATE_NORMAL && newl[1] != NULL) {
                tok.lineno = 2;
                if (_PyTokenizer_check_coding_spec(newl[0]+1, newl[1] - newl[0],
                                       tok, StringTokenizer::buf_setreadl) == 0)
                    return NULL;
            }
        }
        tok.lineno = 0;
        if (tok.enc != null) {
            try {
                utf8 = _PyTokenizer_translate_into_utf8(tok.input, str, tok.enc);
            } catch (DecodeError e) {
                _PyTokenizer_error_ret(tok);
                throw e;
            }
        }
        else if (_PyTokenizer_ensure_utf8(str, tok, 1) == 0) {
            return _PyTokenizer_error_ret(tok);
        }
        if (utf8 != null) {
            tok.input = _PyTokenizer_translate_newlines(utf8, 0, single, preserve_crlf);
            str = 0;
        }
        tok.str = str;
        return str;
    }

    /* Set up tokenizer for string */
    static tok_state _PyTokenizer_FromString(byte[] str, boolean exec_input,
            boolean preserve_crlf) throws DecodeError {
        tok_state tok = _PyTokenizer_tok_new();
        int decoded;

        decoded = decode_str(str, exec_input, tok, preserve_crlf);
        if (decoded == NULL) {
            throw tok.error;
        }

        tok.buf = tok.cur = tok.inp = decoded;
        tok.end = decoded;
        tok.underflow = StringTokenizer::tok_underflow_string;
        return tok;
    }

    /**
     * C: _PyTokenizer_FindEncodingFilename (file_tokenizer.c), over the
     * file's bytes: the encoding a BOM or a coding cookie in the first two
     * lines declares, or null (meaning UTF-8). C runs the file tokenizer
     * until it has read two lines, which checks the BOM and the cookie as
     * decode_str does; a cookie naming an encoding it can't open
     * (fp_setreadl) gives null.
     */
    static String _PyTokenizer_FindEncodingFilename(byte[] data) {
        tok_state tok = _PyTokenizer_tok_new();
        tok.input = _PyTokenizer_translate_newlines(data, 0, false, false);
        tok.str = 0;
        _PyTokenizer_check_bom(tok);
        int str = tok.str;
        int[] newl = {NULL, NULL};
        int lineno = 0;
        for (int s = str; tok.input[s] != '\0' && lineno < 2; s++) {
            if (tok.input[s] == '\n') {
                newl[lineno++] = s;
            }
        }
        // fp_setreadl: the file is reopened with the encoding, which must exist.
        Helpers.SetReadline fp_setreadl = (t, enc) -> {
            try {
                return Charset.isSupported(enc);
            } catch (IllegalArgumentException e) {
                return false;
            }
        };
        // The file tokenizer checks each line it reads, including an
        // unterminated last one.
        int end1 = newl[0] != NULL ? newl[0] + 1 : tok.strlen(str) + str;
        if (end1 > str) {
            tok.lineno = 1;
            if (_PyTokenizer_check_coding_spec(str, end1 - str, tok, fp_setreadl) == 0) {
                return null;
            }
            if (tok.decoding_state != STATE_NORMAL && newl[0] != NULL) {
                int start2 = newl[0] + 1;
                int end2 = newl[1] != NULL ? newl[1] + 1 : tok.strlen(start2) + start2;
                if (end2 > start2) {
                    tok.lineno = 2;
                    if (_PyTokenizer_check_coding_spec(start2, end2 - start2, tok,
                            fp_setreadl) == 0) {
                        return null;
                    }
                }
            }
        }
        return tok.encoding;
    }

    /* Set up tokenizer for UTF-8 string */
    static tok_state _PyTokenizer_FromUTF8(byte[] str, boolean exec_input,
            boolean preserve_crlf) {
        tok_state tok = _PyTokenizer_tok_new();
        tok.input = _PyTokenizer_translate_newlines(str, 0, exec_input, preserve_crlf);
        int translated = 0;
        tok.decoding_state = STATE_NORMAL;
        tok.enc = null;
        tok.str = translated;
        tok.encoding = "utf-8";

        tok.buf = tok.cur = tok.inp = translated;
        tok.end = translated;
        tok.underflow = StringTokenizer::tok_underflow_string;
        return tok;
    }
}
