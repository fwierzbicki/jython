# The invokedynamic compiler: plan and status

Running the code the Python 3 compiler makes on the Jython 3 runtime: the
port of the parser and compiler (developed on the cpython-bytecode-compiler
branch; see plan-cpython-bytecode-compiler.md and plan-pegen-parser.md) to
`main`, on the `invokedynamic-compiler` branch, and the interpreter work
that follows. **Status** says where the work stands and what is next.

## Status (2026-10-05)

- **Planned, approved 2026-10-05:** the plan below. Nothing ported yet.
- **Next: R0, the port**, to be detailed before work starts.

## Plan: running code on the Jython 3 runtime

**Goal:** run the code this compiler makes on the Jython 3 runtime's
interpreter (`CPython315Frame`, on the `repl315` branch), first in its
REPL, then for more of the language as the interpreter grows. Decided
with the user, 2026-10-05: the compiler is ported to `main` now (an
amendment to ADR 0001, which deferred the port to the end of the
backend), rather than bridged through marshal files or given an
interpreter of its own here.

**Where things stand on `repl315`** (from `main`): `core` is the only
Gradle subproject (`settings.gradle` reserves a `compiler` slot). Its REPL
compiles each statement in a CPython 3.15 subprocess
(`ReplCompiler`, `repl_compiler.py`: `codeop.compile_command(src,
"<stdin>", "single")`, sent back as `marshal.dumps`), and
`org.python.modules.marshal.loads` makes the `CPython315Code`.
`CPython315Frame` runs module-level code: names, constants, operators,
calls, subscripts, conditional and unconditional jumps; not yet
functions, loops over iterators, exceptions, classes or imports. Nothing
in `core` uses an AST: the only one on `main` is Jython 2's ANTLR tree in
the old top-level `src/`, which the Gradle build doesn't include and this
leaves alone. `peg-parser-main` has an older copy of the parser as a
`parser` subproject; this supersedes it.

**Decisions** (approved by the user, 2026-10-05, with the branch name,
this file, and generated code not checked in):
- **Branch:** `invokedynamic-compiler`, a new branch from `repl315`,
  checked out as a second worktree (`../jython-invokedynamic`), so this
  tree stays usable. From then on the compiler is developed there; the
  cpython-bytecode-compiler branch stops at 0c5257070 plus the plan
  updates. This plan, plan-cpython-bytecode-compiler.md and
  plan-pegen-parser.md move with the code (the latter two as the record
  of how the parser and compiler were built, and for their working
  notes).
- **One subproject, `compiler`:** the packages stay as they are
  (`org.python.pegen`, `.lexer`, `.ast`, `.compile`), in
  `compiler/src/main/java`. Not a `parser` and a `compiler` subproject:
  `org.python.pegen` and `org.python.pegen.compile` refer to each other
  (`Parser.fromString` takes `Compile.PyCompilerFlags`). It depends on
  nothing in `core`; `core` depends on it (for the REPL).
- **Generated code is not checked in** (decided with the user,
  2026-10-05): it's generated at build time into the build's generated
  areas, as `core` does (`build/generated/sources/<name>/java/main`,
  added to the source set, `compileJava` depending on the task). The
  generators move to `build-tools/python/pegen/` (as on
  `peg-parser-main`) and become Gradle tasks with declared inputs and
  outputs: `generate.py` (GeneratedParser, TokenTypes, AstFactory, ast/),
  `generate_opcodes.py` (Opcode) and `generate_unicode.py` (UnicodeTables
  and the name table). So building `compiler` needs, like `core`, the
  CPython 3.15 executable (`-Pcpython`) and also its source tree (for
  `Grammar/`, `Parser/Python.asdl` and `pycore_opcode_metadata.h`; a
  property defaulting to `../cpython`). `core` finds the executable only
  as `../cpython/python.exe` (macOS); on Linux the build is
  `../cpython/python`, so the default should try both. (`core`'s own
  `Opcode315`, from `opcode_gen.py`, stays: two opcode tables for now,
  both generated from 3.15.)
- **`\N{...}` names:** `UnicodeNames` stops using Jython 2's `ucnhash`.
  `generate_unicode.py` also writes the name table (names and aliases)
  from CPython's `unicodedata`, so lookups match CPython exactly; that
  closes the `\N{RS}` known difference.
- **Tests:** the JUnit tests move to JUnit 5 (`main`'s convention). The
  comparisons (`compare_*.py`, `*Compare.java`, `smoke.sh`, the samples,
  `compare_known.txt`) move too, reading the Gradle build's classes, and
  must pass there before anything else changes.
- **The REPL compiles with Java:** `ReplCompiler` calls the Java compiler
  in-process, with `codeop.compile_command`'s rules for incomplete input
  (`PyCF_ALLOW_INCOMPLETE_INPUT`, `PyCF_DONT_IMPLY_DEDENT`, and the
  "incomplete input" SyntaxError), and turns the code object into a
  `CPython315Code` through `Marshal.dumps` and rt3's `marshal.loads`:
  the path the backend decisions chose, and already checked by
  `--marshal`. A direct conversion can come later if it's needed. The
  CPython subprocess stays available behind a switch, as the oracle.
- **The oracle for running code:** each example program is run by
  CPython and by rt3 (Java compiler, rt3 interpreter), and stdout plus
  the exception (type and message) compared, the way the compiler's
  comparisons work. It starts from `core/src/test/pythonExample/`.

**Phases** (each detailed before work starts, as D to F were):
- **R0, the port:** the worktree and branch, the `compiler` subproject,
  the generators as build tasks, JUnit 5, the comparisons and smoke.sh
  passing under Gradle, the name table.
- **R1, the REPL on the Java compiler:** `ReplCompiler` in-process,
  `ReplTest` passing, the run-and-compare oracle over what the
  interpreter runs today.
- **R2 onwards, the interpreter:** opcode groups in the order programs
  need them, each with example programs: functions and closures
  (`MAKE_FUNCTION`, `LOAD_FAST*`, cells and free variables), loops and
  comprehensions (`GET_ITER`, `FOR_ITER`), exceptions (the exception
  table, `PUSH_EXC_INFO`, `RAISE_VARARGS`, `with`), classes
  (`LOAD_BUILD_CLASS`, `__class__`, `super`), then imports and
  generators. How much of rt3's object model each needs is part of
  detailing it.
