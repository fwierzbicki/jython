# Python 3 compiler for Jython 3 (branch: invokedynamic-compiler)

## Goal
Run code compiled by our Java port of CPython's compiler on the Jython 3
runtime's interpreter (`CPython315Frame`), first in its REPL, then for more
of the language as the interpreter grows. The parser (a Java port of
CPython's pegen parser and tokenizer) and the compiler (future, preprocess,
symtable, codegen, flowgraph, assemble: CPython 3.15 code objects) were
built on the peg-parser and cpython-bytecode-compiler branches and ported
here, on top of `repl315`. Target: CPython v3.15.0 (../cpython).

## Resuming work
- plan-invokedynamic-compiler.md: this branch's plan. Its Status says
  where the work stands and what is next. Read it first; update Status and
  the checkboxes at each checkpoint.
- plan-cpython-bytecode-compiler.md, plan-pegen-parser.md: the record of
  how the compiler and the parser were built, with their working notes
  (decisions, conventions, traps). Their paths and commands are those of
  the old ant layout; the table below maps them.

## Ground rules
- The user makes the git commits: don't commit or stage (use mv, not git mv).
- Keep python.gram as close to upstream as possible. Actions are translated
  mechanically by the Java generator; complex helpers are hand-ported from
  Parser/action_helpers.c into a Java class with matching names.
- Generated Java is never hand-edited, and is not checked in: the Gradle
  build generates it.
- Hand-ported code keeps CPython's names (files, functions, variables).
- Correctness oracle: CPython itself. The compare_*.py scripts diff our
  tokens, ASTs, symbol tables, instruction sequences and code objects
  against CPython's over a source corpus.

## Layout
- `core/`: the Jython 3 runtime (interpreter, objects, REPL), from `repl315`.
- `compiler/`: the parser and compiler, one Gradle sub-project, depending
  on nothing in `core`. Sources in `compiler/src/main/java/org/python/pegen/`:
  - `Parser.java`, `Token.java`, `TokenSource.java`, `PythonSyntaxError.java`,
    `ActionHelpers.java` (pegen.c, action_helpers.c, pegen_errors.c),
    `StringParser.java` (string_parser.c), `UnicodeNames.java` (`\N{...}`),
    `LargeStack.java` (runs the stages on a 16 MB stack).
  - `ast/`: hand-written AST support (`AST`, `Located`, `AstValueError`) and
    Constant value classes (`Complex`, `Bytes`, `Singleton`; str=String,
    int=BigInteger, float=Double).
  - `lexer/`: the tokenizer (lexer.c, state.c, helpers.c, string_tokenizer.c).
  - `compile/`: the compiler: Compile, Future, AstPreprocess, Symtable,
    Errors, Ast, Codegen, InstructionSequence, AstUnparse, OpcodeUtils,
    Flowgraph, Abstract, LibM, Assemble, PyCodeObject, Marshal (the writer),
    PyTuple/PyFrozenSet/PySlice, Repr, SourceLocation.
- Generated into `compiler/build/generated/sources/pegen/java/main` by
  Gradle tasks running the generators in `build-tools/python/pegen/` with
  the CPython executable:
  - `generateParser` (generate.py: java_generator.py, action_translator.py,
    action_overrides.py, java_types.py, asdl_java.py): GeneratedParser,
    TokenTypes, AstFactory, the ast/ node classes, ast/base/, the `*Type`
    enums.
  - `generateOpcodes` (generate_opcodes.py): compile/Opcode.
  - `generateUnicode` (generate_unicode.py): lexer/UnicodeTables, and
    `unicode_names.txt` (the `\N{...}` names and aliases, from
    unicodename_db.h) into `build/generated/resources/pegen`.
- `compiler/src/test/java/org/python/pegen/`: JUnit 5 tests (`*Test`) and
  the comparison drivers (`*Compare`, `RecognizerSmoke`).
- `compiler/src/test/pegen/`: the comparisons and their corpora:
  compare_ast.py, compare_symtable.py, compare_tokens.py (with
  dump_tokens.py), compare_codegen.py, compare_flowgraph.py, compare_code.py
  (`--marshal` loads Java's marshal output); compare_known.txt (expected
  differences); smoke.sh (all of them); extract_samples.py (the syntax-error
  corpus); test_action_translator.py; the samples: accept/, reject/,
  single/, symtable/, deep/ (nesting around MAXSTACK, on a 256 KB stack),
  pending/ (known gaps, reported but not failing).
- GLOSSARY.md: canonical terms (CPython's); docs/adr/: architecture decisions.

Old ant layout to new: `src/org/python/pegen/` → `compiler/src/main/java/org/python/pegen/`;
`src/pegen/tools/` → `build-tools/python/pegen/`; `tests/java/org/python/pegen/` →
`compiler/src/test/java/org/python/pegen/`; `tests/pegen/` → `compiler/src/test/pegen/`;
`build/classes` → `compiler/build/classes/java/main` (+ `resources/main`).

## Key upstream files
- ../cpython/Tools/peg_generator/pegen/ (generator; c_generator.py)
- ../cpython/Grammar/python.gram, Grammar/Tokens, Parser/Python.asdl
- ../cpython/Parser/lexer/, Parser/action_helpers.c, Python/compile.c,
  Python/codegen.c, Python/flowgraph.c, Python/assemble.c

## Build and test
The build needs JDK 17 or later (Gradle 8.14) and CPython 3.15: its
executable (`-Pcpython=PATH`, default ../cpython/python.exe, then
../cpython/python, then `python`) and its source tree
(`-PcpythonSource=DIR`, default ../cpython).

    ./gradlew compiler:test        # generate, build, JUnit tests
    ./gradlew core:test            # the runtime's tests
    ./gradlew -q --console=plain core:repl

## Compare with CPython
The scripts build with Gradle first (`--no-build` skips it); run them with
the 3.15 build:

    ../cpython/python compiler/src/test/pegen/compare_ast.py [--mode single] [--optimize N] PATH...

## Smoke test
    compiler/src/test/pegen/smoke.sh [--skip-stages]

`--skip-stages` leaves out the codegen and flowgraph comparisons. Uses
../cpython/python.exe or ../cpython/python if built, else set
PYTHON=/path/to/python3.15.
