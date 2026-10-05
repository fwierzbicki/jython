# CPython bytecode compiler for Jython: plan and status

The work of the cpython-bytecode-compiler branch, which builds on the parser
from the peg-parser branch: the stages CPython runs after parsing (future,
preprocess, symtable), then a backend that compiles to CPython bytecode.
**Status** says where the work stands and what is next. **Current plan** is
the approved plan with progress ticked off. **Working notes** has what's
specific to this work. The parser it builds on, and the commands,
conventions and traps shared with it, are in plan-pegen-parser.md.

## Status (2026-10-04)

- **The parser is done** (plan-pegen-parser.md): its output matches
  CPython's `ast.parse()` over Lib and the error corpus, upstream v3.15.0rc2.
- **The compiler front end is done** (see **Current plan**): Phases A
  (driver and future), B (preprocess) and C (symtable), in
  `org.python.pegen.compile`. `Compile.new_compiler` runs all three, as
  CPython's `compiler_setup` does.
- **The Java tokenizer is done** (plan-pegen-parser.md, "Completed: the
  Java tokenizer"): `Parser.fromString` parses source bytes, and every
  comparison runs from source.
- **Next: the backend** (codegen, flowgraph, assemble), outlined below in
  **Next plan**. The questions about it were settled with the user on
  2026-10-04 (see **Decisions for the backend**). Phase D (codegen) is
  done (**Phase D plan**); next is Phase E, flowgraph, to be detailed
  before work starts. One question for the user is open: lone surrogates in
  str constants (see Phase D results).
- **Checks passing:** `ant compile`, `tests/pegen/smoke.sh` (exit 0, about 13
  minutes) and the pegen JUnit tests (41 tests, `FutureTest`,
  `AstPreprocessTest`, `SymtableTest` and `TokenizerTest` included);
  commands in plan-pegen-parser.md, Working notes.
- **Committed:** Phase A (and the plan split) in c5e31fdb9, Phase B in
  d91441d6a, Phase C in ad571bf9a. Check `git status` for anything newer.

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
- **Jython 2 is not changed:** `org.python.compiler`, the ANTLR parser and
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

### Phase B: preprocess (done)
- [x] `AstPreprocess.java` from ast_preprocess.c, for `optimize` 0, 1 and 2.
      Folded constants use the existing AST value classes.
- [x] `compare_ast.py --optimize N` against `ast.parse(..., optimize=N)`,
      warnings included, for N = 0, 1 and 2, run by smoke.sh.
- [x] Samples aimed at preprocess: `%`-formats, `finally` control flow,
      match patterns, `__debug__`, asserts, docstrings.

Results:
- `AstPreprocess._PyAST_Preprocess` runs from both `compiler_setup` (warnings
  on, full folding) and `_PyCompile_AstPreprocess` (no warnings; folding only
  with `PyCF_OPTIMIZED_AST`, as pythonrun.c decides). Lib, the samples and the
  corpus match CPython at all three levels.
- **Nodes are replaced, not changed in place.** C turns a node into another
  kind (`make_const`, `COPY_NODE`); a Java node's class is its kind, so
  `astfold_expr` and the folds return the node to store in the parent's
  field or list slot.
- **PEP 765 warnings** are issued only when compiling to code
  (`enable_warnings` is 0 for `PyCF_ONLY_AST`), so `ast.parse()` never shows
  them. compare_ast.py compares them separately as `#COMPILE-WARNING` lines:
  CPython's from a full `compile()`, filtered to preprocess's messages
  (codegen's warnings aren't ported); the Java side's from `new_compiler` on
  a second parse. Only one file in Lib has one, so the samples carry them.
- Compiler-stage warnings go through `Errors._PyErr_EmitSyntaxWarning` to a
  `Parser.WarningHandler` passed to `new_compiler` (with `module`, as in C).
  A handler that returns false (the "error" action) gets a SyntaxError.
- `fold_const_match_patterns`'s arithmetic (`PyNumber_Negative`, `_Add`,
  `_Subtract`) is ported for int, float and complex with CPython 3.14+'s
  mixed-mode complex rules (`real - complex` negates the imaginary part,
  zero included). An int too large for a float isn't folded (C's
  OverflowError, cleared by `make_const`).
