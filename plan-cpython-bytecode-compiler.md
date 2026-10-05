# CPython bytecode compiler for Jython: plan and status

The work of the cpython-bytecode-compiler branch, which builds on the parser
from the peg-parser branch: the stages CPython runs after parsing (future,
preprocess, symtable), then a backend that compiles to CPython bytecode.
**Status** says where the work stands and what is next. **Current plan** is
the approved plan with progress ticked off. **Working notes** has what's
specific to this work. The parser it builds on, and the commands,
conventions and traps shared with it, are in plan-pegen-parser.md.

## Status (2026-10-05)

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
  done (**Phase D plan**), and so is Phase E, flowgraph (**Phase E
  plan**). Next is Phase F, assemble, to be detailed before work starts.
- **Checks passing:** `ant compile`, `tests/pegen/smoke.sh` (exit 0, about 17
  minutes) and the pegen JUnit tests (48 tests, `FutureTest`,
  `AstPreprocessTest`, `SymtableTest`, `TokenizerTest` and `FlowgraphTest`
  included);
  commands in plan-pegen-parser.md, Working notes.
- **Committed:** Phase A (and the plan split) in c5e31fdb9, Phase B in
  d91441d6a, Phase C in ad571bf9a, the tokenizer in bb17abeb6, Phase D
  (codegen) in 5623f71c7. Check `git status` for anything newer.

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
  `optimize_cfg`. Detailed below (**Phase E plan**).
- **Phase F, assemble:** `assemble.c` (about 0.8k lines), which produces the
  code object. Compared with `assemble_code_object`, then end to end through
  marshal. Also to consider (from Phase E): comparing `compile()`'s code
  objects directly (decoded `co_code`, `co_consts`, `co_stacksize`, the
  exception table, positions), which covers the real path of flowgraph,
  including the units optimize_cfg can't run (see Phase E results).

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
  modes (about 37,300 files each): all identical, apart from 7 entries in
  `compare_known.txt` (mode `codegen`): `\N{RS}` and six files below. (An
  eighth, a RecursionError on `deep/accept/deep_lambda.py` put down to
  CPython's C stack, turned out in Phase E to come from compare_codegen.py's
  own recursive walk of the nested units; it now walks them iteratively,
  and the file matches.)
- **Lone surrogates:** Python's `'\ud801\udca0'` (two lone surrogates) and
  `'\U000104A0'` are different strings, but the same Java String, so the
  Java const cache merges them (6 Lib test files). The AST comparison
  can't see it: both sides write strings as UTF-16 code units. **To decide
  with the user:** how str constants represent lone surrogates; this will
  matter again for marshal (Phase F) and the runtime. **Decided
  (2026-10-05):** deferred. str constants stay `java.lang.String`, the
  files stay in `compare_known.txt`, and the representation is settled
  when the work is ported to `main`, where rt3's str type decides it.
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

## Phase E plan: flowgraph (approved 2026-10-05, done 2026-10-05)

**Goal:** port Python/flowgraph.c (4,255 lines), so that for any source the
Java compiler turns each unit's instruction sequence into the optimized
one CPython's flowgraph does: same opcodes, opargs, locations and final
constants, for the module and every nested unit.

**The oracle** (checked on 3.15.0rc2):
`_testinternalcapi.optimize_cfg(seq, consts, nlocals)` is
`_PyCompile_OptimizeCfg`: it builds the CFG from a codegen sequence
(splicing in the module's annotation code), runs `_PyCfg_OptimizeCodeUnit`
with `nparams = 0` and `firstlineno = 1`, then `calculate_stackdepth`
(result discarded) and `optimize_load_fast`, and returns the sequence.
It works on `consts` in place: folded constants are appended, and
`remove_unused_consts` compacts it, so afterwards the list is the unit's
final constants in index order.
- **Not run by it:** `convert_pseudo_conditional_jumps`,
  `prepare_localsplus` (cell and free offsets, `MAKE_CELL`,
  `COPY_FREE_VARS`), `convert_pseudo_ops` and `normalize_jumps`, which
  `_PyCfg_OptimizedCfgToInstructionSequence` runs on the real path; and
  the stack depth isn't returned. Phase F sees all of these (`co_code`,
  `co_stacksize`, `co_nlocalsplus`).
