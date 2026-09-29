"""Dump tokens from CPython's tokenize module for RecognizerSmoke.java.

Usage: dump_tokens.py [--all] ROOT OUT

Walks ROOT for *.py files and writes one "#FILE path" line per file followed
by one line per token: TYPE LINENO COL END_LINENO END_COL STRING. TYPE is the
exact token type name (LPAR, NOTEQUAL, ...); OP remains only for an operator
nothing here recognizes, which the Java driver reports. COL and END_COL are
UTF-8 byte offsets, as CPython's lexer and AST use (tokenize reports
characters). STRING is the token's text with backslash, newline and carriage
return escaped as \\, \n and \r. NL, COMMENT and ENCODING are dropped, as
CPython's parser never sees them.

By default only files this Python can also ast.parse() are dumped, so the
recognizer is expected to accept all of them; --all keeps every file that
tokenizes (for the must-reject samples).
"""

import argparse
import ast
import pathlib
import sys
import tokenize

SKIP = {tokenize.NL, tokenize.COMMENT, tokenize.ENCODING}

# Operators tokenize reports only as OP. CPython's tokenizer gives '<>' type
# NOTEQUAL and leaves it to the grammar to accept it only under barry_as_FLUFL.
EXTRA_EXACT_TYPES = {"<>": "NOTEQUAL"}


def escape(text):
    return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


def byte_columns(raw_lines, encoding):
    """col(row, col) -> the UTF-8 byte offset of character column col on row."""
    decoded = [line.decode(encoding) for line in raw_lines]

    def col(row, column):
        if row - 1 < len(decoded):
            return len(decoded[row - 1][:column].encode("utf-8"))
        return column
    return col


def type_name(tok):
    if tok.exact_type == tokenize.OP:
        return EXTRA_EXACT_TYPES.get(tok.string, "OP")
    return tokenize.tok_name[tok.exact_type]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="skip the ast.parse() filter")
    ap.add_argument("root")
    ap.add_argument("out")
    args = ap.parse_args()

    dumped = skipped = 0
    with open(args.out, "w", encoding="utf-8") as out:
        for path in sorted(pathlib.Path(args.root).rglob("*.py")):
            try:
                src = path.read_bytes()
                if not args.all:
                    ast.parse(src)
                raw_lines = src.splitlines(keepends=True)
                lines = iter(raw_lines)
                toks = list(tokenize.tokenize(lambda: next(lines, b"")))
                col = byte_columns(raw_lines, toks[0].string)  # ENCODING token
            except Exception:
                skipped += 1
                continue
            dumped += 1
            out.write(f"#FILE {path}\n")
            for t in toks:
                if t.type in SKIP:
                    continue
                (row, column), (end_row, end_column) = t.start, t.end
                out.write(
                    f"{type_name(t)} {row} {col(row, column)} "
                    f"{end_row} {col(end_row, end_column)} {escape(t.string)}\n"
                )
    print(
        f"dumped {dumped} files from {args.root}; skipped {skipped} "
        f"that Python {sys.version.split()[0]} cannot tokenize/parse",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