- **Stack:** preprocess recurses as deep as the tree, and on a 1 MB stack
  the deepest tree the parser accepts can overflow. `LargeStack` (pulled out
  of `Parser.runParser`) now runs preprocess on the parser's 16 MB-stack
  thread pool too; Phase C's symtable should do the same. A
  StackOverflowError beyond that becomes a RecursionError ("Stack overflow
  during compilation", without C's kB figure). `RecognizerSmoke` now also
  preprocesses (at optimize 1), and smoke.sh's small-stack checks use 256 KB,
  where running preprocess on the caller's stack fails every time.
- Samples: `accept/preprocess_*.py` (format, format with postponed
  annotations, debug, match, docstring, finally) and
  `single/accept/preprocess_interactive*.py`. Mutation checks (breaking the
  port on purpose) showed each fold is covered; the one not caught,
  `_Py_rc_sum` dropping a -0.0 imaginary part, can't be reached from source.
- `tests/java/org/python/pegen/compile/AstPreprocessTest.java`: what the
  comparison can't see: where warnings go, a warning made an error (offsets
  and text), RecursionError, and the number edge cases.

### Phase C: symtable (done)
- [x] `Symtable.java` from symtable.c, run on the preprocessed tree.
- [x] `compare_symtable.py` + `SymtableCompare.java`: diff the block tree
      and each symbol's raw `DEF_*` bits (through `_symtable`, not the
      friendly `symtable` API) over Lib, the samples and the error corpus.
- [x] A symtable error corpus. smoke.sh runs the comparison.

Results:
- `Symtable._PySymtable_Build` runs at the end of `compiler_setup`, after
  preprocess (`Compile.c_st`). `_symtable.symtable()`, the comparison's
  CPython side, builds from the tree as parsed instead
  (`_Py_SymtableStringObjectFlags`: future, then symtable, no preprocess),
  and `Symtable._Py_SymtableStringObjectFlags` does the same for
  `SymtableCompare` (taking a parsed tree: no tokenizer yet). The two differ
  only where preprocess changes names (a `__debug__` load becomes a
  constant); `SymtableTest` checks that.
- Compared per block: type, name, lineno, nested, symbols (raw flags, scope
  bits included; sorted, since C adds free names in set order, which follows
  str hashes) and varnames, children in order. Lib, the samples, single
  input and the error corpus match from the first run.
- **What `_symtable` doesn't show** (ste_generator, ste_coroutine,
  ste_needs_class_closure, ste_needs_classdict,
  ste_has_conditional_annotations, ste_has_docstring, ste_method,
  ste_comp_inlined, an inlined comprehension's own symbols, the block keys)
  is checked by `tests/java/org/python/pegen/compile/SymtableTest.java`,
  with expected values taken from CPython's code objects (co_flags,
  co_cellvars). Codegen, when it comes, checks them all through `dis`.