- **Nested units:** compiler_codegen gives their sequences but not their
  constants, which optimize_cfg needs (folding reads the values). So the
  Java side writes each nested unit's codegen constants and `nlocals`
  (`len(u_varnames)`), and compare_flowgraph.py rebuilds them as Python
  objects and runs optimize_cfg on CPython's nested sequence with them. A
  code object constant becomes a unique placeholder object (it's only ever
  loaded for `MAKE_FUNCTION`, never folded). Codegen's comparison already
  shows the sequences match; a wrong nested constant or `nlocals` would be
  fed to both sides alike, so those two are left for Phase F (`co_consts`,
  `co_nlocals`) to catch.

**Decisions** (approved by the user, 2026-10-05):
- **Files** in `org.python.pegen.compile`, C names and order kept:
  `Flowgraph.java` (flowgraph.c: `cfg_builder`, `basicblock`,
  `cfg_instr`), and `Compile.java` grows `optimize_and_assemble_code_unit`
  up to the call to assemble. All of flowgraph.c goes in at once, as
  codegen.c did; the checkpoints are what the comparison shows.
- **Stack effects are generated:** `generate_opcodes.py` adds
  `_PyOpcode_num_popped` / `_PyOpcode_num_pushed` to `Opcode.java`,
  copied from `pycore_opcode_metadata.h` (the return expressions, such as
  `2 + (oparg-1)` or `1 + (oparg & 0xFF) + (oparg >> 8)`, are valid Java),
  plus `_PyOpcode_Deopt` and whatever other tables flowgraph reads.
