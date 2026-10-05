"""Dump CPython's tokens, the oracle for the Java tokenizer (compare_tokens.py).

Usage: dump_tokens.py [--all] [--mode file|single|eval] ROOT OUT

Walks ROOT for *.py files (or takes ROOT itself if it is a file) and writes,
per file:

    #FILE path
    #SOURCE text
    TYPE LINENO COL END_LINENO END_COL STRING
    #META text          (after a token that carries metadata)
    ...

The tokens are the ones CPython's parser sees: _tokenize.TokenizerIter with
extra_tokens=False gives the C tokenizer's own tokens (exact types, no NL or
COMMENT, empty NEWLINE text, column -1 for INDENT, DEDENT and ENDMARKER). Two
things it reports differently from what the parser gets are fixed here:

- The parser's end column is the tokenizer's column count (tok->col_offset),
  which counts characters the token's text leaves out: the newline of a
  NEWLINE, and the second brace of an FSTRING_MIDDLE/TSTRING_MIDDLE ending in
  an escaped brace ("a}}" has the text "a}").
- Token.metadata, the source text of an f-string debug expression or
  t-string interpolation (lexer.c set_ftstring_expr), isn't exposed; it is
  recomputed from the source.

COL and END_COL are UTF-8 byte offsets, as CPython's parser and AST use
(tokenize reports characters). #SOURCE is the decoded source with universal
newlines, for error messages. Text is escaped: backslash, newline and carriage
return become \\\\, \\n and \\r.

By default only files this Python can also ast.parse() are dumped, so they are
expected to be accepted; --all keeps every file that tokenizes (for the
must-reject samples).
"""

import argparse
import ast
import io
import pathlib
import sys
import tokenize
import _tokenize
from token import tok_name, FSTRING_START, TSTRING_START, FSTRING_MIDDLE, \
    TSTRING_MIDDLE, FSTRING_END, TSTRING_END, LBRACE, RBRACE, LPAR, RPAR, \
    LSQB, RSQB, EXCLAMATION, COLON, EQUAL, ENDMARKER, NEWLINE, DEDENT
import warnings


def escape(text):
    return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


def strip_comments(expr):
    """The comment handling of lexer.c set_ftstring_expr."""
    hash_detected = False
    in_string = False
    quote_char = None
    i = 0
    while i < len(expr):
        ch = expr[i]
        if ch == "\\":
            i += 2
            continue
        if ch in "\"'":
            if not in_string:
                in_string, quote_char = True, ch
            elif ch == quote_char:
                in_string = False
        elif ch == "#" and not in_string:
            hash_detected = True
            break
        i += 1
    if not hash_detected:
        return expr
    out = []
    in_string = False
    quote_char = None
    i = 0
    while i < len(expr):
        ch = expr[i]
        if ch in "\"'":
            if not in_string:
                in_string, quote_char = True, ch
            elif ch == quote_char:
                in_string = False
            out.append(ch)
        elif ch == "#" and not in_string:
            while i < len(expr) and expr[i] != "\n":
                i += 1
            if i < len(expr):
                out.append("\n")
        else:
            out.append(ch)
        i += 1
    return "".join(out)


class FStringMode:
    """What the lexer tracks per f/t-string (tokenizer_mode) for metadata."""

    def __init__(self, is_tstring):
        self.is_tstring = is_tstring
        self.in_expr = False      # between a field's '{' and its ':' or '}'
        self.depth = 0            # brackets open inside the expression
        self.expr_start = None    # offset just after the field's '{'
        self.expr_end = None      # last_expr_end
        self.in_debug = False


def compute_metadata(toks, offset, text):
    """{token index: metadata} for the tokens set_ftstring_expr sets it on."""
    metadata = {}
    modes = []
    for i, (type_, string, start, end) in enumerate(toks):
        if type_ in (FSTRING_START, TSTRING_START):
            modes.append(FStringMode(type_ == TSTRING_START))
            continue
        if type_ in (FSTRING_END, TSTRING_END):
            if modes:
                modes.pop()
            continue
        if not modes:
            continue
        mode = modes[-1]
        if not mode.in_expr:
            if type_ == LBRACE:
                # A replacement field starts (_PyLexer_update_ftstring_expr '{').
                mode.in_expr = True
                mode.depth = 0
                mode.expr_start = offset(end)
                mode.expr_end = None
                mode.in_debug = False
            continue
        if type_ in (LPAR, LSQB, LBRACE):
            mode.depth += 1
            continue
        if type_ in (RPAR, RSQB) or (type_ == RBRACE and mode.depth > 0):
            mode.depth -= 1
            continue
        if mode.depth > 0:
            continue
        if type_ == EQUAL and i + 1 < len(toks) and \
                toks[i + 1][0] in (RBRACE, EXCLAMATION, COLON):
            mode.in_debug = True
            continue
        if type_ in (EXCLAMATION, COLON, RBRACE):
            if type_ != COLON or mode.expr_end is None:
                mode.expr_end = offset(start)
            if mode.in_debug or mode.is_tstring:
                metadata[i] = strip_comments(text[mode.expr_start:mode.expr_end])
            if type_ in (COLON, RBRACE):
                mode.in_expr = False
    return metadata