- **Block keys.** C keys `st_blocks` by AST node address, and twice by
  address + 1 (an AnnAssign's annotation block: the enclosing block's
  `ste_id` + 1; a TypeVar's default: the TypeVar + 1). Java uses
  `Symtable.BlockKey(node, offset)`, compared by node identity; codegen
  looks blocks up with the same keys.
- Samples: `tests/pegen/symtable/reject/` (71 files, one error each: every
  symtable.c message, with mangled, multi-line and non-ASCII locations) and
  `symtable/accept/` (class scopes, comprehensions, type parameters,
  annotations with and without `from __future__ import annotations`,
  global/nonlocal, mangling). The error corpus from CPython's tests had only
  the `__debug__`, global/nonlocal and type-parameter errors. Mutation
  checks: 20 mutations of the port, 18 caught by the comparison, the other
  two by `SymtableTest` (an inlined comprehension's symbols) or not
  observable (`update_symbols`' `bound` test, which no tree reaches
  differently).
- **Stack:** symtable runs on `LargeStack` like preprocess; a
  StackOverflowError becomes the same RecursionError. `RecognizerSmoke` now
  also builds the symbol table (any error but a SyntaxError fails it), and
  smoke.sh runs it on 256 KB over `symtable/accept/` too.

### Verification
- smoke.sh passes, with the new comparisons in it, at the end of every
  phase. `compare_known.txt` lists any expected differences.

## Decisions for the backend (made with the user, 2026-10-04)

- **This is a learning spike** (ADR 0001, amended). Whether the work moves to
  `main`, and in what form, is decided at the end.
- **It's built here, on the Jython 2 tree,** although the interpreter (the
  Jython 3 runtime's, on `main`) lives there. It's ported **once, at the
  end, and only after checking with the user.** `peg-parser-main` and
  `repl315` stay as they are until then.
- **Order:** the Java tokenizer first, then codegen.
- **The backend stops at the code object.** Running code is out of scope for
  this pass, and the user may revisit that once the code object is done.
- **The code object is a plain Java value** in `org.python.pegen.compile`,
  with `PyCodeObject`'s fields. It is neither rt3's `CPython315Code` nor
  Jython 2's `PyCode`. Its constants are the AST value classes (String,
  BigInteger, Double, Complex, Bytes, Singleton) plus tuple, frozenset and
  nested code objects.
- **The oracle works stage by stage,** through `_testinternalcapi`:
  `compiler_codegen` (the instruction sequence), `optimize_cfg` (flowgraph)
  and `assemble_code_object`. Each stage is a phase, compared over the whole
  corpus.
- **The end-to-end check is marshal output:** a `marshal.dumps` writer for the
  code object, diffed against CPython's `marshal.dumps(compile(...))`. That's
  also how the code would reach rt3, which already reads marshal
  (`repl315`). Still to check: `FLAG_REF` depends on CPython refcounts, so
  the diff may need normalizing, or a `marshal.loads` round-trip instead.
- **Rigor:** the whole-corpus comparisons pass at every checkpoint, as
  before. Mutation checks are optional spot-checks.
- **Names:** see `GLOSSARY.md`, which now has Jython 2, Jython 3 runtime,
  Interpreter and Code object. "Core Jython" is no longer used.

## Next plan: the backend (outline, to be detailed before work starts)

- **Phase D, codegen:** `codegen.c` (about 6.5k lines) and the rest of
  `compile.c` (compiler units, the const cache), which produce the
  instruction sequence. Compared with `compiler_codegen`. Detailed below
  (**Phase D plan**, approved 2026-10-04).
- **Phase E, flowgraph:** `flowgraph.c` (about 4.3k lines). Compared with
  `optimize_cfg`.
- **Phase F, assemble:** `assemble.c` (about 0.8k lines), which produces the
  code object. Compared with `assemble_code_object`, then end to end through
  marshal.

## Phase D plan: codegen (approved 2026-10-04, done 2026-10-05)

**Goal:** port Python/codegen.c (6,666 lines), the rest of Python/compile.c
(1,807 lines, of which the front half is done) and
Python/instruction_sequence.c (490 lines), so that for any source the Java
compiler produces the instruction sequence CPython's codegen does: same
opcodes, opargs, locations and constants, for the module and every nested
unit, plus codegen's SyntaxErrors and SyntaxWarnings.

**The oracle** (checked on 3.15.0rc2):
`_testinternalcapi.compiler_codegen(tree, filename, optimize, compile_mode)`
runs preprocess, symtable and codegen on an AST (compile_mode 0 Module,
1 Expression, 2 Interactive), adds the final return and applies the label
map. It returns the instruction sequence and the top unit's metadata:
argcount, posonlyargcount, kwonlyargcount and `consts` in index order.
- `get_instructions()` gives `(opcode, oparg, lineno, end_lineno, col,
  end_col)` per instruction. oparg is None for opcodes without an argument,
  jump targets are instruction indices (labels resolved), and pseudo-ops
  are opcodes 256 and up (`ANNOTATIONS_PLACEHOLDER`, `JUMP`,
  `SETUP_FINALLY`, `LOAD_CLOSURE`, ...).
- `get_nested()` gives each nested unit's sequence (functions, lambdas,
  classes, comprehensions, `__annotate__`), recursively, as codegen left it,
  final return included.
