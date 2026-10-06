package org.python.pegen.lexer;

import static org.python.pegen.ActionHelpers.E_OK;
import static org.python.pegen.TokenTypes.FSTRING_END;
import static org.python.pegen.TokenTypes.FSTRING_MIDDLE;
import static org.python.pegen.TokenTypes.STRING;
import static org.python.pegen.TokenTypes.TSTRING_END;
import static org.python.pegen.TokenTypes.TSTRING_MIDDLE;

import org.python.pegen.Parser;
import org.python.pegen.PythonSyntaxError;

/**
 * The tokenizer's state: a port of Parser/lexer/state.h and state.c.
 *
 * <p>C's pointers into the input are int indices into {@link tok_state#buf}'s
 * array, {@code input}, which holds the whole (newline-translated, UTF-8)
 * source followed by a NUL, as C's string tokenizers do; -1 stands for NULL.
 * C's {@code tok->buf}, {@code tok->str} and {@code tok->input} all point into
 * that one array.
 */
public final class State {

    private State() {}

    public static final int MAXINDENT = 100;       /* Max indentation level */
    public static final int MAXLEVEL = 200;        /* Max parentheses level */
    public static final int MAXFSTRINGLEVEL = 150; /* Max f-string nesting level */

    /* Never change this */
    static final int TABSIZE = 8;

    static final int MAX_EXPR_NESTING = 3;

    /** C: EOF, as tok_nextc returns it. */
    static final int EOF = -1;

    /** C: a NULL pointer into the input. */
    static final int NULL = -1;

    static boolean INSIDE_FSTRING(tok_state tok) {
        return tok.tok_mode_stack_index > 0;
    }

    static boolean INSIDE_FSTRING_EXPR(tokenizer_mode tok) {
        return tok.curly_bracket_expr_start_depth >= 0;
    }

    static boolean INSIDE_FSTRING_EXPR_AT_TOP(tokenizer_mode tok) {
        return tok.curly_bracket_depth - tok.curly_bracket_expr_start_depth == 1;
    }

    // enum decoding_state
    static final int STATE_INIT = 0;
    static final int STATE_SEEK_CODING = 1;
    static final int STATE_NORMAL = 2;

    // enum tokenizer_mode_kind_t
    static final int TOK_REGULAR_MODE = 0;
    static final int TOK_FSTRING_MODE = 1;

    // enum string_kind_t
    static final int FSTRING = 0;
    static final int TSTRING = 1;

    /** C: struct token. start and end are indices into the input, or NULL. */
    public static final class token {
        public int level;
        public int lineno, col_offset, end_lineno, end_col_offset;
        public int start = NULL, end = NULL;
        public String metadata;
    }

    /** C: tokenizer_mode. */
    static final class tokenizer_mode {
        int kind;

        int curly_bracket_depth;
        int curly_bracket_expr_start_depth;

        char quote;
        int quote_size;
        boolean raw;
        int start = NULL;
        int multi_line_start = NULL;
        int first_line;

        int start_offset;
        int multi_line_start_offset;

        /* Points into tok->buf, which is retained while INSIDE_FSTRING(tok). */
        int last_expr_start = NULL;
        int last_expr_start_offset;

        boolean in_debug;
        boolean in_format_spec;

        int string_kind;
    }

    /** C: tok->underflow, the function that refills the buffer. */
    interface Underflow {
        boolean underflow(tok_state tok);
    }

    /** Tokenizer state (C: struct tok_state). */
    public static final class tok_state {
        /** The array the pointers below index (C: the memory tok->buf points into). */
        byte[] input;

        /* Input state; buf <= cur <= inp <= end */
        /* NB an entire line is held in the buffer */
        int buf = NULL;     /* Input buffer, or NULL */
        int cur = NULL;     /* Next character in buffer */
        int inp = NULL;     /* End of data in buffer */
        int end = NULL;     /* End of input buffer if buf != NULL */
        int start = NULL;   /* Start of current token if not NULL */
        int done;           /* E_OK normally, E_EOF at EOF, otherwise error code */
        /* NB If done != E_OK, cur must be == inp!!! */
        int tabsize;        /* Tab spacing */
        int indent;         /* Current indentation index */
        int[] indstack = new int[MAXINDENT];            /* Stack of indents */
        boolean atbol;      /* Nonzero if at begin of new line */
        int pendin;         /* Pending indents (if > 0) or dedents (if < 0) */
        String prompt, nextprompt;          /* For interactive prompting */
        int lineno;         /* Current line number */
        int first_lineno;   /* First line of a single line or multi line string
                               expression (cf. issue 16806) */
        int starting_col_offset; /* The column offset at the beginning of a token */
        int col_offset;     /* Current col offset */
        int level;          /* () [] {} Parentheses nesting level */
                /* Used to allow free continuations inside them */
        char[] parenstack = new char[MAXLEVEL];
        int[] parenlinenostack = new int[MAXLEVEL];
        int[] parencolstack = new int[MAXLEVEL];
        String filename;
        String module;
        /* Stuff for checking on different tab sizes */
        int[] altindstack = new int[MAXINDENT];         /* Stack of alternate indents */
        /* Stuff for PEP 0263 */
        int decoding_state;
        boolean decoding_erred;     /* whether erred in decoding  */
        String encoding;            /* Source encoding. */
        boolean cont_line;          /* whether we are in a continuation line. */
        int line_start = NULL;      /* pointer to start of current line */
        int multi_line_start = NULL; /* pointer to start of first line of
                                        a single line or multi line string
                                        expression (cf. issue 16806) */
        String enc;                 /* Encoding for the current str. */
        int str = NULL;             /* Source string being tokenized (if tokenizing from a string)*/