- **Constant folding needs Python's operations on constants**
  (`fold_const_binop`, `fold_const_unaryop`, the tuple / list / set
  folding): `+ - * / // % ** << >> | ^ &`, subscripts, unary `- + ~ not`,
  truth, over int, float, complex, bool, str, bytes, tuple and frozenset,
  with the `const_folding_safe_*` limits; an operation that raises means
  "don't fold". Hand-ported from Objects/ (abstract.c's `PyNumber_*`,
  then the `long_*`, `float_*`, `complex_*`, `unicode_*`, `bytes_*`,
  `tuple_*` slots they reach) into `Abstract.java`, over the AST value
  classes, C names kept, only the paths folding can reach. Not reused from
  Jython 2's `org.python.core`: that's Python 2 semantics, and the code
  object stays a plain Java value. Risky spots: int / int true division
  (correctly rounded, `long_true_divide`), float `**` (C's `pow` against
  Java's `StrictMath.pow`), float `%` and `//`, complex `/` and `**`,
  int-to-float overflow, frozenset building (dedup by Python equality:
  `{1, 1.0, True}` has one element).
- **New comparison:** `tests/pegen/compare_flowgraph.py` and
  `FlowgraphCompare.java`, the same pattern as compare_codegen.py: per
  unit, depth first, the optimized instructions and the final constants
  (every unit's, now). Java runs exactly what `_PyCompile_OptimizeCfg`
  does (`nparams = 0`, `firstlineno = 1`). Run by smoke.sh over Lib, the
  samples and the corpus, at optimize 0, 1 and 2, in exec, single and
  eval modes (a file codegen rejects is skipped: compare_codegen.py
  covers it).
- **The real path** (`_PyCfg_OptimizedCfgToInstructionSequence`,
  `prepare_localsplus`, the real `nparams` and `firstlineno`) is ported
  in E and wired into `_PyCompile_OptimizeAndAssemble`, whose placeholder
  code object then carries the optimized sequence, final constants, stack
  depth and `nlocalsplus`. It's compared in F, which is the first oracle
  to see it.

**Checkpoints** (all of flowgraph.c went in at once, so E0 to E2 were
checked together):
- [x] E0: `Opcode.java` stack effects; `Flowgraph.java`'s CFG building
      and flattening (`_PyCfg_FromInstructionSequence`,
      `translate_jump_labels_to_targets`, `mark_except_handlers`,
      `label_exception_targets`, `calculate_stackdepth`,
      `_PyCfg_ToInstructionSequence`); compare_flowgraph.py and
      FlowgraphCompare.java in smoke.sh.
- [x] E1: optimize_cfg without constant folding: unreachable code, NOPs,
      jump threading, small-block inlining, `swaptimize`,
      `remove_unused_consts`, uninitialized-variable checks,
      superinstructions, cold blocks, line numbers, `optimize_load_fast`.
- [x] E2: constant folding (`Abstract.java`, `LibM.java`),
      `LOAD_SMALL_INT`, `LOAD_COMMON_CONSTANT`, list and set to tuple and
      frozenset.
- [x] E3: the real path wired into `_PyCompile_OptimizeAndAssemble`
      (`optimize_and_assemble_code_unit`; the placeholder code object is
      made by a `_PyAssemble_MakeCodeObject` stand-in in Compile.java).
- [x] smoke.sh passes, with the flowgraph comparison in it.

Results:
- **Comparison:** Lib at optimize 0, 1 and 2 (2,025 files each), the
  samples with `deep/`, and the error corpus in file, single and eval
  modes (37,455 files each): all identical, apart from 7 entries in
  `compare_known.txt` (mode `flowgraph`), the codegen ones seen again.
  New samples: `accept/const_folding.py` (folding's edge cases, about 320
  expressions) and `accept/many_locals.py` (more than 64 locals, `del`,
  superinstructions).
- **CPython bugs in the oracle (3.15.0rc2, not reported yet):**
  `optimize_cfg` fails an assertion, aborting a debug build (ours is one),
  on two kinds of unit; `compile()` is fine with both. (1)
  `load_fast_push_block`, on any unit with an `async for`: optimize_cfg
  runs `optimize_load_fast` on a CFG that still has the pseudo-instructions
  the real path converts first. (2) `_PyCfg_FromInstructionSequence`, on a
  module with annotations: with compiler_codegen's `c_save_nested_seqs`,
  the annotation code has the `__annotate__` unit as a nested sequence,
  which the assertion forbids. FlowgraphCompare, which ports the
  assertions, writes such a unit as `#ABORT`, and compare_flowgraph.py
  then leaves it out: 125 units in Lib, 5 in the samples. Phase F compares
  them.
- **Real path:** every Lib file compiles through `_PyAST_Compile` with
  assertions on (2,013, and the 12 SyntaxErrors CPython's has too). The
  codegen and flowgraph comparisons run it on every nested unit (C's
  compiler_codegen optimizes and assembles nested units for their code
  objects). Its results are first compared in Phase F.
- **The platform's libm:** CPython's float `**` (non-integral exponent)
  and complex `**` (unless the exponent is an integer up to 100) call
  libm's pow, exp, log, sin, cos, atan2 and hypot, whose last bit varies
  across platforms. glibc's are correctly rounded in about 99.95% of
  cases; StrictMath (fdlibm) differed from glibc in 3% to 10% of random
  arguments. `LibM.java` computes them correctly rounded (BigDecimal,
  Ziv's method), which matches glibc in about 99.9%; the rest are glibc's
  own rounding errors. C's errno also decides folding: glibc's
  `cos(inf)` sets EDOM, so `(2+3j) ** 1e999` raises (ZeroDivisionError)
  and isn't folded; Abstract emulates it.
- **Object identity:** flowgraph's constants index (`consts_index`) is
  keyed by address, so whether a folded value reuses an existing constant
  depends on whether C returns the same object. `Abstract.Py_Is` treats as
  one object what C shares (small ints, `''`, one-character Latin-1 str,
  `b''`, one-byte bytes, `()`), and Abstract returns an operand itself
  where C does (`+x`, `s + ''`, `t * 1`, full slices). Not modelled: C's
  `bytes_repeat` and stepped bytes slices make new empty or one-byte
  bytes. On the real path this can't show (add_const merges through the
  const cache first, which every constant is in); in optimize_cfg's mode
  (a fresh cache) `0 * b'ab'` next to a `b''` literal would get a
  different index. const_folding.py avoids that pairing.
- **NaN constants:** C's const cache merges a NaN only with the same
  object (`_PyCode_ConstantKey` compares values, and a NaN equals
  nothing); the Java key compared bits, so it merged equal NaNs. Fixed in
  `PyCodeObject._PyCode_ConstantKey` (floats and complexes with a NaN are
  keyed by identity). AstCompare's float form now writes raw bits, as
  compare_ast.py does (`doubleToLongBits` dropped a NaN's sign).

## Working notes (for a new session)

### Traps already hit
- **_testinternalcapi aborts on some input** (our CPython is a debug
  build): optimize_cfg on `async for` units and on modules with
  annotations (Phase E results). A test script that dies with an
  assertion message in C may be hitting a CPython bug, not ours: check
  `compile()` on the same source.
- **`os._exit` drops buffered output:** the compare scripts leave with
  `os._exit` (see compare_codegen.py's KEEP), so a quick probe script that
  does the same must run with `python -u` or flush, or print nothing.
- **A `WarningHandler` returning false** turns the warning into an error.
- **Folding's results depend on object identity** in C (see Phase E
  results, "Object identity"): return an operand itself exactly where C
  does.
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
