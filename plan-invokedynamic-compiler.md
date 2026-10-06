# The invokedynamic compiler: plan and status

Running the code the Python 3 compiler makes on the Jython 3 runtime: the
port of the parser and compiler (developed on the cpython-bytecode-compiler
branch; see plan-cpython-bytecode-compiler.md and plan-pegen-parser.md) to
`main`, on the `invokedynamic-compiler` branch, and the interpreter work
that follows. **Status** says where the work stands and what is next.

## Status (2026-10-05)

- **Planned, approved 2026-10-05:** the plan below.
- **Done: R0, the port** (steps below), 2026-10-05. Worktree
  `../jython-invokedynamic` on `invokedynamic-compiler`, from
  `origin/repl315` (aebca20a4; the branch tracks origin/repl315 until it
  is pushed); the cpython-bytecode-compiler tree stays at 3938c5439.
  `compiler:test` (48) and `core:test` (1454, 7 skipped) pass; smoke.sh
  passes (exit 0, about 35 minutes) with the old tree's known differences
  less `\N{RS}`, which now matches at every stage.
- **Next: R1, the REPL on the Java compiler**, to be detailed before work
  starts.

## R0, the port: steps

- [x] **0. Baseline.** `repl315`'s wrapper is Gradle 7.6, which can't run
  on JDK 21 (the only JDK on the Linux machine): moved to 8.14.3 (runs on
  17 and 21). `core:test` then passes as before: 1454 tests, 7 skipped.
  `-Pcpython` must be absolute (Exec resolves it from `core/`).
- [x] **1. Copy** (not move: the old tree keeps its record). Hand-written
  Java to `compiler/src/main/java/org/python/pegen/` (generated files left
  behind: GeneratedParser, TokenTypes, AstFactory, the ast/ node classes,
  ast/base/, the `*Type` enums, compile/Opcode, lexer/UnicodeTables); the
  generators to `build-tools/python/pegen/`; Java tests and drivers to
  `compiler/src/test/java/`; `tests/pegen/` (scripts, samples,
  compare_known.txt) to `compiler/src/test/pegen/`; the plans, GLOSSARY.md
  and docs/adr/ to the root. CLAUDE.md is rewritten for the new layout.
- [x] **2. Build.** `settings.gradle` includes `compiler`;
  `compiler/compiler.gradle`: `java-library`, JUnit 5, and three Exec
  tasks with declared inputs and outputs writing into
  `build/generated/sources/pegen/java/main` (added to `main`):
  `generateParser` (generate.py), `generateOpcodes` (generate_opcodes.py),
  `generateUnicode` (generate_unicode.py; also the name table, a resource
  in `build/generated/resources/pegen`). Properties `cpython` (executable;
  default `../cpython/python.exe`, then `../cpython/python`, then `python`)
  and `cpythonSource` (default `../cpython`), shared with `core` through
  the root project. The generators' default paths change to the new layout.
  Done: the generated files match the old checked-in ones but for their
  header comments; everything compiles at `--release 17`.
- [x] **3. JUnit 5:** the seven JUnit 4 test classes (imports, and the
  message argument moved from first to last). 48 tests pass, as before.
- [x] **4. Name table:** generate_unicode.py writes every name and alias
  `unicodedata.lookup` accepts for `\N{...}` (not named sequences);
  UnicodeNames reads it instead of `ucnhash`. The `\N{RS}` known
  difference goes. Done by decoding the packed DAWG of
  `Modules/unicodename_db.h` with `Tools/unicode/dawg.py` (Python's
  `unicodedata` can't list aliases): 34,594 names and 481 aliases, each
  checked with `unicodedata.lookup`. Case folding is ASCII only, as
  `Py_TOUPPER`. StringParserTest covers aliases, a named sequence and a
  dotless-i name.
- [x] **5. Comparisons:** the compare_*.py scripts and smoke.sh use the
  Gradle classes (`compiler/build/classes/java/{main,test}` and the
  generated resources) instead of compiling the drivers with javac, and
  build with `./gradlew compiler:testClasses` unless `--no-build`.
  smoke.sh's recognizer check runs generate.py from build-tools. Output
  goes to `compiler/build/pegen-*`. compare_ast.py holds the shared
  `CLASSPATH` and `build()` (`gradlew compiler:testClasses` with the
  running Python as `-Pcpython`).
- [x] **6. Pass:** `compiler:test` and smoke.sh pass as on the old tree
  (same known differences, less `\N{RS}`); then update CLAUDE.md and
  this Status.

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
