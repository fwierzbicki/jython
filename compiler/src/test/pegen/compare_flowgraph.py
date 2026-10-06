"""Compare the Java compiler's flowgraph with CPython's, file by file.

Usage: compare_flowgraph.py [--mode file|single|eval] [--optimize N] [--show N]
                            [--no-build] [--known FILE] PATH...

PATH is a .py file or a directory (searched for *.py). Each file is parsed
as ast.parse() parses it and compiled to instruction sequences by
_testinternalcapi.compiler_codegen(tree, "<unknown>", optimize, mode), as
compare_codegen.py does; then every unit's sequence, the module's and each
nested one's, is optimized by _testinternalcapi.optimize_cfg(seq, consts,
nlocals), which runs flowgraph's optimizations (_PyCompile_OptimizeCfg: a
fresh const cache, nparams 0, firstlineno 1). The Java side
(compiler/src/test/java/org/python/pegen/FlowgraphCompare.java) does the same with
Flowgraph._PyCompile_OptimizeCfg. Compared, in a canonical text form both
sides write, unit by unit (depth first): the constants after flowgraph and
the optimized instructions (opcode, oparg, location; jump targets as
instruction indices).

compiler_codegen exposes the module unit's constants but not a nested
unit's, nor any unit's number of locals, which optimize_cfg needs. Those
come from the Java side's #IN and #INCONST lines (a code object constant
becomes a stand-in carrying its name, qualname and first line: flowgraph
only ever loads it). A wrong one would be fed to both sides alike; Phase F
(co_consts, co_nlocals) catches those.

CPython bugs (3.15.0rc2 and rc3, not reported yet): optimize_cfg fails an
assertion, aborting a debug build, on two kinds of unit; compile() is fine
with both.
- load_fast_push_block, on any unit with an `async for`: optimize_cfg runs
  optimize_load_fast on a CFG that still has the pseudo-instructions the
  real path converts first.
- _PyCfg_FromInstructionSequence, on a module with annotations: with
  compiler_codegen's c_save_nested_seqs, the module's annotation code has
  the __annotate__ unit as a nested sequence, which the assertion forbids.
The Java side, which ports the assertions, writes such a unit as #ABORT,
and this script then doesn't run optimize_cfg on it: those units are left
for Phase F to compare.

A file codegen rejects is compared by its error only (compare_codegen.py
checks the rest). --known names a file of expected differences, as for
compare_ast.py (mode "flowgraph"). Exits 0 if every other file matches.

Needs Python 3.15 (run it with the CPython build the compiler follows) and
the compiler built
(see compare_ast.py; --no-build skips it). Output goes to
compiler/build/pegen-flowgraph/.
"""

import argparse
import ast
import opcode
import os
import pathlib
import struct
import sys
import warnings
import _testinternalcapi

from compare_ast import (COMPILER_BUILD, CLASSPATH, build, blocks, error, first_difference,
                         python_files, read_known, run, sha)
from compare_codegen import AST_MODE, COMPILE_MODE, ERRORS, KEEP, constant

OUT = COMPILER_BUILD / "pegen-flowgraph"
OPNAME = {v: k for k, v in opcode.opmap.items()}


class Code:
    """A stand-in for a code object constant of a nested unit."""

    def __init__(self, name, qualname, firstlineno):
        self.co_name = name
        self.co_qualname = qualname
        self.co_firstlineno = firstlineno


INPUT_NAMES = {
    "__builtins__": {},
    "F": lambda h: struct.unpack(">d", bytes.fromhex(h))[0],
    "C": lambda r, i: complex(struct.unpack(">d", bytes.fromhex(r))[0],
                              struct.unpack(">d", bytes.fromhex(i))[0]),
    "S": lambda h: bytes.fromhex(h).decode("utf-16-be", "surrogatepass"),
    "B": bytes.fromhex,
    "T": lambda *items: tuple(items),
    "FS": lambda *items: frozenset(items),
    "SL": slice,
    "CODE": Code,
}


def inputs(java):
    """The units' inputs from the Java side's #IN and #INCONST lines:
    [(nlocals, consts)], depth first."""
    units = []
    for line in java:
        if line.startswith("#IN "):
            units.append((int(line[4:]), []))
        elif line.startswith("#INCONST "):
            units[-1][1].append(eval(line[9:], dict(INPUT_NAMES)))
    return units


