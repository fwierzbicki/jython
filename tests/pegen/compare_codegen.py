"""Compare the Java compiler's codegen with CPython's, file by file.

Usage: compare_codegen.py [--mode file|single|eval] [--optimize N] [--show N]
                          [--no-build] [--known FILE] PATH...

PATH is a .py file or a directory (searched for *.py). Each file is parsed
as ast.parse() parses it, and the tree compiled to an instruction sequence
by _testinternalcapi.compiler_codegen(tree, "<unknown>", optimize, mode),
which runs preprocess (at the optimize level), symtable and codegen. The
Java side (tests/java/org/python/pegen/CodegenCompare.java) does the same
with Compile._PyCompile_CodeGen. Compared, in a canonical text form both
sides write:

- the instructions of the module's unit and of every nested unit (depth
  first): opcode, oparg (None for an opcode without one, jump targets as
  instruction indices) and location;
- the module unit's argcount, posonlyargcount, kwonlyargcount and constants
  (compiler_codegen exposes no other unit's constants; a code object
  constant is shown as its name, qualname and first line, its contents being
  compared through its unit's instructions);
- the exception, when either side rejects the file, and the warnings
  compiling issued.

A file using a construct the Java port doesn't cover yet is reported as
"unsupported" and doesn't fail. --known names a file of expected
differences, as for compare_ast.py (mode "codegen"). Exits 0 if every
other file matches.

Needs Python 3.15 (run it with the CPython build the compiler follows) and
`ant compile` already run. Output goes to build/pegen-codegen/.
"""

import argparse
import ast
import opcode
import os
import pathlib
import sys
import warnings
import _testinternalcapi

from compare_ast import (ROOT, bits, blocks, error, first_difference, python_files,
                         read_known, run, sha, string, warning_lines)

OUT = ROOT / "build" / "pegen-codegen"
OPNAME = {v: k for k, v in opcode.opmap.items()}
COMPILE_MODE = {"file": 0, "eval": 1, "single": 2}
AST_MODE = {"file": "exec", "eval": "eval", "single": "single"}


def constant(v):
    """A constant's canonical form (as CodegenCompare.constant())."""
    if v is None or v is Ellipsis or isinstance(v, bool):
        return "Ellipsis" if v is Ellipsis else str(v)
    if isinstance(v, int):
        return "int:%d" % v
    if isinstance(v, float):
        return "float:" + bits(v)
    if isinstance(v, complex):
        return "complex:%s,%s" % (bits(v.real), bits(v.imag))
    if isinstance(v, str):
        return "str:" + string(v)
    if isinstance(v, bytes):
        return "bytes:" + v.hex()
    if isinstance(v, tuple):
        return "tuple(" + ",".join(constant(x) for x in v) + ")"
    if isinstance(v, frozenset):
        return "frozenset(" + ",".join(sorted(constant(x) for x in v)) + ")"
    if isinstance(v, slice):
        return "slice(%s,%s,%s)" % (constant(v.start), constant(v.stop), constant(v.step))
    if hasattr(v, "co_qualname"):
        return "code:%s,%s,%d" % (string(v.co_name), string(v.co_qualname), v.co_firstlineno)
    return "?" + type(v).__name__


def unit(seq, out):
    """A unit's instructions, then its nested units, depth first (iteratively:
    lambdas nest deeper than Python's recursion limit)."""
    stack = [seq]
    while stack:
        seq = stack.pop()
        if seq is None:
            out.append("#END\n")
            continue
        out.append("#UNIT\n")
        for op, arg, lineno, end_lineno, col, end_col in seq.get_instructions():
            out.append("%s %s %d %d %d %d\n" % (OPNAME[op], arg, lineno, end_lineno, col,
                                                end_col))
        stack.append(None)
        stack.extend(reversed(seq.get_nested()))


ERRORS = (SyntaxError, ValueError, MemoryError, OverflowError, SystemError, RecursionError)

# The results of compiler_codegen, never freed: on 3.15.0rc2 and rc3, freeing a
# sequence whose module has annotations decrefs a list twice
# (PyInstructionSequence_Fini doesn't clear s_nested, and runs twice on
# s_annotations_code), which corrupts the heap or aborts a debug build. So
# they are kept, and main() leaves with os._exit.
KEEP = []


