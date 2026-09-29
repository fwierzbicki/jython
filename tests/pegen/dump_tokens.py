"""Dump tokens from CPython's tokenize module for RecognizerSmoke.java.

Usage: dump_tokens.py [--all] ROOT OUT

Walks ROOT for *.py files and writes one "#FILE path" line per file followed
by one line per token: TYPE LINENO COL END_LINENO END_COL STRING. TYPE is the
exact token type name (LPAR, NOTEQUAL, ...); OP remains only for an operator
nothing here recognizes, which the Java driver reports. STRING is only
meaningful for NAME and operator tokens and is "-" otherwise. NL, COMMENT and
ENCODING are dropped, as CPython's parser never sees them.

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
                lines = iter(src.splitlines(keepends=True))
                toks = list(tokenize.tokenize(lambda: next(lines, b"")))
            except Exception:
                skipped += 1
                continue
            dumped += 1
            out.write(f"#FILE {path}\n")
            for t in toks:
                if t.type in SKIP:
                    continue
                s = t.string if t.type in (tokenize.NAME, tokenize.OP) else "-"
                out.write(
                    f"{type_name(t)} {t.start[0]} {t.start[1]} "
                    f"{t.end[0]} {t.end[1]} {s}\n"
                )
    print(
        f"dumped {dumped} files from {args.root}; skipped {skipped} "
        f"that Python {sys.version.split()[0]} cannot tokenize/parse",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
