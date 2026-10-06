"""Compare the Java tokenizer's tokens with CPython's, file by file.

Usage: compare_tokens.py [--show N] [--no-build] [--known FILE] PATH...

PATH is a .py file or a directory (searched for *.py). Each file CPython's
tokenizer accepts is dumped with dump_tokens.py --all (CPython's tokens, as
the parser sees them, in file mode), tokenized by the Java tokenizer
(org.python.pegen.lexer, driven by compiler/src/test/java/org/python/pegen/TokenCompare.java)
and the two compared: types, text, positions (UTF-8 byte columns) and
Token.metadata. A file the Java tokenizer rejects shows as #ERROR.

dump_tokens.py only approximates some of what C's tokenizer gives (see its
docstring); --known names a file of expected differences, as for
compare_ast.py, with the mode "tokens". Exits 0 if every other file matches.

Needs Python 3.15 (run it with the CPython build the parser follows) and
the compiler built (see compare_ast.py; --no-build skips it). Output goes to
compiler/build/pegen-tokens/.
"""

import argparse
import pathlib
import sys

from compare_ast import (COMPILER_BUILD, CLASSPATH, HERE, blocks, build, first_difference,
                         read_known, run, sha)

OUT = COMPILER_BUILD / "pegen-tokens"
MODE = "tokens"




def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--show", type=int, default=20, help="differences to show in full")
    ap.add_argument("--no-build", action="store_true",
                    help="use the classes an earlier build made")
    ap.add_argument("--known", help="file of expected differences")
    ap.add_argument("paths", nargs="+")
    args = ap.parse_args()
    known = read_known(args.known)

    OUT.mkdir(parents=True, exist_ok=True)
    if not args.no_build:
        build()

    # CPython's tokens, without the #SOURCE lines.
    python = {}
    for i, path in enumerate(args.paths):
        part = OUT / f"tokens.{i}"
        run([sys.executable, HERE / "dump_tokens.py", "--all", path, part])
        for name, lines in blocks(part):
            python[name] = [line for line in lines if not line.startswith("#SOURCE ")]
        part.unlink()

    files = OUT / "files"
    files.write_text("".join(name + "\n" for name in python), encoding="utf-8")
    java_out = OUT / "java.out"
    run(["java", "-Xss16m", "-cp", CLASSPATH,
         "org.python.pegen.TokenCompare", files, java_out])

    total = same = 0
    differ = {"tokens": [], "metadata": [], "error": [], "crash": []}
    expected = []
    fixed = []
    for name, java in blocks(java_out):
        total += 1
        cpython = python[name]
        key = (MODE, sha(name))
        if java == cpython:
            same += 1
            if key in known:
                fixed.append((name, known[key]))
            continue
        if key in known:
            expected.append((name, known[key]))
            continue
        i = first_difference(java, cpython)
        j = java[i] if i < len(java) else "(end)\n"
        p = cpython[i] if i < len(cpython) else "(end)\n"
        if j.startswith("#CRASH"):
            kind = "crash"
        elif j.startswith("#ERROR"):
            kind = "error"
        elif j.startswith("#META") or p.startswith("#META"):
            kind = "metadata"
        else:
            kind = "tokens"
        context = "".join(cpython[max(0, i - 4):i])
        differ[kind].append((name, i, context, j, p))

    shown = 0
    for kind, items in differ.items():
        for name, i, context, j, p in items:
            if shown == args.show:
                break
            shown += 1
            print(f"{kind}: {name} ({MODE} {sha(name)}; line {i + 1} of its dump)")
            if context:
                print("  " + context.rstrip("\n").replace("\n", "\n  "))
            print("  java:    " + j.rstrip("\n"))
            print("  cpython: " + p.rstrip("\n"))
    for name, description in fixed:
        print(f"known difference now matches, remove it from {args.known}: {name} "
              f"({description})")
    print(f"{total} files, {same} identical"
          + (f", {len(expected)} known differences" if expected else "")
          + "".join(f", {len(v)} {k}" for k, v in differ.items() if v))
    sys.exit(0 if total and same + len(expected) == total else 1)


if __name__ == "__main__":
    main()