        boolean type_comments;      /* Whether to look for type comments */

        Underflow underflow;        /* Function to call when buffer is empty and we need to refill it*/

        boolean report_warnings;
        tokenizer_mode[] tok_mode_stack = new tokenizer_mode[MAXFSTRINGLEVEL];
        int tok_mode_stack_index;
        boolean tok_extra_tokens;
        boolean comment_newline;
        boolean implicit_newline;
        boolean barry_as_bdfl;

        /**
         * The exception the tokenizer set (C: the pending exception set by
         * PyErr_SetObject), if any.
         */
        PythonSyntaxError error;

        /**
         * Receives the tokenizer's warnings (C: PyErr_WarnExplicitObject);
         * returning false turns the warning into an error.
         */
        Parser.WarningHandler warning_handler = w -> true;

        /** C: *p, the byte at index p (0 at the end of the input, as C's NUL). */
        int at(int p) {
            return input[p] & 0xff;
        }

        /** C: strlen(p), the bytes from p to the NUL ending the input. */
        int strlen(int p) {
            int q = p;
            while (input[q] != 0) {
                q++;
            }
            return q - p;
        }
    }

    /** C: ISSTRINGLIT(x), Include/internal/pycore_token.h. */
    static boolean ISSTRINGLIT(int x) {
        return x == STRING || x == FSTRING_MIDDLE || x == FSTRING_END
                || x == TSTRING_MIDDLE || x == TSTRING_END;
    }

    /* Create and initialize a new tok_state structure */
    static tok_state _PyTokenizer_tok_new() {
        tok_state tok = new tok_state();
        tok.buf = tok.cur = tok.inp = NULL;
        tok.start = NULL;
        tok.end = NULL;
        tok.done = E_OK;
        tok.tabsize = TABSIZE;
        tok.indent = 0;
        tok.indstack[0] = 0;
        tok.atbol = true;
        tok.pendin = 0;
        tok.prompt = tok.nextprompt = null;
        tok.lineno = 0;
        tok.starting_col_offset = -1;
        tok.col_offset = -1;
        tok.level = 0;
        tok.altindstack[0] = 0;
        tok.decoding_state = STATE_INIT;
        tok.decoding_erred = false;
        tok.enc = null;
        tok.encoding = null;
        tok.cont_line = false;
        tok.filename = null;
        tok.module = null;
        tok.type_comments = false;
        tok.underflow = null;
        tok.str = NULL;
        tok.report_warnings = true;
        tok.tok_extra_tokens = false;
        tok.comment_newline = false;
        tok.implicit_newline = false;
        for (int i = 0; i < MAXFSTRINGLEVEL; i++) {
            tok.tok_mode_stack[i] = new tokenizer_mode();
        }
        tok.tok_mode_stack[0].kind = TOK_REGULAR_MODE;
        tok.tok_mode_stack[0].quote = '\0';
        tok.tok_mode_stack[0].quote_size = 0;
        tok.tok_mode_stack[0].in_debug = false;
        tok.tok_mode_stack_index = 0;
        return tok;
    }

    static int _PyLexer_type_comment_token_setup(tok_state tok, token token, int type,
            int col_offset, int end_col_offset, int start, int end) {
        token.level = tok.level;
        token.lineno = token.end_lineno = tok.lineno;
        token.col_offset = col_offset;
        token.end_col_offset = end_col_offset;
        token.start = start;
        token.end = end;
        return type;
    }

    static int _PyLexer_token_setup(tok_state tok, token token, int type, int start, int end) {
        assert (start == NULL && end == NULL) || (start != NULL && end != NULL);
        token.level = tok.level;
        if (ISSTRINGLIT(type)) {
            token.lineno = tok.first_lineno;
        }
        else {
            token.lineno = tok.lineno;
        }
        token.end_lineno = tok.lineno;
        token.col_offset = token.end_col_offset = -1;
        token.start = start;
        token.end = end;
        if (start != NULL && end != NULL) {
            token.col_offset = tok.starting_col_offset;
            token.end_col_offset = tok.col_offset;
        }
        return type;
    }
}