def cpython(path, mode, optimize):
    """The canonical form of CPython's codegen for path (after the #FILE line)."""
    src = pathlib.Path(path).read_bytes()
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            tree = ast.parse(src, mode=AST_MODE[mode])
    except ERRORS as e:
        return [error(e)]
    out = []
    with warnings.catch_warnings(record=True) as ws:
        warnings.simplefilter("always")
        try:
            seq, md = _testinternalcapi.compiler_codegen(tree, "<unknown>", optimize,
                                                         COMPILE_MODE[mode])
            KEEP.append(seq)
            out.append("#ARGS %d %d %d\n" % (md["argcount"], md["posonlyargcount"],
                                             md["kwonlyargcount"]))
            for v in md["consts"]:
                out.append("#CONST " + constant(v) + "\n")
            unit(seq, out)
        except ERRORS as e:
            out = [error(e)]
    out += warning_lines(ws)
    return "".join(out).splitlines(keepends=True)


def build():
    tests = ROOT / "tests/java/org/python/pegen"
    run(["javac", "-nowarn", "-cp", ROOT / "build/classes", "-d", OUT / "classes",
         tests / "CodegenCompare.java", tests / "AstCompare.java"])


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--mode", choices=["file", "single", "eval"], default="file")
    ap.add_argument("--optimize", type=int, choices=[0, 1, 2], default=0)
    ap.add_argument("--show", type=int, default=20, help="differences to show in full")
    ap.add_argument("--no-build", action="store_true",
                    help="reuse the driver compiled by an earlier run")
    ap.add_argument("--known", help="file of expected differences")
    ap.add_argument("paths", nargs="+")
    args = ap.parse_args()
    known = read_known(args.known)

    (OUT / "classes").mkdir(parents=True, exist_ok=True)
    if not args.no_build:
        build()

    file_list = OUT / "files"
    file_list.write_text("".join(f"{path}\n" for path in python_files(args.paths)),
                         encoding="utf-8")
    java_out = OUT / "java.out"
    run(["java", "-ea", "-cp", f"{ROOT / 'build/classes'}:{OUT / 'classes'}",
         "org.python.pegen.CodegenCompare", "--mode", args.mode,
         "--optimize", args.optimize, file_list, java_out])

    files = same = 0
    differ = {"code": [], "error": [], "accept/reject": [], "warnings": [], "crash": []}
    unsupported = {}
    expected = []
    fixed = []
    for name, java in blocks(java_out):
        files += 1
        if java and java[0].startswith("#UNSUPPORTED"):
            what = java[0][len("#UNSUPPORTED "):].rstrip("\n")
            unsupported[what] = unsupported.get(what, 0) + 1
            continue
        python = cpython(name, args.mode, args.optimize)
        key = ("codegen", sha(name))
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
        elif j.startswith("#WARNING") or p.startswith("#WARNING"):
            kind = "warnings"
        elif java_error:
            kind = "error"
        else:
            kind = "code"
        context = "".join(python[max(0, i - 6):i])
        differ[kind].append((name, i, context, j, p))

    shown = 0
    for kind, items in differ.items():
        for name, i, context, j, p in items:
            if shown == args.show:
                break
            shown += 1
            print(f"{kind}: {name} (codegen {sha(name)}; line {i + 1} of its dump)")
            if context:
                print("  " + context.rstrip("\n").replace("\n", "\n  "))
            print("  java:    " + j.rstrip("\n"))
            print("  cpython: " + p.rstrip("\n"))
    for what, n in sorted(unsupported.items(), key=lambda kv: -kv[1]):
        print(f"unsupported: {n} files: {what}")
    for name, description in fixed:
        print(f"known difference now matches, remove it from {args.known}: {name} "
              f"({description})")
    nunsupported = sum(unsupported.values())
    print(f"{files} files, {same} identical"
          + (f", {nunsupported} unsupported" if nunsupported else "")
          + (f", {len(expected)} known differences" if expected else "")
          + "".join(f", {len(v)} {k}" for k, v in differ.items() if v))
    sys.stdout.flush()
    # Not sys.exit: see KEEP.
    os._exit(0 if files and same + len(expected) + nunsupported == files else 1)


if __name__ == "__main__":
    main()
