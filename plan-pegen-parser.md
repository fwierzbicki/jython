# PEG parser for Jython: plan and status

The parser, built on the peg-parser branch: a Java port of CPython's
pegen-generated parser, building a Python 3 AST identical to `ast.parse()`'s.
The compiler work continues on the cpython-bytecode-compiler branch.
**Status** says where it stands and what's left. **Working notes** has the
commands, conventions and traps the code doesn't show; they apply to the
compiler work too (plan-cpython-bytecode-compiler.md). The completed plans are
kept below for reference.

## Status (2026-10-04)

- **Done: the Java parser generator** (steps 1–4 of the original design below).
  `ant pegen-gen` regenerates the checked-in parser from `../cpython`, which is
  at v3.15.0rc2. It translates all 538 grammar actions by default, so the
  checked-in `GeneratedParser.java` builds the Python 3 AST; `--skip-actions`
  gives a recognizer instead.
- **Done: porting the `_PyPegen_*` helpers** (Phases 1–5 below). The parser's
  output matches CPython's `ast.parse()`: trees, errors and warnings. The
  exceptions are listed in `tests/pegen/compare_known.txt`, and Phase 4's
  results explain them.
- **Done: stack depth.** `Parser.runParser` parses on a
  pooled thread with a 16 MB stack (`LargeStack.STACK_SIZE`), so MAXSTACK, not
  the caller's stack, is the limit, as in CPython. The `pending/stack/`
  samples moved to `tests/pegen/deep/accept/`, and `deep/reject/` has input
  just past MAXSTACK (CPython rejects it with the same MemoryError).
  smoke.sh checks both on a 1 MB stack and passes. See Working notes.
- **Checks passing:** `ant compile`, `tests/pegen/smoke.sh` (exit 0, about
  9 minutes: trees, symbol tables and tokens against CPython over Lib, the
  samples and the error corpus), `tests/pegen/test_action_translator.py`
  and the pegen JUnit tests (41, `TokenizerTest` included).
- **Before calling it done:** run `/adversarial-parser-review` (see
  Verification).
- **Upstream stays at v3.15.0rc2** (`../cpython` checked out at the tag,
  rebuilt). rc3 changed `python.gram`, `action_helpers.c`, `pegen.c/h`,
  `asdl_c.py` and the lexer (`<>` tokenizing as `<` `>` without
  barry_as_FLUFL, gh-151464; unary `+` in match patterns, gh-152708;
  f-string debug text). Syncing to rc3 or later is a separate task.
- **Done: the Java tokenizer** (`org.python.pegen.lexer`, see **Completed:
  the Java tokenizer**): the parser now reads source through it
  (`Parser.fromString`), and every comparison runs from source; token
  dumps are only the oracle for `compare_tokens.py`.
- **What's left:** name aliases for `\N{...}` (`ucnhash`), the one
  `compare_known.txt` entry for the AST.
- **Committed:** Phase 1 in f59b322e1, Phase 2 in 51ec27f58, Phase 3 in
  13fdf834c, Phase 4 in 2c17a33f7, Phase 5 and stack depth by 676fc3da9,
  the last commit on peg-parser. Later work is on cpython-bytecode-compiler
  (plan-cpython-bytecode-compiler.md).

## Completed: the Java tokenizer (approved and done 2026-10-04)

Decided with the user, 2026-10-04 (see plan-cpython-bytecode-compiler.md,
Decisions). It's the next piece of work, ahead of codegen.

