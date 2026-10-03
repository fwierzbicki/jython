# CPython bytecode compiler for Jython: plan and status

The work of the cpython-bytecode-compiler branch, which builds on the parser
from the peg-parser branch: the stages CPython runs after parsing (future,
preprocess, symtable), then a backend that compiles to CPython bytecode.
**Status** says where the work stands and what is next. **Current plan** is
the approved plan with progress ticked off. **Working notes** has what's
specific to this work. The parser it builds on, and the commands,
conventions and traps shared with it, are in plan-pegen-parser.md.

## Status (2026-10-03)

- **The parser is done** (plan-pegen-parser.md): its output matches
  CPython's `ast.parse()` over Lib and the error corpus, upstream v3.15.0rc2.
- **In progress: the compiler front end** (see **Current plan**). Phase A
  (driver and future) is done: `org.python.pegen.compile`. **Next: Phase B,**
  preprocess.
- **Checks passing:** `ant compile`, `tests/pegen/smoke.sh` (exit 0) and the
  pegen JUnit tests (21 tests, `FutureTest` included); commands in
  plan-pegen-parser.md, Working notes.
- **What's left after this plan:** the Java tokenizer (plan-pegen-parser.md),
  then **backends** (codegen onwards). See ADR 0001 and the open questions
  below.
- **Committed:** Phase A (and the plan split) in c5e31fdb9. Check `git status`
  for anything newer.

## Current plan: the compiler front end

**Goal:** port the stages CPython runs between parsing and codegen
(Python/compile.c, `compiler_setup`): future, then preprocess, then symtable.
Each is checked against CPython the way the parser is. Terms are in
`GLOSSARY.md`, and the backend decision is in
`docs/adr/0001-cpython-bytecode-as-first-backend.md`.

**Decisions** (made with the user, 2026-10-03):
- **One front end, several backends.** CPython bytecode is the first
  backend, not the only one. Nothing in future, preprocess or symtable is
  specialized for it.
- **Core Jython is not changed:** `org.python.compiler`, the ANTLR parser and
  the runtime. `ScopesCompiler` is neither reused nor adapted.
- **Port rules:** hand-ported into `org.python.pegen.compile`, with C
  function, field and flag names kept, as with `ActionHelpers`.
- **Order:** CPython's: future, then preprocess, then symtable. Each stage is
  a phase with a checkpoint, and smoke.sh passes at every one.
- **Input:** token dumps from CPython, as now. The Java tokenizer comes after
  this plan.
- **No codegen in this plan.** When it comes, the bytecode is CPython 3.15's
  exact format, unspecialized, so it can be diffed against `dis`.

**What's being ported:**

| Source | Size | Contents |
|---|---|---|
| `Python/future.c` | ~120 lines | `from __future__` features and their errors |
| `Python/ast_preprocess.c` | ~1000 lines | `optimize` levels, PEP 765 `finally` warnings, `%`-format and match-pattern folding, `-OO` docstrings |
| `Python/symtable.c` | ~3370 lines | Blocks, `DEF_*` flags, scopes, symtable errors |
| `Python/compile.c` | front half of setup | The driver |

### Phase A: driver and future (done)
- [x] `org.python.pegen.compile.Compile`: the front half of compile.c's
      setup (flags, optimize level, future). Grow it phase by phase, and leave
      out what needs codegen (compiler units, const cache).
- [x] `Future.java` from future.c.
- [x] Future errors join the error corpus (`extract_samples.py`).

Results:
- `compile(..., PyCF_ONLY_AST)`, which is what compare_ast.py's CPython side
  calls, already runs `_PyCompile_AstPreprocess` after parsing: future, then
  `_PyAST_Preprocess` with `syntax_check_only` (pythonrun.c). So
  `AstCompare` now calls `Compile._PyCompile_AstPreprocess` after the parser,
  and every later stage that `ast.parse()` runs shows up in the existing
  comparison. Phase B's `--optimize N` adds `PyCF_OPTIMIZED_AST`.
- The future errors were already in the corpus (test_syntax doctests and
  test strings); their four `compare_known.txt` entries are gone. New
  samples: `accept/future_*.py` (features, not first, relative
  `.__future__`), `reject/future_*.py` (braces after a docstring, a
  non-ASCII name, `%.100s` cutting a 2- and a 4-byte character) and
  `single/reject/future_braces.py`.
- `tests/java/org/python/pegen/compile/FutureTest.java` checks what the
  comparison can't see: `ff_features`, `ff_location`, the flags
  `compiler_setup` merges, and `SyntaxError.text` read from a real file.
- Other files: `Errors.java` (errors.c: `PyErr_RangedSyntaxLocationObject`,
  `PyErr_ProgramTextObject`), `Ast.java` (ast.c: `_PyAST_GetDocString`),
  `SourceLocation.java` (`_Py_SourceLocation`).

### Phase B: preprocess
- [ ] `AstPreprocess.java` from ast_preprocess.c, for `optimize` 0, 1 and 2.
      Folded constants use the existing AST value classes.
- [ ] `compare_ast.py --optimize N` against `ast.parse(..., optimize=N)`,
      warnings included, for N = 0, 1 and 2, run by smoke.sh.
- [ ] Samples aimed at preprocess: `%`-formats, `finally` control flow,
      match patterns, `__debug__`, asserts, docstrings.

### Phase C: symtable
- [ ] `Symtable.java` from symtable.c, run on the preprocessed tree.
- [ ] `compare_symtable.py` + `SymtableCompare.java`: diff the block tree
      and each symbol's raw `DEF_*` bits (through `_symtable`, not the
      friendly `symtable` API) over Lib, the samples and the error corpus.
- [ ] A symtable error corpus. smoke.sh runs the comparison.

### Verification
- smoke.sh passes, with the new comparisons in it, at the end of every
  phase. `compare_known.txt` lists any expected differences.

### Open questions (decide before codegen starts)
- Where the interpreter comes from: written fresh from ceval.c/bytecodes.c,
  adopted from Jeff Allen's rt3, or something else.
- The runtime object model: Jython 2's `PyObject` or a new one.
- What a code object's constants are made of.

## Working notes (for a new session)

### Traps already hit
- **Compiler-stage errors** are thrown as `PythonSyntaxError`, where C sets
  the exception and returns 0. Their offsets are passed through unconverted
  (1-based UTF-8 byte offsets, as in C's `PyErr_RangedSyntaxLocationObject`),
  and `text` comes from reading `filename`, so it's None for `<unknown>`.
  `Errors.PyErr_ProgramTextObject` assumes UTF-8 until the Java tokenizer
  can find a coding cookie.
- **`PyUnicode_FromFormat`'s `%.100s`** decodes statefully: a character cut
  at the 100th byte is dropped, not replaced with U+FFFD.
- **The parameter `mod`** obscures the type `mod`, so `mod.Kind.Module`
  doesn't compile where a variable `mod` is in scope; use `instanceof`.
- **Warnings from the parser** are issued on the parser's own thread (see
  "Stack depth" in plan-pegen-parser.md), so a warning handler can't use
  thread-locals such as Jython's ThreadState.