def dump_file(path, out, check_parse, exec_input):
    src = path.read_bytes()
    if check_parse:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            ast.parse(src)
    encoding, _ = tokenize.detect_encoding(io.BytesIO(src).readline)
    text = src.decode(encoding)
    if text.startswith("\ufeff"):
        text = text[1:]
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = text.split("\n")
    line_starts = [0]
    for line in lines:
        line_starts.append(line_starts[-1] + len(line) + 1)

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        toks = [(t[0], t[1], t[2], t[3]) for t in
                _tokenize.TokenizerIter(io.StringIO(text).readline, extra_tokens=False)]
    if not toks:
        # TokenizerIter yields nothing for empty input; C's tokenizer gives
        # ENDMARKER on line 0, having read no line (exec input gets no
        # implicit newline when there's nothing to end).
        toks = [(ENDMARKER, "", (0, -1), (0, -1))]
    if not exec_input and not text.endswith("\n"):
        # TokenizerIter adds a newline to an unterminated last line, as C's
        # tokenizer does only for exec input; without it there is no NEWLINE
        # token for that line.
        last_row = len(lines)
        toks = [t for t in toks if not (t[0] == NEWLINE and t[2][0] == last_row)]
        # Without that NEWLINE, C's tokenizer reaches the end with indentation
        # levels still open and returns ENDMARKER first; the parser turns it
        # into a NEWLINE (single input) and has the tokenizer emit the DEDENTs
        # (TokenSource.implyDedents), then ENDMARKER again.
        dedents = 0
        while len(toks) > dedents + 1 and toks[-2 - dedents][0] == DEDENT:
            dedents += 1
        if dedents:
            end = toks[-1]
            toks = toks[:-1 - dedents] + [end] + toks[-1 - dedents:]

    def offset(pos):
        row, col = pos
        return line_starts[row - 1] + col

    def byte_col(row, col):
        if col < 0 or row - 1 >= len(lines):
            return col
        line = lines[row - 1]
        # (A column past the end is the newline's, or past it.)
        return len(line[:col].encode("utf-8")) + max(0, col - len(line))

    metadata = compute_metadata(toks, offset, text)
    out.write(f"#FILE {path}\n")
    out.write(f"#SOURCE {escape(text)}\n")
    for i, (type_, string, (row, col), (end_row, end_col)) in enumerate(toks):
        if type_ in (FSTRING_MIDDLE, TSTRING_MIDDLE) and string[-1:] in ("{", "}") and \
                text[offset((end_row, end_col)):][:1] == string[-1]:
            # An escaped brace: the tokenizer consumed a second one.
            end_col += 1
        elif type_ == NEWLINE:
            end_col += 1
        out.write(f"{tok_name[type_]} {row} {byte_col(row, col)} "
                  f"{end_row} {byte_col(end_row, end_col)} {escape(string)}\n")
        if i in metadata:
            out.write(f"#META {escape(metadata[i])}\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="skip the ast.parse() filter")
    ap.add_argument("--mode", choices=["file", "single", "eval"], default="file",
                    help="the input the tokens are for (single and eval get no implicit newline)")
    ap.add_argument("root")
    ap.add_argument("out")
    args = ap.parse_args()

    root = pathlib.Path(args.root)
    paths = [root] if root.is_file() else sorted(root.rglob("*.py"))
    dumped = skipped = 0
    with open(args.out, "w", encoding="utf-8") as out:
        for path in paths:
            buf = io.StringIO()
            try:
                dump_file(path, buf, not args.all, args.mode == "file")
            except Exception:
                skipped += 1
                continue
            out.write(buf.getvalue())
            dumped += 1
    print(
        f"dumped {dumped} files from {args.root}; skipped {skipped} "
        f"that Python {sys.version.split()[0]} cannot tokenize/parse",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
