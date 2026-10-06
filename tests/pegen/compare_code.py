"""Compare the Java compiler's code objects with CPython's compile(), file by file.

Usage: compare_code.py [--mode file|single|eval] [--optimize N] [--marshal]
                       [--show N] [--no-build] [--known FILE] PATH...

PATH is a .py file or a directory (searched for *.py). Each file is compiled
by compile(source, "<unknown>", mode, dont_inherit=True, optimize=N), and
the Java side (tests/java/org/python/pegen/CodeCompare.java) does the same
with Parser.fromString and Compile._PyAST_Compile. Compared, in a canonical
text form both sides write, for the module's code object and every code
object in its constants (depth first, in constant order): the fields
marshal writes (marshal.c w_object, TYPE_CODE):

- the argument counts, co_stacksize, co_flags, co_filename, co_name,
  co_qualname and co_firstlineno;
- co_names, and co_localsplusnames with their kinds (co_localspluskinds);
- co_consts (a code object as its name, qualname and first line; its
  contents follow as its own block);
- co_code, one code unit per line: the opcode name and arg, and the code
  unit's position (co_positions(), decoded from co_linetable);
- co_linetable and co_exceptiontable as hex, and the exception table's
  entries (start, end, target, depth, lasti);
- the exception, when either side rejects the file, and the warnings
  compiling issued.

With --marshal, the Java side writes each module's code object as its
marshal.dumps bytes (org.python.pegen.compile.Marshal), which are loaded
here with marshal.loads and written in the same canonical form: the
marshal writer is checked by what loading its output gives. (The bytes
can't be compared with CPython's marshal.dumps: C sets FLAG_REF by
refcount.) A code object nested too deeply to marshal is an error on
both sides.

--known names a file of expected differences, as for compare_ast.py (mode
"code"). Exits 0 if every other file matches.

Needs Python 3.15 (run it with the CPython build the compiler follows) and
`ant compile` already run. Output goes to build/pegen-code/.
"""

import argparse
import dis
import marshal
import opcode
import pathlib
import sys
import warnings
import _testinternalcapi

from compare_ast import (ROOT, bits, blocks, error, first_difference, python_files,
                         read_known, run, sha, string, warning_lines)

OUT = ROOT / "build" / "pegen-code"
COMPILE_MODE = {"file": "exec", "eval": "eval", "single": "single"}
CodeType = type(compile("", "", "exec"))


def constant(v):
    """A constant's canonical form (as CodeCompare.constant())."""
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
    if isinstance(v, CodeType):
        return "code:%s,%s,%d" % (string(v.co_name), string(v.co_qualname), v.co_firstlineno)
    return "?" + type(v).__name__


class Marshal:
    """Just enough of marshal's reader (marshal.c r_object) to get each code
    object's co_localsplusnames and co_localspluskinds, which Python doesn't
    expose (_testinternalcapi.get_co_localskinds is a dict, so a name that
    is both a local and a free variable shows once). Code objects are
    collected in the order marshal finishes them: after the code objects in
    their constants."""

    def __init__(self, data):
        self.data = data
        self.pos = 0
        self.refs = []
        self.codes = []

    def long(self):
        v = int.from_bytes(self.data[self.pos:self.pos + 4], "little", signed=True)
        self.pos += 4
        return v

    def raw(self, n):
        v = self.data[self.pos:self.pos + n]
        self.pos += n
        return v

    def obj(self):
        t = self.data[self.pos]
        self.pos += 1
        flag, t = t & 0x80, chr(t & 0x7F)
        if t == "r":
            return self.refs[self.long()]
        if flag:
            index = len(self.refs)
            self.refs.append(None)
        v = self.read(t)
        if flag:
            self.refs[index] = v
        return v

    def read(self, t):
        if t in "NFT.S":
            return t
        if t == "i":
            return self.long()
        if t == "l":
            n = self.long()
            return self.raw(2 * abs(n))
        if t == "g":
            return self.raw(8)
        if t == "y":
            return self.raw(16)
        if t == "s":
            return self.raw(self.long())
        if t in "uatA":
            return self.raw(self.long()).decode("utf-8", "surrogatepass")
        if t in "zZ":
            return self.raw(self.raw(1)[0]).decode("latin-1")
        if t == ")":
            return tuple(self.obj() for _ in range(self.raw(1)[0]))
        if t in "([<>":
            return tuple(self.obj() for _ in range(self.long()))
        if t == ":":
            return (self.obj(), self.obj(), self.obj())
        if t == "c":
            for _ in range(5):
                self.long()   # the counts, co_stacksize, co_flags
            self.obj()        # co_code
            self.obj()        # co_consts
            self.obj()        # co_names
            names = self.obj()
            kinds = self.obj()
            self.obj()        # co_filename
            self.obj()        # co_name
            self.obj()        # co_qualname
            self.long()       # co_firstlineno
            self.obj()        # co_linetable
            self.obj()        # co_exceptiontable
            v = list(zip(names, kinds))
            self.codes.append(v)
            return v
        raise ValueError("marshal type %r" % t)