def units(seq):
    """The units' sequences, depth first (as FlowgraphCompare.units)."""
    stack = [seq]
    while stack:
        seq = stack.pop()
        yield seq
        stack.extend(reversed(seq.get_nested()))


def java_aborts(java):
    """The units the Java side writes as #ABORT: {index: #ABORT line}."""
    aborts = {}
    unit = -1
    for line in java:
        if line == "#UNIT\n":
            unit += 1
        elif line.startswith("#ABORT "):
            aborts[unit] = line
    return aborts


def cpython(path, mode, optimize, java_inputs, aborts):
    """The canonical form of CPython's flowgraph for path (after the #FILE line)."""
    src = pathlib.Path(path).read_bytes()
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            tree = ast.parse(src, mode=AST_MODE[mode])
    except ERRORS as e:
        return [error(e)]
    out = []
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            seq, md = _testinternalcapi.compiler_codegen(tree, "<unknown>", optimize,
                                                         COMPILE_MODE[mode])
        except ERRORS as e:
            return [error(e)]
        KEEP.append(seq)
        seqs = list(units(seq))
        if len(seqs) != len(java_inputs):
            return ["#UNITS %d\n" % len(seqs)]
        for i, s in enumerate(seqs):
            nlocals, consts = java_inputs[i]
            if i == 0:
                consts = list(md["consts"])
            out.append("#UNIT\n")
            if i in aborts:
                out.append(aborts[i])
                continue
            try:
                optimized = _testinternalcapi.optimize_cfg(s, consts, nlocals)
            except ERRORS as e:
                out.append(error(e))
                break
            for v in consts:
                out.append("#CONST " + constant(v) + "\n")
            for op, arg, lineno, end_lineno, col, end_col in optimized.get_instructions():
                out.append("%s %s %d %d %d %d\n" % (OPNAME[op], arg, lineno, end_lineno,
                                                    col, end_col))
    return "".join(out).splitlines(keepends=True)




def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--mode", choices=["file", "single", "eval"], default="file")
    ap.add_argument("--optimize", type=int, choices=[0, 1, 2], default=0)
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

    file_list = OUT / "files"
    file_list.write_text("".join(f"{path}\n" for path in python_files(args.paths)),
                         encoding="utf-8")
    java_out = OUT / "java.out"
    run(["java", "-ea", "-cp", CLASSPATH,
         "org.python.pegen.FlowgraphCompare", "--mode", args.mode,
         "--optimize", args.optimize, file_list, java_out])

    files = same = nabort = 0
    differ = {"code": [], "consts": [], "error": [], "accept/reject": [], "crash": []}
    expected = []
    fixed = []
    for name, java in blocks(java_out):
        files += 1
        java_inputs = inputs(java)
        java = [line for line in java if not line.startswith("#IN")]
        if java and java[0].startswith("#CRASH"):
            python = []
        else:
            aborted = java_aborts(java)
            nabort += len(aborted)
            python = cpython(name, args.mode, args.optimize, java_inputs, aborted)
        key = ("flowgraph", sha(name))
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
            j = "".join(java)
        elif java_error != python_error:
            kind = "accept/reject"
        elif java_error:
            kind = "error"
        elif j.startswith("#CONST") or p.startswith("#CONST"):
            kind = "consts"
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
            print(f"{kind}: {name} (flowgraph {sha(name)}; line {i + 1} of its dump)")
            if context:
                print("  " + context.rstrip("\n").replace("\n", "\n  "))
            print("  java:    " + j.rstrip("\n").replace("\n", "\n           "))
            print("  cpython: " + p.rstrip("\n"))
    for name, description in fixed:
        print(f"known difference now matches, remove it from {args.known}: {name} "
              f"({description})")
    print(f"{files} files, {same} identical"
          + (f", {len(expected)} known differences" if expected else "")
          + (f" ({nabort} units not compared: optimize_cfg aborts)" if nabort else "")
          + "".join(f", {len(v)} {k}" for k, v in differ.items() if v))
    sys.stdout.flush()
    # Not sys.exit: see compare_codegen.KEEP.
    os._exit(0 if files and same + len(expected) == files else 1)


if __name__ == "__main__":
    main()
