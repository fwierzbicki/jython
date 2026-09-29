# PEG parser for Jython: plan and status

Start here when resuming. **Status** says where the work stands and what is
next. **Current work** is the approved plan with progress ticked off.
**Working notes** has the decisions, traps and commands that the code doesn't
show. The finished generator design is kept at the end for reference.

## Status (2026-09-29)

- **Done: the Java parser generator** (steps 1–4 of the original design below).
  `ant pegen-gen` regenerates the checked-in parser from `../cpython`, which is
  at v3.15.0rc2. Actions are skipped by default, so the checked-in
  `GeneratedParser.java` is a recognizer. `generate.py --actions` translates
  all 538 grammar actions, and that version compiles.
- **In progress: porting the `_PyPegen_*` helpers** (plan below).
  - **Phase 1 (Python 3 AST) is done.**
  - **Phase 2 is partly done:** the value classes, the token dump's text and
    byte columns, and the NAME/NUMBER token functions.
  - **Next:** Phase 2's `StringParser.java` port of `string_parser.c`, then the
    JUnit tests for number and string decoding.
- **Checks passing:** `ant compile`, `tests/pegen/smoke.sh` (exit 0, with all
  2,020 Lib files accepted) and `tests/pegen/test_action_translator.py`
  (15 tests).
- **Not committed:** at the time of writing, the Phase 1 work is uncommitted in
  the working tree. It includes `build.xml` (the `pegen-gen` target),
  `src/pegen/tools/asdl_java.py`, `src/org/python/pegen/ast/`, the edits to
  Parser, Token and ActionHelpers, the tests, and the move of
  `int_over_digit_limit.py` into `tests/pegen/reject/`. Check `git status`.

## Current work: port the _PyPegen_* helpers

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
- [ ] **Port `string_parser.c` into `src/org/python/pegen/StringParser.java`**,
      keeping the C function names (`_PyPegen_decode_string`,
      `_PyPegen_parse_string`, `decode_unicode_with_escapes`, …).
  - Handles prefixes, raw strings, and str and bytes escapes.
  - `\N{...}` lookup goes through `org.python.modules.ucnhash.lookup`, since
    Java 8 has no name lookup. Its Unicode data may be older than 3.15's; track
    any mismatches in `pending/`.
  - An invalid escape produces a SyntaxWarning through the Phase 4 warnings
    channel.
- [ ] JUnit tests in `tests/java/org/python/pegen/` for number and string
      decoding, with cases from CPython's `test_grammar` and
      `test_string_literals`.

### Phase 3: port action_helpers.c in place
Replace each stub in `ActionHelpers.java` with a straight port. Keep the C
names (the static `_set_*_context` and `_make_*` helpers become private static
methods) and the C file's order, so the two diff side by side. Do the groups in
this order, each compiling before the next:
1. [ ] **Sequences and structs:** `singleton_seq`, `seq_insert_in_front`,
   `seq_append_to_end`, `seq_flatten`, `seq_count_dots`, `map_names_to_ids`,
   the `*_pair` builders and `get_*` accessors, `slash_with_default`,
   `star_etc`, `join_sequences`, `augoperator`, `keyword_or_starred`, the
   `seq_*_starred_exprs` pair, `get_last_comprehension_item`,
   `register_stmts`, `interactive_exit`.
   (`dummy_name` is already done.)
2. [ ] **Nodes:** `set_expr_context`, `make_arguments`, `empty_arguments`, the
   `*_def_decorators` pair, `collect_call_seqs`, `join_names_with_dot`,
   `alias_for_star`, `make_module`, `new_type_comment`,
   `add_type_comment_to_arg`, `ensure_real`/`ensure_imaginary`,
   `check_legacy_stmt`, `check_barry_as_flufl`, `checked_from_import`. For the
   barry checks, `Parser` gets a `flags` field; `checked_from_import` sets
   BARRY_AS_BDFL.