- **Limit:** only the top unit's `consts` are exposed. A nested unit's
  `LOAD_CONST` opargs can be compared as indices, not values, as CPython's
  own test_compiler_codegen does. Phases E and F see every value (through
  `co_consts` and marshal), so a wrong nested constant can't survive past F.
- **Code objects as constants:** the top unit's `consts` hold the nested
  units' code objects, which come from flowgraph and assemble (Phases E and
  F). Until then, Java's `_PyCompile_OptimizeAndAssemble` returns a
  placeholder code object carrying `co_name`, `co_qualname` and
  `co_firstlineno` and its unit's sequence. The canonical form writes a code
  constant as just those three fields, and compares its contents through
  the nested sequences.

**Decisions** (approved by the user, 2026-10-04):
- **Files** in `org.python.pegen.compile`, C names and order kept:
  `Codegen.java` (codegen.c), `InstructionSequence.java`
  (instruction_sequence.c), and `Compile.java` grows the rest of compile.c
  (compiler units, scopes, `_PyCompile_AddConst` and the const cache,
  `_PyCompile_ResolveNameop`, fblocks, `_PyCompile_CodeGen`).
- **Opcodes are generated** by a new `src/pegen/tools/generate_opcodes.py`,
  run on the 3.15 build like generate_unicode.py, into `Opcode.java`:
  `opcode.opmap` (pseudo-ops included), the `_opcode.has_*` flags, the
  intrinsic and `NB_*` tables (`_opcode.get_intrinsic1_descs`,
  `get_nb_ops`, ...). Stack effects wait for Phase E.
- **Constants and the const cache:** constants are the AST value classes
  plus a tuple and a frozenset class and the code object. The cache key
  follows `_PyCode_ConstantKey`: the type is part of the key (`1`, `1.0`
  and `True` are different constants, and so are `0.0` and `-0.0`), and
  tuples and frozensets are keyed by their items' keys.
- **Not yet ported means "unsupported", not a failure.** Until codegen is
  complete, a construct not yet ported throws an `Unsupported` exception.
  The comparison counts those files separately (like pending/) and fails
  only on real differences, and each checkpoint brings the count down. It
  must reach 0 at the end of D.
- **New comparison:** `tests/pegen/compare_codegen.py` and
  `CodegenCompare.java`, the same pattern as compare_symtable.py. Per unit,
  depth first: name, the metadata ints, the constants (top unit), then one
  line per instruction (opcode *name*, oparg, location), then the nested
  units. Errors and warnings as in compare_ast.py. Run by smoke.sh over Lib,
  the samples and the corpus, at optimize 0 and 1 (asserts and `__debug__`)
  and 2, in exec, single and eval modes.

### D0–D3 (done, 2026-10-05)

All of codegen.c went in at once, in C's order, as the tokenizer's lexer.c
did: the expression visitor reaches every construct, so a subset wasn't a
useful checkpoint. The checkpoints below are what the comparison shows.