**Goal:** a port of Parser/lexer/ and Parser/tokenizer/ (about 3.3k lines of
C) as the parser's `TokenSource`, so the front end runs from source with no
CPython token dumps. It closes these gaps:
- tokenizer errors (`pending/tokenizer/`, and the corpus files compare_ast.py
  skips today because CPython's tokenizer rejects them);
- tokenizer warnings (compare_ast.py filters them out today);
- the 9 single-input entries in `compare_known.txt`;
- the 39 f/t-string debug files (`Token.metadata`) and the escaped-brace end
  columns, which are limitations of the dump;
- coding cookies, for `Errors.PyErr_ProgramTextObject` and
  `SyntaxError.text`.

**Rules:** the same as the rest of this plan: C names kept, CPython's
`tokenize` and `ast.dump()` as the oracle, and smoke.sh passing over the
whole corpus at every checkpoint. Mutation checks are optional spot-checks.

**Decisions** (approved by the user, 2026-10-04):
- **Package `org.python.pegen.lexer`,** one Java file per C file, C names and
  order kept: `State.java` (lexer/state.h and state.c: `tok_state`,
  `tokenizer_mode`, `struct token`), `Lexer.java` (lexer/lexer.c),
  `Helpers.java` (tokenizer/helpers.c),
  `StringTokenizer.java` (tokenizer/string_tokenizer.c and
  utf8_tokenizer.c). Fields are package-private, as C's struct fields are
  open. A `Tokenizer` class adapts `tok_state` to `TokenSource` (the
  `_PyPegen_fill_token`/`initialize_token` side of pegen.c), and
  `Parser` gets a `_PyPegen_run_parser_from_string` counterpart.
- **The buffer is a `byte[]` of UTF-8,** and `buf`, `cur`, `inp`,
  `line_start`, `start`, `multi_line_start` are int indices into it, as
  `StringParser` works on bytes. Columns stay byte offsets, so nothing
  converts them, and error columns that C counts in characters (decoded
  `errtext` length) are counted the way C counts them.
- **Input: strings only.** `_PyTokenizer_FromString` (bytes: BOM, coding
  cookie, decoding, newline translation) and `_PyTokenizer_FromUTF8` (str
  source, `PyCF_IGNORE_COOKIE`). The file, readline and interactive
  tokenizers (file_tokenizer.c, readline_tokenizer.c) are left out: there
  is no REPL or `tokenize` module on this branch. `tok->prompt` stays null.
- **Encodings** named by a cookie are looked up as Java charsets after
  `get_normal_name`; a name Python knows and Java doesn't is a difference to
  list, not to emulate.
- **The token oracle stays:** `dump_tokens.py` keeps writing CPython's
  tokens, and a new `TokenCompare` driver (`compare_tokens.py`) diffs the
  Java tokenizer's against them. Once the drivers switch to the Java
  tokenizer (Phase T3), `TokenDump` is no longer used by `AstCompare`,
  `SymtableCompare` or `RecognizerSmoke`, and is deleted.

### Phase T1: the lexer (done, 2026-10-04)
- [x] `State` (state.h, state.c), `Lexer` (all of lexer.c), `Helpers`
      (helpers.c), `StringTokenizer` (string_tokenizer.c, utf8_tokenizer.c)
      and `Tokenizer`, the `TokenSource` adapter, in
      `org.python.pegen.lexer`. Not used by the parser yet.
- [x] `TokenCompare` and `compare_tokens.py`: types, text, byte positions
      and metadata against `dump_tokens.py`. smoke.sh runs it over Lib, the
      samples and the error corpus (`tokens-*`).

Results:
- **All of lexer.c went in at once,** f/t-string mode included, since
  `tok_get_normal_mode` is interleaved with it, so Phase T2's code is in T1.
- **Generated:** `TokenTypes` now has `_PyToken_OneChar`, `_TwoChars` and
  `_ThreeChars` (Parser/token.c), built by generate.py the way
  Tools/build/generate_token.py builds them. `lexer/UnicodeTables.java`
  holds CPython's XID_Start, XID_Continue and printable ranges (Unicode 17),
  generated by `src/pegen/tools/generate_unicode.py`, which must run on the
  3.15 build (`../cpython/python src/pegen/tools/generate_unicode.py`), so
  identifiers follow CPython's Unicode version, not the JVM's.
- **Left out:** buffer.c and case 0 of `_PyLexer_update_ftstring_expr`,
  which only the file and readline tokenizers use. The input is held whole,
  so a mode's `last_expr_buffer` is an index into it, not a copy.
- **Gotos:** `nextline` and `again` are labelled loops, `f_string_quote` /
  `letter_quote` a jump variable, and `fraction` / `exponent` /
  `imaginary` the method `tok_number_tail`.
- **Comparison:** 31,048 files (Lib, samples, the error corpus): all match
  apart from 13 known dump limitations in `compare_known.txt` (mode
  `tokens`): the dump doesn't set metadata on the `}` that closes a debug
  or t-string field with a format spec (or a debug field whose `=` isn't
  last), and a BOM-only file's ENDMARKER line. `dump_tokens.py` now puts an
  empty file's ENDMARKER on line 0, as C does in every mode.

### Phase T2: f- and t-strings (done with T1)
- [x] `tok_get_fstring_mode`, the mode stack, `set_ftstring_expr` /
      `_PyLexer_update_ftstring_expr` (`Token.metadata`). (`Buffer` isn't
      needed: see T1.)
- [x] `compare_tokens.py` over every file; the dump's known limitations
      listed as expected differences.

### Phase T3: errors and warnings; the drivers switch over (done, 2026-10-04)
- [x] The tokenizer's errors and warnings (ported in T1 with helpers.c):
      `_PyTokenizer_syntaxerror*`, `indenterror`, `error_ret`,
      `warn_invalid_escape_sequence`, `parser_warn`, `ensure_utf8`, decode
      errors and `_PyTokenizer_raise_init_error`; `TokenSource.error()`, and
      warnings to the parser's `warning_handler`.
- [x] `Parser.fromString` (the setup half of `_PyPegen_run_parser_from_string`,
      with `compute_parser_flags`): source bytes, or UTF-8 with
      `PyCF_IGNORE_COOKIE`. `AstCompare`, `SymtableCompare` and
      `RecognizerSmoke` read source and use it. compare_ast.py no longer
      skips files CPython's tokenizer rejects, nor filters tokenizer
      warnings.
- [x] `pending/tokenizer/reject/invalid_identifier_char.py` moved to
      `reject/`; the 9 single-input entries left `compare_known.txt`.
- [x] `Errors.PyErr_ProgramTextObject` decodes with the encoding
      `_PyTokenizer_FindEncodingFilename` (in `StringTokenizer`) finds.
- [x] `TokenDump` deleted; smoke.sh uses the Java tokenizer throughout.
      `dump_tokens.py` stays as compare_tokens.py's oracle.

Results:
- **Comparisons:** Lib is 2,025 files now (the 4 CPython's tokenizer rejects
  included), and the error corpus 37,260 (about 8,000 tokenizer rejects
  that were skipped before); all match but the `\N{RS}` entry, at
  every mode and optimize level.
- **`tok->buf` is not `line_start`:** C reports E_LINECONT at
  `cur - buf`, and `_PyPegen_raise_error`'s fallback checks `cur == buf`.
  In the string tokenizer `buf` moves to a new line only between tokens, so
  after a line continuation it's still on the earlier line. The dump's
  `cursorColumn()` stood in for both; `TokenSource.bufferOffset()` is now
  `cur - buf` (single-mode `strings/s11771.py`).
- **Compile's own check:** `compile()` rejects source with a null byte
  before parsing (`_Py_SourceAsString`); `AstCompare.parser` does the same,
  standing in for the builtin.
- **`SyntaxError` from an init error** has `end_lineno` and `end_offset`
  None (a 4-tuple): `PythonSyntaxError.noEnd`.
- `tests/java/org/python/pegen/lexer/TokenizerTest.java`: encoding
  detection, `SyntaxError.text` through a latin-1 cookie, and
  `PyCF_IGNORE_COOKIE`.

## Completed: port the _PyPegen_* helpers

**Goal:** parsing with actions produces a Python 3 AST identical to CPython's
`ast.parse()`, error messages and locations included. The checked-in parser can
then switch to actions, and an `ast.dump()` oracle can replace the
accept/reject smoke test.

**Decisions** (made with the user):
- **AST:** a new Python 3 AST, generated from CPython's `Parser/Python.asdl`.
  Jython 2.7's AST is not used.
- **Values:** plain Java values inside the AST, not Jython core PyObjects.

| Python | Java |
|---|---|
| identifiers and `str` | `String` |
| `int` | `BigInteger` |
| `float` | `Double` |
| `complex` | `ast.Complex` |
| `bytes` | `ast.Bytes` |
| `None`, `True`, `False`, `Ellipsis` | `ast.Singleton` |

**What's being ported:**

| Source | Size | Contents |
|---|---|---|
| `Parser/action_helpers.c` | ~930 lines | The helpers |
| `Parser/pegen.c` | ~200 lines | Literals |
| `Parser/string_parser.c` | ~340 lines | String literal decoding |
| `Parser/pegen_errors.c` | ~420 lines | Error reporting |

### Phase 1: generate the Python 3 AST (done)
- [x] `src/pegen/tools/asdl_java.py`, run by `generate.py`. It uses CPython's
      own `Parser/asdl.py` and is modelled on `Parser/asdl_c.py`.
- [x] **Generated output:**
  - Node classes go in `org.python.pegen.ast`.
  - Sum-type bases (`expr`, `stmt`, …) go in `ast/base/`, each with a `Kind`
    enum and `kind()`.
  - Simple sums become `*Type` enums (`operatorType`, …).
  - Types with location attributes implement `ast.Located`.
- [x] `AstFactory.java` is generated. `_PyAST_*` keep their C signatures and
      required-field checks, and a missing field throws `ast.AstValueError`.
- [x] **Real types:** `java_types.JavaTypeMap(ast_types=True)` gives real AST
      types in `--actions` mode. Skip-actions mode stays all-`Object`, because
      every rule there returns one dummy value.
- [x] **ActionHelpers:** the pegen.h structs have typed fields. The stubs were
      regenerated with real types, and C's untyped `asdl_seq *` parameters are
      `List<?>`.
- [x] **Translator follows C's typing:** optional items are `void *`, and a
      `void *` value passed as an argument (including a `?:` with a `void *`
      branch) becomes `fromVoidPtr(x)`, so Java infers the cast. Sequence casts
      are written `(List<X>) (List<?>) x`.

### Phase 2: values, tokens and literals
- [x] Value classes in `ast/`: `Complex`, `Bytes`, `Singleton`.
- [x] **`Token` carries C's data:**
  - `Token.string` holds the exact token text.
  - Columns are UTF-8 **byte** offsets, as in C.
  - `tests/pegen/dump_tokens.py` writes both (escaped text, byte columns) and
    `RecognizerSmoke` decodes them. The future Java tokenizer must do the same.
- [x] **pegen.c literals in `Parser.java`:**
  - `nameToken()` and `softKeywordToken()` return `Name` nodes via
    `newIdentifier`, which applies NFKC normalization to non-ASCII names. Like
    C, `softKeywordToken()` doesn't rewind on failure.
  - `numberToken()` returns a `Constant` via `parsenumber`, and applies the
    4,300-digit limit with CPython's message.
- [x] **Port `string_parser.c` into `src/org/python/pegen/StringParser.java`**,
      keeping the C function names (`_PyPegen_decode_string`,
      `_PyPegen_parse_string`, `decode_unicode_with_escapes`, …).
  - It also ports the two decoders C calls,
    `_PyUnicode_DecodeUnicodeEscapeInternal2` and `_PyBytes_DecodeEscape2`,
    and `_Pypegen_raise_decode_error` (in `ActionHelpers`).
  - It works on UTF-8 bytes, as C does (see Working notes).
  - `\N{...}` goes through `UnicodeNames.getcode`, a port of unicodedata.c's
    `_getcode`. It computes the derived names (Hangul syllables, CJK/Tangut/…
    ideographs, with the Unicode 17 ranges) and looks up the rest in Jython's
    `ucnhash`. **Known gaps:** `ucnhash` has no name aliases
    (`\N{LINE FEED}`, `\N{BYTE ORDER MARK}`) and older Unicode data. Add
    samples to `pending/` once the Phase 5 oracle can see them; the
    recognizer never decodes strings.
  - Invalid escapes warn through a minimal warnings channel in `Parser`
    (`warnings`, `warnings_as_errors`, `warnExplicit`). Phase 4 finishes it.
- [x] JUnit tests `StringParserTest` and `ParsenumberTest` in
      `tests/java/org/python/pegen/`. Their expected values, messages and
      positions were checked against CPython 3.15, and include cases from
      `test_grammar` and `test_string_literals`.

### Phase 3: port action_helpers.c in place
Replace each stub in `ActionHelpers.java` with a straight port. Keep the C
names (the static `_set_*_context` and `_make_*` helpers become private static
methods) and the C file's order, so the two diff side by side. Do the groups in
this order, each compiling before the next:
All four groups are done, ported in one pass in C's order (plus
`_PyPegen_interactive_exit` from pegen.c).
1. [x] **Sequences and structs:** `singleton_seq`, `seq_insert_in_front`,
   `seq_append_to_end`, `seq_flatten`, `seq_count_dots`, `map_names_to_ids`,
   the `*_pair` builders and `get_*` accessors, `slash_with_default`,
   `star_etc`, `join_sequences`, `augoperator`, `keyword_or_starred`, the
   `seq_*_starred_exprs` pair, `get_last_comprehension_item`,
   `register_stmts`, `interactive_exit`.
   (`dummy_name` is already done.)
