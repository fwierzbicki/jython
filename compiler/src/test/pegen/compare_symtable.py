"""Compare the Java symbol table with CPython's _symtable.symtable(), file by file.

Usage: compare_symtable.py [--mode file|single|eval] [--show N]
                           [--no-build] [--known FILE] PATH...

PATH is a .py file or a directory (searched for *.py). Each file is
tokenized by the Java tokenizer, parsed by the checked-in parser and given to org.python.pegen.compile.Symtable (driven by
compiler/src/test/java/org/python/pegen/SymtableCompare.java) the way the _symtable
module does it (_Py_SymtableStringObjectFlags: parse, future, then symtable,
with no preprocess), and compared with what CPython's
_symtable.symtable(source, "<unknown>", mode) gives. Compared:

- the block tree, when both accept the file: each block's type, name,
  lineno and nested flag, its symbols with their raw flags (DEF_* bits and
  scope), its varnames and its children, in order. These are all the fields
  _symtable's entries show; the other ste_* fields (ste_generator,
  ste_needs_class_closure, ...) aren't visible from Python.
- the exception, when either rejects it, as compare_ast.py writes it.

Warnings aren't compared: the parser's are compared by compare_ast.py, and
symtable issues none.

--known names a file of expected differences, as for compare_ast.py
(compare_known.txt; entries are per mode and file, so the two
scripts share it). Exits 0 if every other file matches.

Needs Python 3.15 (the CPython build the parser follows) and the compiler built
(see compare_ast.py; --no-build skips it). Output goes to
compiler/build/pegen-symtable/.
"""

import argparse
import pathlib
import sys
import warnings
import _symtable

from compare_ast import (COMPILER_BUILD, CLASSPATH, build, blocks, error, first_difference,
                         python_files, read_known, run, sha, string)

OUT = COMPILER_BUILD / "pegen-symtable"


# ---- The canonical form (mirrors SymtableCompare.java) ----

def block(ste, out, indent):
    out.append(" " * indent + "BLOCK %d %s %d %d\n"
               % (ste.type, string(ste.name), ste.lineno, ste.nested))
    for name, flags in sorted((string(k), v) for k, v in ste.symbols.items()):
        out.append(" " * (indent + 1) + "SYMBOL %s %d\n" % (name, flags))
    out.append(" " * (indent + 1) + "VARNAMES" + "".join(" " + string(v) for v in ste.varnames)
               + "\n")
    for child in ste.children:
        block(child, out, indent + 1)


def cpython(path, mode):
    """The canonical form of CPython's result for path (after the #FILE line)."""
    src = pathlib.Path(path).read_bytes()
    out = []
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            top = _symtable.symtable(src, "<unknown>", mode)
        block(top, out, 0)
    except (SyntaxError, ValueError, MemoryError, OverflowError, SystemError,
            RecursionError) as e:
        out.append(error(e))
    return "".join(out).splitlines(keepends=True)


# ---- Running and comparing ----



def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--mode", choices=["file", "single", "eval"], default="file")
    ap.add_argument("--show", type=int, default=20, help="differences to show in full")
    ap.add_argument("--no-build", action="store_true",
                    help="use the classes an earlier build made")
    ap.add_argument("--known", help="file of expected differences")
    ap.add_argument("paths", nargs="+")
    args = ap.parse_args()
    symtable_mode = {"file": "exec", "single": "single", "eval": "eval"}[args.mode]
    known = read_known(args.known)

    OUT.mkdir(parents=True, exist_ok=True)
    if not args.no_build:
        build()

    file_list = OUT / "files"
    file_list.write_text("".join(f"{path}\n" for path in python_files(args.paths)),
                     encoding="utf-8")

    java_out = OUT / "java.out"
    run(["java", "-ea", "-cp", CLASSPATH,
         "org.python.pegen.SymtableCompare", "--mode", args.mode, file_list, java_out])

    files = same = 0
    differ = {"symtable": [], "error": [], "accept/reject": [], "crash": []}
    expected = []
    fixed = []
    for name, java in blocks(java_out):
        files += 1
        python = cpython(name, symtable_mode)
        key = (args.mode, sha(name))
        if java == python:
            same += 1
            if key in known:
                fixed.append((name, known[key]))
            continue
        if key in known:
            expected.append((name, known[key]))
            continue
        i = first_difference(java, python)
        j = java[i] if i < len(java) else "(end)\n"
        p = python[i] if i < len(python) else "(end)\n"
        java_error = bool(java) and java[0].startswith("#ERROR")
        python_error = bool(python) and python[0].startswith("#ERROR")
        if java and java[0].startswith("#CRASH"):
            kind = "crash"
        elif java_error != python_error:
            kind = "accept/reject"
        elif java_error:
            kind = "error"
        else:
            kind = "symtable"
        context = "".join(python[max(0, i - 4):i])
        differ[kind].append((name, i, context, j, p))

    shown = 0
    for kind, items in differ.items():
        for name, i, context, j, p in items:
            if shown == args.show:
                break
            shown += 1
            print(f"{kind}: {name} ({args.mode} {sha(name)}; line {i + 1} of its dump)")
            if context:
                print("  " + context.rstrip("\n").replace("\n", "\n  "))
            print("  java:    " + j.rstrip("\n"))
            print("  cpython: " + p.rstrip("\n"))
    for name, description in fixed:
        print(f"known difference now matches, remove it from {args.known}: {name} "
              f"({description})")
    print(f"{files} files, {same} identical"
          + (f", {len(expected)} known differences" if expected else "")
          + "".join(f", {len(v)} {k}" for k, v in differ.items() if v))
    sys.exit(0 if files and same + len(expected) == files else 1)


if __name__ == "__main__":
    main()
