# Move the bytecode interpreter from CPython 3.11 to 3.15

## Context
The user asked for a REPL. This branch has no Python compiler: it runs CPython 3.11
bytecode (`.pyc` files read by `marshal`) on `CPython311Frame`. Any REPL would
therefore get its bytecode from an external CPython. Before building the REPL, the user
wants the runtime moved to 3.15. `../cpython` holds a built **3.15.0rc2**
(`../cpython/python.exe`, magic number **3666**, `marshal.version` 6).

This follows the same route as the 3.8→3.11 move (commits `4efc90aea`, `22f728e39`,
`c9da398eb`, `e9eeb64ea`): first rename, then change the classes, then add opcodes. The
3.11 classes are replaced, not kept alongside. The REPL gets its own plan afterwards.

## What the investigation found (3.15 vs 3.11)
- **Marshal code-object layout is unchanged.** The field order in `Python/marshal.c`
  (line ~701) matches `marshal.java` `CodeCodec.read`. The local-kind flags have the same
  values; 3.15 adds `CO_FAST_ARG_*` (0x02/0x04/0x08) and `CO_FAST_HIDDEN` (0x10), which
  can be ignored for now.
- **Marshal has two new type codes:** `TYPE_SLICE ':'` (slice constants, from 3.14) and
  `TYPE_FROZENDICT '}'`.
- **Opcode numbers and cache sizes are all different.** 3.15 cache entries:
  LOAD_ATTR 9, BINARY_OP 5, CALL/CALL_KW 3, TO_BOOL 3, LOAD_GLOBAL 4, STORE_ATTR 4,
  1 each for COMPARE_OP, CONTAINS_OP, STORE_SUBSCR, JUMP_BACKWARD, POP_JUMP_IF_*,
  FOR_ITER, UNPACK_SEQUENCE, CALL_FUNCTION_EX.
- Opcodes the existing test examples need under 3.15: BINARY_OP, BUILD_LIST/MAP/TUPLE,
  CALL, CALL_KW, CALL_FUNCTION_EX, COMPARE_OP, CONTAINS_OP, DELETE_NAME, DICT_MERGE,
  JUMP_BACKWARD, JUMP_FORWARD, LIST_EXTEND, LOAD_ATTR, LOAD_COMMON_CONSTANT, LOAD_CONST,
  LOAD_NAME, LOAD_SMALL_INT, NOT_TAKEN, POP_JUMP_IF_FALSE/TRUE, PUSH_NULL, RESUME,
  RETURN_VALUE, STORE_NAME, STORE_SUBSCR, TO_BOOL, UNARY_INVERT, UNARY_NEGATIVE.
- **Semantic changes that affect the frame:**
  - Call stack layout is now `callable | self_or_null | args…` (3.11 had `null | callable`).
    CALL_KW takes its kwnames tuple from the stack, and KW_NAMES and PRECALL are gone.
  - LOAD_METHOD is gone. `LOAD_ATTR` takes the name index from `oparg>>1`. If
    `oparg&1` is set, it pushes `meth, self` or `attr, NULL`.
  - `BINARY_SUBSCR` is folded into `BINARY_OP` with `NB_SUBSCR = 26`.
  - COMPARE_OP: the operator is `oparg>>5`, and `oparg&16` means "coerce the result to
    bool" (`bytecodes.c` ~3277).
  - POP_JUMP_IF_* are forward-only, work on an exact bool, and have a TO_BOOL before them.
    Jumps are relative to the end of the instruction's inline cache. NOT_TAKEN is a no-op.
  - JUMP_IF_{TRUE,FALSE}_OR_POP are gone; `and`/`or` compile to COPY/TO_BOOL/POP_JUMP/POP_TOP.
  - LOAD_SMALL_INT pushes `oparg` as an int. LOAD_COMMON_CONSTANT indexes a fixed table:
    `[AssertionError, NotImplementedError, tuple, all, any, list, set, None, '', True, False, -1]`.
  - Interactive mode uses `CALL_INTRINSIC_1 1 (INTRINSIC_PRINT)` + POP_TOP, not
    PRINT_EXPR. This matters for the REPL later.

## Steps

### 1. Rename (a separate commit, like `4efc90aea`)
`git mv` `CPython311Code/Frame/Function.java` → `CPython315*`, `Opcode311` → `Opcode315`,
and `CPython311CodeTest` → `CPython315CodeTest`. Then update the references in
`Comparison.java:173`, `PyType.java:766` and `marshal.java` (`CodeCodec`).

### 2. Generate `Opcode315.java` from 3.15 itself
Add `build-tools/python/tool/opcode_gen.py`, run with `../cpython/python.exe`. It writes
`Opcode315.java` in the current file's style: a Javadoc'd `static final int` per opcode from
`opcode.opmap`; `NB_*` from `dis._nb_ops`; `INLINE_CACHE_ENTRIES_<OP>` from
`opcode._inline_cache_entries`; `INTRINSIC_*` from `dis._intrinsic_1_descs`; and
`HAVE_ARGUMENT`. It is run by hand and the output committed, so the next version move
costs one command rather than 550 hand-edited lines.

### 3. `CPython315Code` (`core/src/main/java/org/python/core/`)
- Make `create(...)`/the constructor accept the 3.15 fields. They are the same as 3.11, so
  this is mostly renaming. Check that `layout` handles `CO_FAST_HIDDEN`/`CO_FAST_ARG_*`
  bits: it should mask on LOCAL/CELL/FREE only.