def localsplus_all(top):
    """{id(code): [(name, kind), ...]} for top and the code objects in its
    constants, from marshal (see Marshal), or from get_co_localskinds where
    marshal can't nest that deep."""
    order = []   # code objects in marshal's order (post-order, each once)
    seen = set()
    stack = [(top, False)]
    while stack:
        co, done = stack.pop()
        if done:
            order.append(co)
            continue
        if id(co) in seen:
            continue
        seen.add(id(co))
        stack.append((co, True))
        stack.extend((c, False) for c in reversed(co.co_consts) if isinstance(c, CodeType))
    try:
        data = marshal.dumps(top)
    except ValueError:  # "object too deeply nested to marshal"
        return {id(co): [(co._varname_from_oparg(i), kind) for i, kind in
                         enumerate(_testinternalcapi.get_co_localskinds(co).values())]
                for co in order}
    limit = sys.getrecursionlimit()
    sys.setrecursionlimit(max(limit, 10000))
    try:
        reader = Marshal(data)
        reader.obj()
    finally:
        sys.setrecursionlimit(limit)
    assert len(reader.codes) == len(order)
    return {id(co): v for co, v in zip(order, reader.codes)}


def code(co, out):
    """The canonical form of co and the code objects in its constants, depth
    first (iteratively: lambdas nest deeper than Python's recursion limit)."""
    locals_ = localsplus_all(co)
    stack = [co]
    while stack:
        co = stack.pop()
        if co is None:
            out.append("#END\n")
            continue
        out.append("#CODE %s %s %d\n" % (string(co.co_name), string(co.co_qualname),
                                         co.co_firstlineno))
        out.append("#ARGS %d %d %d\n" % (co.co_argcount, co.co_posonlyargcount,
                                         co.co_kwonlyargcount))
        out.append("#STACKSIZE %d\n" % co.co_stacksize)
        out.append("#FLAGS %x\n" % co.co_flags)
        out.append("#FILENAME %s\n" % string(co.co_filename))
        for name in co.co_names:
            out.append("#NAME %s\n" % string(name))
        for name, kind in locals_[id(co)]:
            out.append("#LOCAL %s %02x\n" % (string(name), kind))
        for v in co.co_consts:
            out.append("#CONST " + constant(v) + "\n")
        raw = co.co_code
        positions = list(co.co_positions())
        for i in range(0, len(raw), 2):
            # A line table that ends early leaves the last code units without one.
            pos = positions[i // 2] if i // 2 < len(positions) else ("?",) * 4
            out.append("%s %d %s %s %s %s\n" % (opcode.opname[raw[i]], raw[i + 1], *pos))
        out.append("#LINETABLE %s\n" % co.co_linetable.hex())
        out.append("#EXCEPTIONTABLE %s\n" % co.co_exceptiontable.hex())
        for e in dis._parse_exception_table(co):
            out.append("#HANDLER %d %d %d %d %d\n" % (e.start, e.end, e.target, e.depth,
                                                      e.lasti))
        stack.append(None)
        stack.extend(reversed([c for c in co.co_consts if isinstance(c, CodeType)]))


ERRORS = (SyntaxError, ValueError, MemoryError, OverflowError, SystemError, RecursionError)


def cpython(path, mode, optimize, marshal_mode=False):
    """The canonical form of compile()'s code objects for path (after the #FILE line)."""
    src = pathlib.Path(path).read_bytes()
    out = []
    with warnings.catch_warnings(record=True) as ws:
        warnings.simplefilter("always")
        try:
            co = compile(src, "<unknown>", COMPILE_MODE[mode], dont_inherit=True,
                         optimize=optimize)
            try:
                if marshal_mode:
                    marshal.dumps(co)
                code(co, out)
            except ValueError:  # "object too deeply nested to marshal"
                out = ["#MARSHAL-ERROR\n"]
        except ERRORS as e:
            out = [error(e)]
    out += warning_lines(ws)
    return "".join(out).splitlines(keepends=True)


def loaded(java):
    """The Java output with a #MARSHAL line replaced by the canonical form of
    the code object marshal.loads makes of it."""
    if java and java[0].startswith("#MARSHAL "):
        out = []
        code(marshal.loads(bytes.fromhex(java[0][len("#MARSHAL "):].strip())), out)
        return "".join(out).splitlines(keepends=True) + java[1:]
    return java


def build():
    tests = ROOT / "tests/java/org/python/pegen"
    run(["javac", "-nowarn", "-cp", ROOT / "build/classes", "-d", OUT / "classes",
         tests / "CodeCompare.java", tests / "AstCompare.java"])


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--mode", choices=["file", "single", "eval"], default="file")
    ap.add_argument("--optimize", type=int, choices=[0, 1, 2], default=0)
    ap.add_argument("--marshal", action="store_true",
                    help="compare through Java's marshal.dumps and marshal.loads")
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
         "org.python.pegen.CodeCompare", "--mode", args.mode,
         "--optimize", args.optimize, *(["--marshal"] if args.marshal else []),
         file_list, java_out])

    files = same = 0
    differ = {"code": [], "error": [], "accept/reject": [], "warnings": [], "crash": []}
    expected = []
    fixed = []
    for name, java in blocks(java_out):
        files += 1
        java = loaded(java)
        python = cpython(name, args.mode, args.optimize, args.marshal)
        key = ("code", sha(name))
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
            print(f"{kind}: {name} (code {sha(name)}; line {i + 1} of its dump)")
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
