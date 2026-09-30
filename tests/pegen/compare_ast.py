"""Compare the Java PEG parser's output with CPython's ast.parse(), file by file.

Usage: compare_ast.py [--mode file|single|eval] [--show N] [--no-build] PATH...

PATH is a .py file or a directory (searched for *.py). Each file that
CPython's tokenizer accepts is tokenized with dump_tokens.py, parsed by a Java
parser generated with actions (tests/java/org/python/pegen/AstCompare.java),
and compared with what CPython's compile(..., "<unknown>", mode,
ast.PyCF_ONLY_AST) gives for the same source:

- the tree, every node, field and location, when both accept the file;
- the exception, when either rejects it: type, msg, lineno, offset,
  end_lineno, end_offset and text (for SyntaxError and its subclasses);
- the warnings the parser issued: category, lineno and message. (Warnings the
  tokenizer issues are left out: the Java side has no tokenizer yet.)

Both sides write the same canonical text (see AstCompare.java), so a
difference is a real one; the first differing line of each file is shown.
Exits 0 if every file matches.

Needs Python 3.15 (run it with the CPython build the parser follows, e.g.
../cpython/python.exe), `ant compile` already run, and ../cpython (or
CPYTHON=...) for the generator. Output goes to build/pegen-compare/.
"""

import argparse
import ast
import io
import os
import pathlib
import struct
import subprocess
import sys
import tokenize
import warnings
import _tokenize

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OUT = ROOT / "build" / "pegen-compare"


# ---- The canonical form (mirrors AstCompare.java) ----

def string(x):
    u = x.encode("utf-16-be", "surrogatepass")
    out = ['"']
    for i in range(0, len(u), 2):
        c = int.from_bytes(u[i:i + 2], "big")
        out.append(chr(c) if 0x20 <= c < 0x7F and chr(c) not in '\\"' else "\\u%04x" % c)
    return "".join(out) + '"'


def bits(f):
    return "%x" % struct.unpack("<Q", struct.pack("<d", f))[0]


SIMPLE_SUMS = (ast.expr_context, ast.boolop, ast.operator, ast.unaryop, ast.cmpop)


def value(v, out, indent):
    if v is None or v is Ellipsis or isinstance(v, bool):
        out.append(("Ellipsis" if v is Ellipsis else str(v)) + "\n")
    elif isinstance(v, SIMPLE_SUMS):
        out.append(type(v).__name__ + "\n")
    elif isinstance(v, ast.AST):
        out.append("\n")
        node(v, out, indent + 1)
    elif isinstance(v, list):
        out.append("[\n")
        for x in v:
            out.append(" " * (indent + 1))
            value(x, out, indent + 1)
        out.append(" " * indent + "]\n")
    elif isinstance(v, str):
        out.append(string(v) + "\n")
    elif isinstance(v, int):
        out.append(str(v) + "\n")
    elif isinstance(v, float):
        out.append("f" + bits(v) + "\n")
    elif isinstance(v, complex):
        out.append("c%s,%s\n" % (bits(v.real), bits(v.imag)))
    elif isinstance(v, bytes):
        out.append("b'" + v.hex() + "'\n")
    else:
        out.append("?%s\n" % type(v).__name__)


def node(n, out, indent):
    out.append(" " * indent + type(n).__name__ + "\n")
    for f in n._fields + n._attributes:
        out.append(" " * (indent + 1) + f + "=")
        value(getattr(n, f, None), out, indent + 1)


def error(e):
    fields = [type(e).__name__, string(str(e.msg if isinstance(e, SyntaxError) else e))]
    if isinstance(e, SyntaxError):
        fields += [str(e.lineno), str(e.offset), str(e.end_lineno), str(e.end_offset),
                   "None" if e.text is None else string(e.text)]
    return "#ERROR " + "\t".join(fields) + "\n"


def warning_lines(ws):
    return ["#WARNING %s\t%d\t%s\n" % (w.category.__name__, w.lineno, string(str(w.message)))
            for w in ws]


def tokenizer_warnings(text):
    """The warnings CPython's tokenizer alone issues for text."""
    with warnings.catch_warnings(record=True) as ws:
        warnings.simplefilter("always")
        try:
            for _ in _tokenize.TokenizerIter(io.StringIO(text).readline, extra_tokens=False):
                pass
        except SyntaxError:
            pass
    return {(w.category, w.lineno, str(w.message)) for w in ws}