- Add the `LOAD_COMMON_CONSTANT` table (a static `Object[]`). Entries this runtime lacks
  (`AssertionError`, `set`, `all`, `any`) are filled lazily, or throw `MissingFeature`
  when loaded.

### 4. `CPython315Frame.eval()`: rewrite the switch against `Opcode315`
Keep the loop structure (wordcode `short[]`, `ip` in code units, EXTENDED_ARG handling),
and after each cached opcode skip `INLINE_CACHE_ENTRIES_*`. Opcode by opcode:
- Unchanged apart from the number: NOP, RESUME, LOAD_CONST, PUSH_NULL, UNARY_NEGATIVE,
  UNARY_INVERT, STORE_SUBSCR, DELETE_SUBSCR, RETURN_VALUE, STORE_NAME, DELETE_NAME,
  LOAD_NAME, BUILD_TUPLE/LIST/MAP, LIST_EXTEND, DICT_MERGE, IS_OP, CONTAINS_OP,
  JUMP_FORWARD, JUMP_BACKWARD, JUMP_BACKWARD_NO_INTERRUPT.
- Changed: LOAD_ATTR (method flag), COMPARE_OP (`>>5`, bool flag), BINARY_OP (adds
  NB_SUBSCR; also enable the `NB_*` cases whose `PyNumber` methods exist),
  POP_JUMP_IF_{FALSE,TRUE,NONE,NOT_NONE} (single forward form), CALL (new stack order),
  CALL_FUNCTION_EX (new stack order, with `self_or_null`).
- New: TO_BOOL (`Abstract.isTrue` → `Py.True/False`), NOT_TAKEN (no-op), LOAD_SMALL_INT,
  LOAD_COMMON_CONSTANT, CALL_KW, POP_TOP, COPY, SWAP, UNARY_NOT,
  CALL_INTRINSIC_1 (only INTRINSIC_PRINT for now; others throw `MissingFeature`).
- Removed: PRECALL, KW_NAMES, LOAD_METHOD, JUMP_IF_*_OR_POP, the POP_JUMP_BACKWARD_*
  variants, BINARY_SUBSCR, JUMP_BACKWARD_QUICK.
- Change `getMethod(...)` to write `meth, self` / `attr, null` in the new order. It is
  called from LOAD_ATTR, and `PyType.java:766` documents it.
- Check jump arithmetic against `dis` output (target = next instruction after caches ±
  oparg).

### 5. `marshal.java` (`core/src/main/java/org/python/modules/`)
- Add a decoder for `TYPE_SLICE ':'`: three objects → `PySlice`, with ref handling like
  tuple.
- Add a decoder for `TYPE_FROZENDICT '}'` that throws `MissingFeature` (there is no
  frozendict type yet), so any failure is clear.

### 6. Build and test tooling
- `core/core.gradle`: the `PythonExec` class uses the executable `python`, which is not on
  PATH here. Add a Gradle property `cpython` (default `../cpython/python.exe`, override
  with `-Pcpython=…`) used by `compileTestPythonExamples`. Leave
  `generateObjectDefinitions` on any Python 3, using the property if set.
- `build-tools/python/lib/compile_examples.py`: take `COMPILER` from
  `sys.implementation.cache_tag` (→ `cpython-315`) and fail fast unless
  `sys.version_info[:2] == (3, 15)`.
- `CPython315CodeTest`: `CPYTHON_VER = "cpython-315"`, `MAGIC_NUMBER = 3666`. Update the
  `co_consts` count expectation in `SimpleCodeAttributes`: 3.15 uses
  LOAD_SMALL_INT/LOAD_COMMON_CONSTANT, so `None` and small ints are no longer in
  `co_consts`. Re-check each expectation against `../cpython/python.exe -c "compile(...)"`.
- Add examples for the new paths: `and_or.py` (COPY/TO_BOOL), `not_op.py`, `subscr_slice.py`
  (NB_SUBSCR plus slice constant), `call_kw.py` (CALL_KW).

### 7. Docs
Update the version references in `README.md` ("support version 3.8") and the Javadoc
comments in the renamed classes (e.g. "CPython38Frame" at `CPython315Frame.java:40`).

## Verification
1. `../cpython/python.exe build-tools/python/tool/opcode_gen.py` → diff it against the
   committed `Opcode315.java` (the output is reproducible).
2. `./gradlew --console=plain core:compileTestPythonExamples` → check that
   `core/build/generated/sources/pythonExample/test/__pycache__/*.cpython-315.pyc` exists.
3. `./gradlew core:test`. All of `CPython315CodeTest` should pass (load, attributes,
   executeSimple, executeBranchAndLoop, and the new examples), and the other core tests
   should stay green.
4. Spot-check: for each example, disassemble with `../cpython/python.exe -m dis` and
   confirm that each opcode in it has a case in `CPython315Frame`. No `unknown opcode`
   `InterpreterError` should appear in the test output.

## Follow-on (a separate plan)
The REPL: a `Repl` main class, plus a persistent `../cpython/python.exe` subprocess that
uses `codeop.compile_command(..., 'single')` and sends back marshalled code, run on a
persistent `globals` via `Interpreter.eval`. It depends on CALL_INTRINSIC_1/INTRINSIC_PRINT
from step 4.
