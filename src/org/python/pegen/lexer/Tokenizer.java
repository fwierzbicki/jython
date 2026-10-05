package org.python.pegen.lexer;

import static org.python.pegen.lexer.State.NULL;
import static org.python.pegen.lexer.State.TSTRING;

import java.nio.charset.StandardCharsets;

import org.python.pegen.Parser;
import org.python.pegen.PythonSyntaxError;
import org.python.pegen.Token;
import org.python.pegen.TokenSource;
import org.python.pegen.lexer.Helpers.DecodeError;
import org.python.pegen.lexer.State.tok_state;
import org.python.pegen.lexer.State.token;

/**
 * The tokenizer as the parser's {@link TokenSource}: CPython's
 * {@code struct tok_state} behind the parser's view of it. {@link #next()}
 * does the tokenizer side of pegen.c's _PyPegen_fill_token and
 * initialize_token (the token's text from its start and end); the other
 * methods read the fields C's parser reads.
 */
public final class Tokenizer implements TokenSource {

    final tok_state tok;

    private Tokenizer(tok_state tok) {
        this.tok = tok;
    }

    /**
     * C: _PyTokenizer_FromString, as _PyPegen_run_parser_from_string calls it
     * for source bytes: the BOM and coding cookie decide the encoding.
     * execInput is C's exec_input (file input), which adds a newline to an
     * unterminated last line. A failure is thrown as the SyntaxError
     * _PyTokenizer_raise_init_error makes of it.
     */
    public static Tokenizer fromString(byte[] str, boolean execInput, String filename) {
        tok_state tok;
        try {
            tok = StringTokenizer._PyTokenizer_FromString(str, execInput, false);
        } catch (DecodeError e) {
            throw Helpers._PyTokenizer_raise_init_error(filename, e);
        }
        tok.filename = filename;
        return new Tokenizer(tok);
    }

    /**
     * C: _PyTokenizer_FromUTF8, for source that is already text
     * (PyCF_IGNORE_COOKIE): a coding cookie is ignored.
     */
    public static Tokenizer fromUTF8(byte[] str, boolean execInput, String filename) {
        tok_state tok = StringTokenizer._PyTokenizer_FromUTF8(str, execInput, false);
        tok.filename = filename;
        return new Tokenizer(tok);
    }

    /**
     * C: _PyTokenizer_FindEncodingFilename: the encoding a BOM or coding
     * cookie declares for a file with these bytes, or null (UTF-8).
     */
    public static String findEncoding(byte[] data) {
        return StringTokenizer._PyTokenizer_FindEncodingFilename(data);
    }

    /** C: tok->module, for the warnings the tokenizer issues. */
    public void setModule(String module) {
        tok.module = module;
    }

    /** Where the tokenizer's warnings go (C: the warnings module). */
    public void setWarningHandler(Parser.WarningHandler handler) {
        tok.warning_handler = handler;
    }

    /** C: tok->type_comments, set from PyPARSE_TYPE_COMMENTS. */
    public void setTypeComments(boolean type_comments) {
        tok.type_comments = type_comments;
    }

    @Override
    public void setBarryAsBdfl(boolean barry_as_bdfl) {
        tok.barry_as_bdfl = barry_as_bdfl;
    }

    /** C: tok->encoding, the source encoding (from a BOM or cookie), or null. */
    public String encoding() {
        return tok.encoding;
    }

    private String decode(int start, int end) {
        return new String(tok.input, start, end - start, StandardCharsets.UTF_8);
    }

    @Override
    public Token next() {
        token t = new token();
        int type = Lexer._PyTokenizer_Get(tok, t);
        // C: PyBytes_FromStringAndSize(new_token->start, new_token->end - new_token->start)
        String string = t.start == NULL ? "" : decode(t.start, t.end);
        Token result = new Token(type, string, t.lineno, t.col_offset, t.end_lineno,
                t.end_col_offset);
        result.level = t.level;
        result.metadata = t.metadata;
        return result;
    }

    @Override
    public void implyDedents() {
        if (tok.indent != 0) {
            tok.pendin = -tok.indent;
            tok.indent = 0;
        }
    }

    @Override
    public boolean insideFstring() {
        return State.INSIDE_FSTRING(tok);
    }

    @Override
    public boolean insideTstring() {
        return Lexer.TOK_GET_MODE(tok).string_kind == TSTRING;
    }

    @Override
    public boolean fstringRaw() {
        return Lexer.TOK_GET_MODE(tok).raw;
    }

    @Override
    public int done() {
        return tok.done;
    }

    @Override
    public PythonSyntaxError error() {
        return tok.error;
    }

    @Override
    public int lineno() {
        return tok.lineno;
    }

    @Override
    public int cursorColumn() {
        return tok.cur - tok.line_start;
    }

    @Override
    public int bufferOffset() {
        return tok.cur - tok.buf;
    }

    @Override
    public String currentLine() {
        // C (_PyPegen_raise_error_known_location): p->tok->inp > p->tok->buf
        if (tok.inp == NULL || tok.inp <= tok.buf) {
            return null;
        }
        return decode(tok.line_start, tok.inp);
    }

    /** C: get_error_line_from_tokenizer_buffers (pegen_errors.c), for string input. */
    @Override
    public String getLine(int lineno) {
        byte[] a = tok.input;
        int cur_line = tok.str;
        int buf_end = tok.inp;
        int len = a.length - 1;
        if (buf_end < cur_line) {
            buf_end = len;
        }

        for (int i = 0; i < lineno - 1; i++) {
            int new_line = indexOfNewline(cur_line);
            if (new_line == NULL || new_line + 1 > buf_end) {
                break;
            }
            cur_line = new_line + 1;
        }

        int next_newline = indexOfNewline(cur_line);
        if (next_newline == NULL) { // This is the last line
            next_newline = len;
        }
        return decode(cur_line, next_newline);
    }

    /** C: strchr(p, '\n'). */
    private int indexOfNewline(int p) {
        byte[] a = tok.input;
        for (; a[p] != 0; p++) {
            if (a[p] == '\n') {
                return p;
            }
        }
        return NULL;
    }

    @Override
    public String rest() {
        if (tok.cur == NULL) {
            return null;
        }
        return decode(tok.cur, tok.input.length - 1);
    }

    /**
     * C (_PyPegen_set_syntax_error_metadata): tok->str decoded with
     * tok->encoding, which for a cookie other than UTF-8 decodes the UTF-8
     * buffer as that encoding, as C does; null where that fails.
     */
    @Override
    public String source() {
        if (tok.str == NULL) {
            return null;
        }
        int len = tok.input.length - 1 - tok.str;
        if (tok.encoding == null) {
            return decode(tok.str, tok.input.length - 1);
        }
        try {
            return Helpers.PyUnicode_Decode(tok.input, tok.str, len, tok.encoding);
        } catch (DecodeError e) {
            return null;
        }
    }

    @Override
    public int level() {
        return tok.level;
    }

    @Override
    public char parenstack(int i) {
        return tok.parenstack[i];
    }

    @Override
    public int parenlinenostack(int i) {
        return tok.parenlinenostack[i];
    }

    @Override
    public int parencolstack(int i) {
        return tok.parencolstack[i];
    }
}