2. [x] **Nodes:** `set_expr_context`, `make_arguments`, `empty_arguments`, the
   `*_def_decorators` pair, `collect_call_seqs`, `join_names_with_dot`,
   `alias_for_star`, `make_module`, `new_type_comment`,
   `add_type_comment_to_arg`, `ensure_real`/`ensure_imaginary`,
   `check_legacy_stmt`, `check_barry_as_flufl`, `checked_from_import`. For the
   barry checks, `Parser` gets a `flags` field; `checked_from_import` sets
   BARRY_AS_BDFL.
3. [x] **Constants and f/t-strings:** the `constant_from_*` functions,
   `decoded_constant_from_token`, `decode_fstring_part`,
   `_get_resized_exprs`, `joined_str`, `template_str`, `formatted_value`,
   `interpolation`, `setup_full_format_spec`, `check_fstring_conversion`,
   `concatenate_strings` (with `_build_concatenated_*`),
   `concatenate_tstrings`.
4. [x] **Error helpers:** `get_expr_name`, `get_invalid_target`,
   `arguments_parsing_error`, `nonparen_genexp_in_call`,
   `raise_error_for_missing_comma`.

**What the port added outside ActionHelpers:**
- `Parser` has the pegen.h fields the helpers use: `flags`, `errcode`,
  `type_ignore_comments` (filled by `fillToken` from TYPE_IGNORE tokens) and
  `last_stmt_location`. It also has `emitSyntaxWarning`
  (`_PyErr_EmitSyntaxWarning`).
- `TokenSource` has default methods for the tokenizer state that actions read:
  `insideFstring`, `insideTstring` and `fstringRaw` (C: `INSIDE_FSTRING`,
  `TOK_GET_MODE(tok)->string_kind`, `->raw`). `RecognizerSmoke.DumpTokenSource`
  implements them by tracking `*_START`/`*_END` tokens.

**How it was checked:** a scratch run of the full-actions parser over Lib,
with Java and CPython each printing every tree in the same canonical text
form, diffed file by file. Results:
- **1,968 files are identical.**
- **12 files differ,** each only in the end column of an f-string text part
  containing `{{` or `}}`. Python's `tokenize` unescapes those in FSTRING_MIDDLE
  and shortens the end column (and splits `{{b` into two tokens); C's tokenizer
  doesn't. This is a dump limitation.