- [x] D0: `Opcode.java` (generated by `src/pegen/tools/generate_opcodes.py`,
      run on the 3.15 build), `OpcodeUtils.java` (pycore_opcode_utils.h),
      `InstructionSequence.java` (instruction_sequence.c), compile.c's
      units, scopes, const cache, name resolution and fblocks in
      `Compile.java`, `PyCodeObject.java` (the placeholder and
      `_PyCode_ConstantKey`), `PyTuple`, `PyFrozenSet`, `PySlice`,
      `Repr.java` (C's %R and tp_name), `compare_codegen.py` and
      `CodegenCompare.java` in smoke.sh.
- [x] D1: statements and expressions, `try` / `with` / fblocks.
- [x] D2: functions, classes, annotations (with `AstUnparse.java`, a port
      of ast_unparse.c, for `from __future__ import annotations`), type
      parameters.
- [x] D3: comprehensions, `match`, single and eval input, codegen's errors
      and warnings (compared by compare_codegen.py, which records the
      warnings compiler_codegen issues; compare_ast.py's #COMPILE-WARNING
      lines stay preprocess-only).
- [x] No construct is unsupported; smoke.sh passes.

Results:
- **Comparison:** Lib at optimize 0, 1 and 2 (2,025 files each), the
  samples with `deep/`, and the error corpus in file, single and eval
  modes (about 37,300 files each): all identical, apart from 8 entries in
  `compare_known.txt` (mode `codegen`): `\N{RS}`, CPython's C-stack
  RecursionError on `deep/accept/deep_lambda.py`, and six files below.
- **Lone surrogates:** Python's `'\ud801\udca0'` (two lone surrogates) and
  `'\U000104A0'` are different strings, but the same Java String, so the
  Java const cache merges them (6 Lib test files). The AST comparison
  can't see it: both sides write strings as UTF-16 code units. **To decide
  with the user:** how str constants represent lone surrogates; this will
  matter again for marshal (Phase F) and the runtime.
- **Code object equality:** the const cache keys a code object by itself,
  but compares by value (`code_richcompare`), so the generator expression
  `all()` / `any()` / `tuple()` / `list()` / `set()` compile twice is one
  constant. The placeholder compares the unit's name, argument counts,
  flags, first line, instructions, constant keys and names.
- **CPython bug (not reported yet):** freeing an instruction sequence from
  `compiler_codegen` whose module has annotations decrefs a list twice
  (`PyInstructionSequence_Fini` doesn't clear `s_nested`, and runs twice on
  `s_annotations_code`): it corrupts the heap, or aborts a debug build.
  compare_codegen.py keeps the results alive and leaves with `os._exit`.
- **Invisible to the oracle:** module-level annotation code
  (`s_annotations_code`, spliced in by flowgraph) and nested units'
  constant values; Phases E and F see both.
- **Errors:** a thrown SyntaxError aborts the compile, so C's
  `*_IN_SCOPE` cleanups aren't needed; codegen runs on LargeStack, a
  StackOverflowError becoming CPython's RecursionError.

**Not in D:** flowgraph (E: stack depth, jump threading, dead code, const
folding of sequences, `co_consts` order), assemble (F: the exception
table, line tables, the real code object, marshal).

## Working notes (for a new session)

### Traps already hit
- **Compiler-stage errors** are thrown as `PythonSyntaxError`, where C sets
  the exception and returns 0. Their offsets are passed through unconverted
  (1-based UTF-8 byte offsets, as in C's `PyErr_RangedSyntaxLocationObject`),
  and `text` comes from reading `filename`, so it's None for `<unknown>`.
  That is a CPython bug (SyntaxError offsets are characters; the parser and,
  since gh-156894, the tokenizer convert), seen on 3.15.0rc2 and not yet
  reported upstream as of 2026-10-04. Keep matching CPython; if it's fixed
  there, follow the fix in Symtable, AstPreprocess and Errors.
  `Errors.PyErr_ProgramTextObject` decodes the line with the file's coding
  cookie (`Tokenizer.findEncoding`), as C does.
- **`PyUnicode_FromFormat`'s `%.100s`** decodes statefully: a character cut
  at the 100th byte is dropped, not replaced with U+FFFD.
- **The parameter `mod`** obscures the type `mod`, so `mod.Kind.Module`
  doesn't compile where a variable `mod` is in scope; use `instanceof`.
- **Warnings from the parser and preprocess** are issued on a LargeStack
  thread (see "Stack depth" in plan-pegen-parser.md), so a warning handler
  can't use thread-locals such as Jython's ThreadState.
- **ant's javac skips a source restored with `cp`** after it was compiled
  from a changed copy (it judges by timestamps), so the classes keep the
  changed code. When changing a file temporarily (e.g. mutation checks),
  delete its .class files before rebuilding.
- **A sample that doesn't parse** is compared as an error on both sides and
  silently matches. Check new `accept/` samples with `ast.parse()` first
  (and symtable samples with `_symtable.symtable()`: a reject sample must
  fail there, not in the parser).
- **`ast.parse()` accepts what symtable rejects** (`__debug__` assignment,
  a late `from __future__` inside a block): `accept/` means accepted by
  `ast.parse()`, so `RecognizerSmoke` lets symtable's SyntaxErrors through.