3. [ ] **Constants and f/t-strings:** the `constant_from_*` functions,
   `decoded_constant_from_token`, `decode_fstring_part`,
   `_get_resized_exprs`, `joined_str`, `template_str`, `formatted_value`,
   `interpolation`, `setup_full_format_spec`, `check_fstring_conversion`,
   `concatenate_strings` (with `_build_concatenated_*`),
   `concatenate_tstrings`.
4. [ ] **Error helpers:** `get_expr_name`, `get_invalid_target`,
   `arguments_parsing_error`, `nonparen_genexp_in_call`,
   `raise_error_for_missing_comma`.

### Phase 4: errors and warnings
- [ ] **Port `pegen_errors.c`,** replacing the minimal `_PyPegen_raise_error*`
      in `ActionHelpers`:
  - `known_err_token`;
  - byte-to-character offset conversion (`_PyPegen_byte_offset_to_character_offset*`
    in pegen.c);
  - the error's source line;
  - `_Pypegen_set_syntax_error`, for the generic "invalid syntax" and
    unclosed-bracket errors.
- [ ] **`PythonSyntaxError`,** a Java exception with type, msg, lineno, offset,
      end_lineno, end_offset and text. `Parser.getError()` returns it.
- [ ] **`TokenSource.getLine(int lineno)`,** standing in for C's `p->tok`
      buffer.
- [ ] **The retry pass in `Parser.runParser()`:** after a failure, clear the
      memos, set `call_invalid_rules`, parse again, then call
      `_Pypegen_set_syntax_error`. Also catch `AstValueError` and report it as
      ValueError, with no second pass, as C does.
- [ ] **Warnings channel in `Parser`:** category, message and location. This
      covers the SyntaxWarnings for string escapes and for
      `_warn_relative_import_of_lazy`.

### Phase 5: check in the parser with actions; ast.dump oracle
- [ ] `generate.py` translates actions by default, and the checked-in
      `GeneratedParser.java` becomes the full-actions version.
- [ ] **`tests/java/org/python/pegen/AstJson.java`:** writes the tree as JSON
      with explicitly typed values, so Python's `repr()` never has to be
      reimplemented in Java.
- [ ] **`tests/pegen/compare_ast.py`** (run with Python 3.15):
  - Rebuilds `ast` nodes from the JSON and compares
    `ast.dump(include_attributes=True)` with CPython's result.
  - For rejected input, compares error type, msg and positions.
  - Compares warnings, using `warnings.catch_warnings`.
- [ ] **`smoke.sh` runs the oracle** over `../cpython/Lib` and the sample
      directories. Samples in `pending/actions/` move out as they pass.

### Verification
- **Every phase:** `ant compile`, `tests/pegen/smoke.sh` (including its
  actions-compile step), and `python3 tests/pegen/test_action_translator.py`.
- **Phases 3–5:** the oracle over all of Lib, with identical trees, positions
  included, and CPython's exact errors for the `reject/` and
  `pending/actions/` samples.
- **Before calling it done:** `/adversarial-parser-review`.

## Working notes (for a new session)

### Commands
- **Regenerate:** `ant pegen-gen`, or `python3 src/pegen/tools/generate.py`.
  Add `--actions --output-dir <dir>` for the full-actions parser, which should
  never be written into `src/` until Phase 5. Setting
  `PYTHONDONTWRITEBYTECODE=1` keeps `__pycache__` out of `src/pegen/tools/`.
- **Smoke test:** `ant compile && tests/pegen/smoke.sh`. It needs Python 3.15
  and uses `../cpython/python.exe` (an in-tree 3.15.0rc2 build) by default.
  Override with `PYTHON=`.
- **Translator tests:** `python3 tests/pegen/test_action_translator.py`.
- **Compiling the full-actions parser** by hand into a scratch directory:
  generate with `--actions --output-dir $D`, then
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
- **Stack depth:** a TODO on `Parser.MAXSTACK`. On a 1 MB JVM stack, deep but
  valid input can overflow before reaching CPython's limit. Tracked in
  `tests/pegen/pending/stack/`.

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