def cpython(path, mode):
    """The canonical form of CPython's result for path (after the #FILE line)."""
    src = pathlib.Path(path).read_bytes()
    out = []
    with warnings.catch_warnings(record=True) as ws:
        warnings.simplefilter("always")
        try:
            tree = compile(src, "<unknown>", mode, ast.PyCF_ONLY_AST)
            node(tree, out, 0)
        except (SyntaxError, ValueError, MemoryError, OverflowError, SystemError) as e:
            out.append(error(e))
    if ws:
        encoding, _ = tokenize.detect_encoding(io.BytesIO(src).readline)
        from_tokenizer = tokenizer_warnings(src.decode(encoding))
        ws = [w for w in ws if (w.category, w.lineno, str(w.message)) not in from_tokenizer]
    out += warning_lines(ws)
    return "".join(out).splitlines(keepends=True)


# ---- Running and comparing ----

def run(cmd, **kw):
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def build(cpython_dir):
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    actions = OUT / "actions"
    run([sys.executable, ROOT / "src/pegen/tools/generate.py", "--cpython", cpython_dir,
         "--actions", "--output-dir", actions], env=env, stdout=subprocess.DEVNULL)
    run(["javac", "-nowarn", "-cp", ROOT / "build/classes", "-d", actions / "classes",
         actions / "GeneratedParser.java"])
    tests = ROOT / "tests/java/org/python/pegen"
    run(["javac", "-nowarn", "-cp", f"{actions / 'classes'}:{ROOT / 'build/classes'}",
         "-d", OUT / "classes", tests / "AstCompare.java", tests / "TokenDump.java"])


def blocks(path):
    """(file, lines) for each #FILE block of the Java output."""
    name, lines = None, []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#FILE "):
                if name is not None:
                    yield name, lines
                name, lines = line[6:].rstrip("\n"), []
            else:
                lines.append(line)
    if name is not None:
        yield name, lines


def first_difference(java, python):
    for i, (j, p) in enumerate(zip(java, python)):
        if j != p:
            return i
    return min(len(java), len(python))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--mode", choices=["file", "single", "eval"], default="file")
    ap.add_argument("--show", type=int, default=20, help="differences to show in full")
    ap.add_argument("--no-build", action="store_true",
                    help="reuse the parser and drivers built by an earlier run")
    ap.add_argument("paths", nargs="+")
    args = ap.parse_args()
    cpython_dir = os.environ.get("CPYTHON", ROOT.parent / "cpython")
    compile_mode = {"file": "exec", "single": "single", "eval": "eval"}[args.mode]

    OUT.mkdir(parents=True, exist_ok=True)
    if not args.no_build:
        build(cpython_dir)

    dump = OUT / "tokens"
    with open(dump, "w", encoding="utf-8") as out:
        for i, path in enumerate(args.paths):
            part = OUT / f"tokens.{i}"
            run([sys.executable, HERE / "dump_tokens.py", "--all", "--mode", args.mode, path, part])
            out.write(part.read_text(encoding="utf-8"))
            part.unlink()

    java_out = OUT / "java.out"
    run(["java", "-Xss16m", "-cp",
         f"{OUT / 'actions/classes'}:{ROOT / 'build/classes'}:{OUT / 'classes'}",
         "org.python.pegen.AstCompare", "--mode", args.mode, dump, java_out])

    files = same = 0
    differ = {"tree": [], "error": [], "accept/reject": [], "warnings": [], "crash": []}
    for name, java in blocks(java_out):
        files += 1
        python = cpython(name, compile_mode)
        if java == python:
            same += 1
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
            kind = "tree"
        context = "".join(python[max(0, i - 4):i])
        differ[kind].append((name, i, context, j, p))

    shown = 0
    for kind, items in differ.items():
        for name, i, context, j, p in items:
            if shown == args.show:
                break
            shown += 1
            print(f"{kind}: {name} (line {i + 1} of its dump)")
            if context:
                print("  " + context.rstrip("\n").replace("\n", "\n  "))
            print("  java:    " + j.rstrip("\n"))
            print("  cpython: " + p.rstrip("\n"))
    print(f"{files} files, {same} identical"
          + "".join(f", {len(v)} {k}" for k, v in differ.items() if v))
    sys.exit(0 if files and same == files else 1)


if __name__ == "__main__":
    main()