- **39 files fail** on f/t-string debug expressions (`f"{x=}"`): C's tokenizer
  puts the expression text in `Token.metadata`, and `tokenize` doesn't expose
  it. This is also a dump limitation.
- **1 file fails** (`test_configparser.py`) on `\N{RS}`, a name alias
  `ucnhash` lacks.

With actions, all 16 `pending/actions/` samples are rejected. Their messages
and start positions match CPython's, except `ltgt_without_barry.py`: CPython's
"invalid syntax" there comes from the Phase 4 retry pass.

The token-dump limitations behind the 12 and 39 files were fixed in Phase 4
(`dump_tokens.py` now dumps C's tokens), and those files match now.

### Phase 4: errors and warnings (done)
- [x] **Port `pegen_errors.c`** into `ActionHelpers`, in C's order:
      `_Pypegen_tokenizer_error`, `raise_unclosed_parentheses_error`,
      `_PyPegen_tokenize_full_source_to_check_for_errors`, `_PyPegen_raise_error`
      (with `known_err_token`), `_PyPegen_raise_error_known_location` (with the
      error's source line), `_Pypegen_set_syntax_error` and
      `_Pypegen_stack_overflow`. The byte-to-character offset conversion
      (`_PyPegen_byte_offset_to_character_offset*`, pegen.c) is there too.
- [x] **`PythonSyntaxError`:** type, msg, lineno, offset, end_lineno,
      end_offset and text, plus `_metadata`. `Parser` keeps the pending
      exception as one (`getError()`, `setError`, `errorMatches`,
      `clearError`). An exception with no location (ValueError, MemoryError,
      ...) has `hasLocation()` false.
- [x] **`TokenSource` has the tokenizer state errors read,** as default
      methods named after the `tok_state` fields: `done`, `error`, `lineno`,
      `cursorColumn`, `currentLine`, `getLine`, `rest`, `source`,
      `interactive`, `level` and the paren stacks. It also has `implyDedents`
      (single input's `pendin`). A TokenSource must keep returning ENDMARKER at
      the end, as C's tokenizer does; `Parser` no longer replays it.
- [x] **`Parser.fillToken` handles ERRORTOKEN** as `initialize_token` does. A
      tokenizer's own exception comes through `TokenSource.error()`.
- [x] **The retry pass in `Parser.runParser()`,** as `_PyPegen_run_parser`
      does, with `reset_parser_state_for_error_pass`, IncompleteInputError
      and `_PyPegen_set_syntax_error_metadata`. An `AstValueError` thrown by
      AstFactory becomes the pending ValueError.
- [x] **Warnings channel:** `Parser.warning_handler` receives each
      `ParserWarning` (category, message, filename, lineno, module). It
      returns false to raise the warning as an error; the default records it
      in `warnings`. It replaces `warnings_as_errors`.
- [x] **`bad_single_statement`** is ported as C scans the text after the
      cursor (`TokenSource.rest()`). The old read-ahead over tokens remains
      only as the fallback for a TokenSource without the source.
- [x] **Generator fix found by the comparison:** a default action returning
      an untyped (`void *`) value from a typed rule goes through
      `ActionHelpers.voidAs`/`voidAsList` (`java_types.void_cast`). C
      reinterprets `_PyPegen_dummy_name`'s Name as any type there. That only
      happens in the second pass, whose result is discarded, so a placeholder
      of the expected type stands in. Before this fix,
      `invalid_def_raw`'s dummy crashed with a ClassCastException. The
      placeholder statement can then reach `_PyPegen_function_def_decorators`
      (a decorated def, then a later error), which returns it unchanged
      instead of casting it. `/adversarial-parser-review` found that case; its
      regression samples are the `decorated_*` files in `reject/` and
      `single/reject/`.

**Comparison tooling** (see Commands):
- `tests/pegen/compare_ast.py` builds the full-actions parser and runs
  `AstCompare.java` over the token dumps. It compares every tree, error
  (type, msg, lineno, offset, end_lineno, end_offset, text) and parser warning
  with CPython's `compile(..., "<unknown>", mode, PyCF_ONLY_AST)`, using a
  canonical text form both sides write.
- `tests/pegen/extract_samples.py` writes the error corpus: the
  `test_syntax.py` doctests and every string constant in Lib/test that
  CPython rejects.
- `dump_tokens.py` now dumps C's own tokens (`_tokenize.TokenizerIter` with
  `extra_tokens=False`), plus the source, the f/t-string metadata, and the
  end-column fixes for NEWLINE and escaped braces. Its docstring has the
  details. `TokenDump.java` reads the dumps for RecognizerSmoke and
  AstCompare.

**Results (2026-09-30):**

| Input | Mode | Identical |
|---|---|---|
| Lib | file | 2,020 of 2,021 |
| samples | file, single | 29 of 29 |
| error corpus | file | 29,010 of 29,012 |
| error corpus | single | 29,001 of 29,012 |

The remaining differences:
- **`\N{RS}` in test_configparser.py:** a name alias, which `ucnhash` lacks.
- **`from __future__ import braces` and an unknown future feature:** CPython
  raises these in the compiler (`future.c`), not the parser. They're out of
  scope. (They match since Phase A of plan-cpython-bytecode-compiler.md.)
- **9 single-mode inputs** that end with a whitespace-only line and no
  newline, after a `def` or `class` header. C's non-exec tokenizer handles
  that last line differently from the exec-mode tokens the dump starts from,
  and the dump only approximates it. The Java tokenizer will fix this.

**Not covered yet:** tokenizer errors. `_Pypegen_tokenizer_error`, the
unclosed-bracket checks and `TokenSource.error()` are ported but untested,
because a file that fails C's tokenizer can't be dumped. For the same reason,
`pending/tokenizer/reject` now dumps 0 files. They need the Java tokenizer.

### Phase 5: check in the parser with actions; smoke.sh runs the comparison (done)
- [x] `generate.py` translates actions by default (`--skip-actions` for the
      recognizer), and the checked-in `GeneratedParser.java` is the
      full-actions version.
- [x] **The comparison with CPython,** built in Phase 4 as
      `compare_ast.py` + `AstCompare.java` (a canonical text form written by
      both sides) in place of the planned `AstJson.java`. It now tests the
      checked-in parser in `build/classes`.
- [x] **`smoke.sh` runs the comparison** over `../cpython/Lib`, `accept/`,
      `reject/` and `single/`, and over the error corpus in file and single
      mode. Expected differences are in `tests/pegen/compare_known.txt`,
      keyed by mode and content hash, and are reported without failing.
      `smoke.sh` still uses RecognizerSmoke for the 1 MB-stack check of
      `accept/` and `deep/`, and for `pending/`. It also checks that the `--skip-actions`
      recognizer compiles, replacing the old actions-compile step.
- [x] The `pending/actions/` samples moved to `reject/` and `single/reject/`.

### Verification
- **Every phase:** `ant compile`, `tests/pegen/smoke.sh`, and
  `python3 tests/pegen/test_action_translator.py`.
- **Phases 3–5:** the comparison over all of Lib, with identical trees,
  positions included, and CPython's exact errors for the `reject/` samples and
  the error corpus. `smoke.sh` runs it.
- **Before calling it done:** `/adversarial-parser-review`.

## Working notes (for a new session)

### Commands
- **Regenerate:** `ant pegen-gen`, or `python3 src/pegen/tools/generate.py`.
  Add `--skip-actions --output-dir <dir>` for the recognizer, which should
  not be written into `src/`. Setting `PYTHONDONTWRITEBYTECODE=1` keeps
  `__pycache__` out of `src/pegen/tools/`.
- **Smoke test:** `ant compile && tests/pegen/smoke.sh`. It needs Python 3.15
  and uses the in-tree build in `../cpython` (`python.exe` on macOS,
  `python` on Linux) by default.
  Override with `PYTHON=`. It extracts the error corpus into
  `build/pegen-smoke/samples` on every run.
- **Translator tests:** `python3 tests/pegen/test_action_translator.py`.
- **pegen JUnit tests** (after `ant compile`):
  `javac --release 8 -cp build/classes:extlibs/junit-4.10.jar -d $T tests/java/org/python/pegen/*Test.java tests/java/org/python/pegen/compile/*Test.java tests/java/org/python/pegen/lexer/*Test.java`,
  then
  `java -ea -cp build/classes:extlibs/junit-4.10.jar:$T org.junit.runner.JUnitCore org.python.pegen.StringParserTest org.python.pegen.ParsenumberTest org.python.pegen.compile.FutureTest org.python.pegen.compile.AstPreprocessTest org.python.pegen.compile.SymtableTest org.python.pegen.lexer.TokenizerTest`.
  `ant javatest` also picks them up (`**/*Test*.java`).
- **Compare with CPython** (after `ant compile`; run with the 3.15 build):
  `../cpython/python.exe tests/pegen/compare_ast.py [--mode single] [--optimize N] PATH...`
  (`../cpython/python` on Linux).
  It tests the parser in `build/classes`, and compiles its Java driver into
  `build/pegen-compare/` (`--no-build` reuses it). `--known
  tests/pegen/compare_known.txt` lets the listed differences pass; each
  difference printed shows the mode and hash for an entry. For the error
  corpus, first run
  `../cpython/python.exe tests/pegen/extract_samples.py build/pegen-samples`,
  then compare `build/pegen-samples/doctests build/pegen-samples/strings`.
  Lib takes about a minute.
- **Compare symbol tables with CPython** (after `ant compile`):
  `../cpython/python.exe tests/pegen/compare_symtable.py [--mode single] PATH...`
  compares with `_symtable.symtable()` the same way (driver in
  `build/pegen-symtable/`; same `--no-build` and `--known`).
- **Compare tokens with CPython** (after `ant compile`):
  `../cpython/python tests/pegen/compare_tokens.py [--known tests/pegen/compare_known.txt] PATH...`
  diffs the Java tokenizer's tokens with `dump_tokens.py`'s (driver in
  `build/pegen-tokens/`; same `--no-build`).
- **Regenerate the Unicode tables:**
  `../cpython/python src/pegen/tools/generate_unicode.py` (it must run on the
  3.15 build; it writes `src/org/python/pegen/lexer/UnicodeTables.java`).
- **Compiling a generated parser** by hand into a scratch directory:
  generate with `--output-dir $D` (and `--skip-actions` for the recognizer),
  then
  `javac --release 8 -d $OUT $D/*.java $D/ast/*.java $D/ast/base/*.java src/org/python/pegen/{Parser,Token,TokenSource,ActionHelpers}.java src/org/python/pegen/ast/*.java`.

### Conventions
- **Generated files are never hand-edited.** That covers `GeneratedParser.java`,
  `TokenTypes.java`, `AstFactory.java` and everything in `ast/` except the
  hand-written support files listed in CLAUDE.md. Fix the generator instead.
- **Hand-written ports keep C's names and order,** so they diff against the C
  side by side. Where Java can't mirror C, a comment names the C construct.
- **`pending/<reason>/` samples are known gaps:** reported, but they don't
  fail `smoke.sh`. Move a sample out as soon as it passes.
- **The user makes the git commits.** Don't commit, and don't stage (a
  `git mv` stages; use plain `mv`).

### Traps already hit
- **Tokens from Python's `tokenize` aren't the parser's tokens.** Use
  `_tokenize.TokenizerIter(..., extra_tokens=False)`, and even then its end
  columns come from the token text, while C's come from the tokenizer's
  column count. That count includes a NEWLINE's newline and an escaped
  brace's second brace. `tokenize` also doesn't expose `Token.metadata`.
  `dump_tokens.py` corrects all of this.
- **Exec vs single input:** C adds an implicit newline to an unterminated last
  line only for exec input. For single input, the dump drops that NEWLINE and
  orders ENDMARKER, DEDENT..., ENDMARKER as C does (`implyDedents`). The
  quoted error line (`SyntaxError.text`) also only has a newline then.
- **An error's text:** it's the tokenizer's current line, with its newline,
  when the tokenizer is still on the error's line. Otherwise it's the source
  line without the newline. Offsets are clamped against it, so a wrong line
  shows up as a wrong offset.
- **Java 8 target:** Jython builds with `-source/-target 1.8`, so no `var` and no
  `Character.codePointOf`. Check with `javac --release 8`.
- **Case-insensitive file systems (macOS):** ASDL names that differ only in case
  (`expr`/`Expr`, `boolop`/`BoolOp`) can't share a directory. Hence `ast/base/`
  and the `*Type` enums, following Jython 2.7's generated AST.
- **Name clashes:**
  - The AST has a `List` node, so generated AST code writes `java.util.List`
    in full, and other code keeps a single-type `import java.util.List`.
  - `Module` clashes with `java.lang.Module` under two on-demand imports, so
    `AstFactory` uses single-type imports.
- **Java generics:** a direct cast between `List<Object>` and `List<expr>` doesn't
  compile, so go through `List<?>`.
- **C's implicit `void *` conversions** are what the translator's
  `fromVoidPtr` / `is_void` handling reproduces.
- **Columns:** CPython's AST columns are UTF-8 byte offsets. `tokenize` reports
  character offsets, and the dumper converts them.
- **Doubly-bound names:** when an alternative binds the same name twice, the
  action's name refers to the *first* binding, because `dedupe` renames the
  later one. See `translate_action` in `java_generator.py`.
- **Byte positions in string decoding:** CPython's escape-error positions
  ("position 10-12") and warning columns are offsets into the buffer that
  `decode_unicode_with_escapes` builds, where each non-ASCII character becomes
  a 10-byte `\U` escape. `StringParser` therefore indexes `byte[]` buffers,
  not Java chars. Don't "fix" this to character offsets.
- **Pending exceptions vs `error_indicator`:** C's decoders set an exception
  without setting `p->error_indicator`, and callers then convert it with
  `_Pypegen_raise_decode_error`. `Parser.setError` / `errorMatches` /
  `clearError` model `PyErr_SetString` / `PyErr_ExceptionMatches` /
  `PyErr_Clear`. `raiseError` also sets `error_indicator`.
- **Stack depth:** in 3.15, C stops at `MAXSTACK` (6000 rule calls), or
  earlier if the C stack runs low (`_Py_ReachedRecursionLimitWithMargin`).
  Java keeps the MAXSTACK check, and `GeneratedParser.parse()` catches
  `StackOverflowError` in place of the C-stack check. Input nested close to
  MAXSTACK needs about 1.25 MB of Java stack (about 210 bytes per level,
  measured with `-Xint`, the default JIT and `-Xcomp`, in both passes). That's
  more than a 1 MB default, so `Parser.runParser` runs `_PyPegen_run_parser`
  on a cached daemon thread (`pegen-large-stack-N`) with a `STACK_SIZE` (16 MB)
  stack, through `LargeStack.call`, which the compiler stages use too. It
  runs directly when it's already on one, and it waits uninterruptibly. **For the compiler hookup:** the `warning_handler` runs on
  that thread, so it can't use thread-locals such as Jython's ThreadState.
  `tests/pegen/deep/` holds the samples. They aren't in `accept/`, because
  CPython's AST conversion of `deep_invert.py` hits a RecursionError and
  `AstCompare`'s dump is recursive.

---

## Completed: the Java parser generator (original design and plan)

### Context
The goal is to replace Jython's ANTLR parser with a Java port of CPython 3.15's pegen parser (checked out at ../cpython, tag v3.15.0rc2). We need a generator that turns `Grammar/python.gram`, left unmodified, into a Java parser. It should follow `CParserGenerator` closely so the generated Java can be compared with `Parser/parser.c` rule by rule. Decisions made so far:
- The generator is a **Python subclass of pegen's `ParserGenerator`**, so it reuses upstream's grammar parsing and analysis unchanged.
- The **generated Java is checked in**. An ant target regenerates it on demand, so a normal `ant` build doesn't need python3 or ../cpython.

---

### Part 1: How upstream pegen emits a parser

#### Shared base (`parser_generator.py`)
- `__init__`: rejects rule names that start with `_` and checks for dangling references (`RuleCheckingVisitor`). It then runs **`compute_left_recursives`**: `NullableVisitor` computes nullable items, `InitialNamesVisitor` builds the "first-call" graph (each rule's leading items, continuing past nullable ones), and `sccutils` finds the SCCs of that graph. Every rule in a cyclic SCC gets `left_recursive=True`. Exactly one **leader** per SCC is chosen: a rule that appears in *every* cycle. It raises an error if no such rule exists. A rule that calls itself directly is its own leader.
- `collect_rules()`: `KeywordCollectorVisitor` sorts string leaves into two groups. `'kw'` becomes a hard keyword with an id from 500 upward. `"kw"` becomes a soft keyword. The loop then keeps running the **callmaker** over every `NamedItem` until no new rules appear, because generating a call is what creates artificial rules:
  - `artificial_rule_from_rhs` creates `_tmp_N`, for groups that can't be inlined.
  - `artificial_rule_from_repeat` creates `_loop0_N` / `_loop1_N`.
  - `artificial_rule_from_gather` creates `_loop0_N` (the `sep elem` loop) and `_gather_N` (`elem seq`).
- The callmaker keeps a **cache keyed on `str(node)`**, so identical sub-expressions share one artificial rule.
- `local_variable_context` / `dedupe` give every item in an alternative a unique variable name (`a`, `a_1`, …). The default action uses these names.

#### C generator (`c_generator.py`)
Code generation is split between two visitors. `CCallMakerVisitor` turns each item into a `FunctionCall`, a value holding the variable name, function, arguments, return type, nodetype and a `force_true` flag. `CParserGenerator` visits `Rule`, `Rhs` and `Alt` and emits the statements around those calls.

**Rules** (`visit_Rule`): each rule becomes `static T name_rule(Parser *p)`, where T is `asdl_seq *` for loop and gather rules, the declared type otherwise, and `void *` when no type is declared. The body does the following:
1. `add_level()`: `p->level++` checked against MAXSTACK (6000).
2. Returns early if `p->error_indicator` is set.
3. `int _mark = p->mark`.
4. If any alternative's action uses `EXTRA`, it captures `_start_lineno` and `_start_col_offset` from `p->tokens[_mark]`.
5. Each alternative is emitted as a block, followed by the `done:` label, the optional memo insert, and `add_return` (which runs the cleanup statements and does `p->level--`).

Rules whose names end in `without_invalid` save `p->call_invalid_rules`, set it to 0, and restore it on every return path through `cleanup_statements`.

**Alternatives** (`visit_Alt`):
- If the alternative's only item is an `invalid_*` rule, the block is wrapped in `if (p->call_invalid_rules)`.
- Variables are declared from `collect_vars`. `_cut_var` is initialised to 0.
- For a normal rule the items are joined as `if (item && item && …) { action; goto done; }`. For a loop rule they are joined as `while (…) { action; append to _children; _mark = p->mark; }`.
- After the block comes `p->mark = _mark;`, which backtracks. If the alternative contains a cut, it is followed by `if (_cut_var) return NULL;`.

**Items** (the callmaker):
| Grammar | Emitted C condition |
|---|---|
| `NAME`, `NUMBER`, `STRING` | `(name_var = _PyPegen_name_token(p))`, which returns `expr_ty` |
| other token, `'op'`, `'kw'` | `(_literal = _PyPegen_expect_token(p, TYPE))`, which returns `Token *` |
| `"softkw"` | `(_keyword = _PyPegen_expect_soft_keyword(p, "softkw"))` |
| rule | `(x = rule_rule(p))` |
| `[e]` / `e?` | `(_opt_var = call, !p->error_indicator)`: a comma expression that succeeds unless an error is set |
| `e*`, `e+`, `s.e+`, `(…)` | a call to the artificial rule (`_loop0_N_rule`, `_gather_N_rule`, `_tmp_N_rule`). A group with one alternative and one item is inlined |
| `~` (cut) | `(_cut_var = 1)` |
| `&&'x'` / `&&(…)` | `_PyPegen_expect_forced_token` / `_expect_forced_result`, which raise an error instead of failing |

**Lookaheads** (`lookahead_call_helper`): `&e` and `!e` become `_PyPegen_lookahead*(positive, fn, args…)`, passing a function pointer. Which variant is used depends on the item's nodetype and return type: `_with_int` for token types, `_with_string` for soft keywords, `_for_expr` for NAME and rules returning `expr_ty`, `_for_stmt`, or the generic form. The helper saves the mark, calls the function, restores the mark, and returns `(result != NULL) == positive`. A lookahead never binds a variable.

**Memoization**: a rule is memoized only if it has the `(memo)` flag and is **not** left-recursive (`_should_memoize`). A memoized rule checks `_PyPegen_is_memoized(p, rule_type, &_res)` on entry and calls `_PyPegen_insert_memo(p, _mark, …)` at `done:`. Each rule gets a numeric id through `#define rule_type N`, starting at 1000. Memo entries are stored per token, keyed by that id. Loop rules can memoize as well, using `_start_mark`.

**Left recursion**: only the **leader** is special-cased (`_set_up_rule_memoization`), using Warth-style seed growing:
```
name_rule(p):  if memoized → return it
               _mark = _resmark = p->mark
               loop: update_memo(_mark, type, _res)   // seed: first pass stores NULL
                     p->mark = _mark; _raw = name_raw(p)
                     if error → NULL; if _raw==NULL || p->mark <= _resmark → break
                     _resmark = p->mark; _res = _raw
               p->mark = _resmark; return _res
name_raw(p):   the ordinary rule body
```
The other rules in the SCC are neither memoized nor wrapped. They reach the growing seed through the leader's memo entry.

**Action splicing** (`emit_action`): the action text is pasted verbatim as `_res = <action>;`, followed by `if ((_res == NULL || p->error_indicator) && PyErr_Occurred()) { error_indicator = 1; return NULL; }`. The behaviour depends on the action:
- If the action mentions `EXTRA`, the end position (`_end_lineno`, `_end_col_offset`) is first taken from `_PyPegen_get_last_nonnwhitespace_token`. The `EXTRA` macro expands to the four position values plus `p->arena`.
- With **no action**, the default is used. With one variable the result is that variable. In a gather rule it is `_PyPegen_seq_insert_in_front(p, elem, seq)`. Otherwise it is `_PyPegen_dummy_name(p, …)`.
- With `skip_actions`, every alternative returns a dummy value.
- A loop rule appends `_res` to a `_children` array that grows by doubling, then copies it into an `asdl_seq`. A `_loop1_` rule fails if it matched zero items.
- `@header`, `@subheader` and `@trailer` metas are pasted in unchanged. The trailer is formatted with `%(mode)`.

#### Python generator (`python_generator.py`): differences from the C generator
- Uses decorators instead of inline code. Leaders get `@memoize_left_rec`, which applies the same seed-growing loop through `self._cache`. Other left-recursive rules get `@logger`. **Every other rule gets `@memoize`**, ignoring `(memo)`.
- Alternatives use walrus operators, `if (a := self.x()) and …: return action`, then `self._reset(mark)`. A cut becomes `cut = True` followed by `if cut: return None`.
- Lookaheads pass a bound method: `self.positive_lookahead(self.rule, args)`.
- An alternative that references an invalid rule gets an `UNREACHABLE` default action. `LOCATIONS` is replaced textually, much like `EXTRA`.
- The Python generator runs against its own small grammars, not python.gram, whose actions are C. **The Java generator should copy the C generator**, since python.gram's actions, `(memo)` flags and `invalid_*` handling are all written for it.

---

### Part 2: Proposed JavaParserGenerator

#### Where the files go
Java code goes under `src/org/python/pegen/` (per CLAUDE.md). The Python generator tools go under `src/pegen/tools/`:
- `src/pegen/tools/java_generator.py`: the generator. It imports `pegen` from `../cpython/Tools/peg_generator` via `sys.path`.
- `src/pegen/tools/action_translator.py`: rewrites C actions into Java.
- `src/pegen/tools/action_overrides.py`: a table of hand-written Java replacements for the few actions that can't be translated mechanically, keyed by `rule name + str(alt)`.
- `src/pegen/tools/generate.py`: the CLI. Inputs are python.gram and Grammar/Tokens. Outputs are `GeneratedParser.java` and `TokenTypes.java`.
- Generated and checked in: `src/org/python/pegen/GeneratedParser.java` and `src/org/python/pegen/TokenTypes.java`.
- An ant target, `pegen-gen`, regenerates them. It is not a dependency of `compile`. The `.py` files must be excluded from jar packaging.

#### Python classes
```
JavaFunctionCall            # dataclass mirroring c_generator.FunctionCall:
                            #   var, java_type, expr, nodetype, force_true, comment
                            #   __str__ -> the Java boolean condition (see mapping below)

JavaCallMakerVisitor(GrammarVisitor)
    # Mirrors CCallMakerVisitor method for method (visit_NameLeaf/StringLeaf/NamedItem,
    # lookahead_call_helper, visit_Opt/Forced/Rhs/Repeat0/Repeat1/Gather/Group/Cut,
    # _generate_artificial_rule_call with the same str(node) cache), emitting Java.

JavaTypeMap                 # C type -> Java type, e.g. expr_ty->expr, asdl_expr_seq*->List<expr>,
                            # Token*->Token, void*->Object, int->int; one table, easy to change once the Py3 AST exists

ActionTranslator            # works on the already-tokenized action text (see Action translation)

JavaParserGenerator(ParserGenerator, GrammarVisitor)
    generate(filename)              # header, TokenTypes/keyword tables, rule-id constants, rules, trailer
    _setup_keywords / _setup_soft_keywords
    visit_Rule                      # same branching as CParserGenerator.visit_Rule
    _set_up_rule_memoization        # leader wrapper + name_raw()
    _handle_default_rule_body / _handle_loop_rule_body
    _should_memoize                 # "memo" in flags and not left_recursive (same as C)
    visit_Rhs / visit_Alt / handle_alt_normal / handle_alt_loop / join_conditions
    emit_action / emit_default_action / emit_dummy_action
    collect_vars / add_var          # reused as is; only the type strings change
    add_level / remove_level / add_return / cleanup_statements (without_invalid)
```
Method names match `CParserGenerator` so the two can be compared side by side.

#### How C constructs map to Java
- **Rule method**: `T name_rule()`, an instance method on `GeneratedParser`. `GeneratedParser` has a field `final Parser p`, the hand-written port of `pegen.c`, so action text such as `p->x` becomes `p.x`.
- **`goto done`**: a labeled block. `done: { {alt1… break done;} {alt2…} _res = null; }` followed by the memo insert and the return.
- **Variables**: declared as `T v = null;`, which sidesteps Java's definite-assignment checks.
- **Item conditions** (C pointer truthiness becomes explicit null checks):
  - Rule or token: `(a = expr_rule()) != null`
  - Opt: `p.opt(a = x_rule())`. Arguments are evaluated first, and the helper returns `!p.error_indicator`.
  - Cut: `(_cut_var = true)`
- **Lookahead, without lambdas**: `p.lookahead(true, p.mark, foo_rule() != null)`. Java evaluates arguments left to right, so the mark is captured, then the rule runs, and then the helper restores the mark and compares the result with `positive`. This covers every lookahead variant with one allocation-free helper. Token lookaheads use `p.lookaheadToken(positive, TYPE)` and soft keywords use `p.lookaheadSoftKeyword(positive, "kw")`.
- **Left recursion**: the same `name_rule` / `name_raw` split, calling `p.isMemoized`, `p.updateMemo` and `p.insertMemo`. `is_memoized` returns its result through `&_res` in C. In Java it returns a `Memo` entry or null.
- **Loops**: an `ArrayList<Object> _children` filled in the loop body, returned as a `List`.
- **Stack depth**: keep `p.level` checked against MAXSTACK. In addition, the top-level entry point catches `StackOverflowError`.
- **Method count**: about 1.3k rule methods is well under the JVM limits. Constant-pool size is the one risk, and it will be checked on the first generation. If needed, rules can be split across an `abstract` superclass chain by rule index.

#### Action translation (`ActionTranslator`)
Measured against 3.15 python.gram: 495 top-level actions. 170 use `EXTRA`, 39 use `?:`, and 31 contain a C cast. pegen stores actions as space-separated tokens, so the translator works on token sequences, not raw text. Its rules, applied in order:
1. `EXTRA` becomes `_start_lineno, _start_col_offset, _end_lineno, _end_col_offset, p.arena`. The arena is kept as an ignored dummy so the ported helpers' signatures match `action_helpers.c`.
2. `NULL` becomes `null`, and `->` becomes `.`.
3. `X -> v . Kind . field` becomes `((Kind) X).field`, and `X -> kind == Tuple_kind` becomes `(X instanceof Tuple)`.
4. C casts `( asdl_*_seq * )` and `( *_ty )` become Java casts via `JavaTypeMap`, or are dropped where the Java type is already right.
5. Pointer truthiness: in `IDENT ?` or `( IDENT ) ?`, where IDENT is a local variable from `collect_vars`, the condition becomes `IDENT != null ?`.
6. Macros that take a type as their first argument lose it: `CHECK(T, e)`, `CHECK_VERSION(T, …)`, `PyPegen_first_item(a, T)`, `PyPegen_last_item(a, T)`. Macros that implicitly use `p` get it as an explicit argument (`RAISE_*` and friends). All of these become static methods on `ActionHelpers` with the same names.
7. `_PyAST_*`, `_PyPegen_*`, `asdl_seq_*` and `PyErr_Occurred` calls stay as they are and resolve through `import static` of `ActionHelpers` and an `AstFactory`. Operator and context enum constants (`Add`, `Load`, …) resolve through `import static`.
8. Any token the translator doesn't recognise, and no override, makes it **fail loudly**, naming the rule and alternative. The only way around that is an explicit entry in `action_overrides.py`. python.gram is never edited.

The same translator also runs on actions inside groups (the `{ z }` in `a=['->' z=expression { z }]`), because those become artificial rules.

#### Runtime this depends on (hand-written, outside this generator task)
The generated code needs these pieces, stubbed at first so it compiles:
- `org.python.pegen.Parser`: the `pegen.c` runtime, with `expectToken`, `nameToken`, memo helpers, lookahead helpers, `opt`, `error_indicator`, `call_invalid_rules`, `level`, `mark` and the token array.
- `ActionHelpers.java`: the port of `action_helpers.c`, with matching names.
- `AstFactory`: the `_PyAST_*` constructors over a new Python 3 AST. Where that AST comes from, most likely generated from `Parser/Python.asdl`, is a separate decision. `JavaTypeMap` keeps the generator independent of it.
- `TokenTypes.java`, generated from `Grammar/Tokens`, like `Tools/build/generate_token.py`.

#### Implementation order
1. `java_generator.py` with `skip_actions=True` (dummy actions). Generate from python.gram and compile against stub `Parser` and `TokenTypes`. This checks rule, alternative, item, lookahead, memo and left-recursion emission on their own.
2. `ActionTranslator` plus an **untranslated-actions report**, which drives the overrides table.
3. Compile against stub `ActionHelpers` and `AstFactory` signatures that return null.
4. Add the `pegen-gen` ant target and check in the output.

### Verification
- **Generator unit tests** (pytest, run with ../cpython's pegen on `sys.path`): small grammars covering direct left recursion (`expr: expr '+' term | term`), indirect left recursion with a single leader, `(memo)`, `~` cut, `&`/`!` lookaheads, `&&` forced tokens, `[x]`, `x*`, `x+`, `','.x+`, and a group with an action. Assert that the generated Java compiles with `javac`, using a stub runtime.
- **Structural diff against C**: for each rule, compare the generated Java with `Parser/parser.c`. The rule-id order, artificial rule names (`_tmp_N`, `_loop0_N`, `_gather_N`) and memo and left-recursive markings should match one for one. This comes for free from reusing `collect_rules`.
- **Recognizer check** (step 1 above, with actions skipped): once `Parser` and the tokenizer exist, parse CPython's `Lib/**/*.py` and require every file to be accepted.
- **Full oracle** (per CLAUDE.md, after the AST work): compare `ast.dump()` from CPython with our dump over the corpus. Then run `ant regrtest`.
