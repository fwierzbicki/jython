# CPython bytecode compiler for Jython 3 (experimental branch: cpython-bytecode-compiler)

## Goal
Groundwork for Python 3 support, modelled stage for stage on CPython. The
parser, a Java port of CPython's pegen-generated parser that replaces the
ANTLR parser, was built on the peg-parser branch; this branch builds on it
with the compiler front end (future, preprocess, symtable) and then a backend
that compiles to CPython bytecode. Target: CPython v3.15.0 (../cpython).

## Resuming work
There are two plans; each Status section says where that work stands and
what is next, and "Working notes" lists the decisions, conventions and traps
already hit. Update Status and the checkboxes at each checkpoint.
- plan-cpython-bytecode-compiler.md: the current work (compiler front end,
  then backends). Read it first.
- plan-pegen-parser.md: the finished parser, and the commands, conventions
  and traps both share.

## Ground rules
- The user makes the git commits: don't commit or stage (use mv, not git mv).
- Do not modify the existing ANTLR parser; new Java code lives in (./src/org/python/pegen/),
  the Python generator tools in (./src/pegen/tools/).
- Keep python.gram as close to upstream as possible. Actions are translated
  mechanically by the Java generator; complex helpers are hand-ported from
  Parser/action_helpers.c into a Java class with matching names.
- Generated Java is never hand-edited.
- Correctness oracle: CPython's tokenize module and ast.dump() output,
  diffed against our output over a source corpus.

## Layout
- plan-pegen-parser.md, plan-cpython-bytecode-compiler.md: plans and status.
- GLOSSARY.md: canonical terms (CPython's); docs/adr/: architecture decisions.
- src/pegen/tools/java_generator.py: JavaParserGenerator, a port of pegen's c_generator.py.
- src/pegen/tools/action_translator.py: C grammar actions -> Java (fails loudly
  on anything it doesn't know); java_types.py: shared Java naming/type mapping.
- src/pegen/tools/action_overrides.py: hand-written Java for the few actions the
  translator can't handle; generate.py rejects unused entries.
- src/pegen/tools/asdl_java.py: Python 3 AST classes + AstFactory from CPython's
  Parser/Python.asdl (a port of asdl_c.py; run by generate.py).
- src/pegen/tools/generate.py: CLI; writes the generated files below.
- src/org/python/pegen/GeneratedParser.java, TokenTypes.java, AstFactory.java
  (_PyAST_* constructors), ast/ (node classes; sum-type bases in ast/base/,
  simple sums as *Type enums): generated, checked in.
- src/org/python/pegen/ast/AST.java, Located.java, AstValueError.java,
  Complex.java, Bytes.java, Singleton.java: hand-written AST support and
  Constant value classes (str=String, int=BigInteger, float=Double).
- src/org/python/pegen/Parser.java, Token.java, TokenSource.java: hand-written
  runtime (port of Parser/pegen.c); TokenSource also stands in for the
  tokenizer state the parser reads. PythonSyntaxError.java: the exception.
- src/org/python/pegen/StringParser.java: port of Parser/string_parser.c (str
  and bytes literal decoding, on UTF-8 bytes as in C); UnicodeNames.java: the
  \N{...} name lookup (unicodedata.c _getcode over Jython's ucnhash).
- src/org/python/pegen/ActionHelpers.java: helpers actions call, C names kept;
  ports of the pegen.h macros, action_helpers.c and pegen_errors.c.
- src/org/python/pegen/LargeStack.java: runs the parser and the compiler
  stages on a thread with a 16 MB stack, so deep nesting fits.
- src/org/python/pegen/compile/: the compiler front end, hand-ported with C
  names: Compile.java (front half of compile.c, and _PyCompile_AstPreprocess,
  which AstCompare runs after parsing), Future.java (future.c),
  AstPreprocess.java (ast_preprocess.c), Symtable.java (symtable.c),
  Errors.java (errors.c), Ast.java (ast.c), SourceLocation.java. Tests in
  tests/java/org/python/pegen/compile/.
- tests/pegen/smoke.sh: the test suite: compare_ast.py over Lib, the sample
  dirs and the error corpus (compare_known.txt lists expected differences),
  Lib and the samples again at --optimize 1 and 2, compare_symtable.py over
  the same; symtable/ holds samples aimed at symtable;
  deep/ is nesting just under and over MAXSTACK, checked on a 256 KB stack;
  pending/ holds known gaps that are reported but don't fail.
- tests/pegen/dump_tokens.py: dumps CPython's C tokens (and source, metadata);
  tests/java/org/python/pegen/TokenDump.java reads them as a TokenSource.
- tests/java/org/python/pegen/RecognizerSmoke.java: accept/reject driver
  (smoke.sh's small-stack and pending/ checks).
- tests/pegen/compare_ast.py + tests/java/org/python/pegen/AstCompare.java:
  the correctness oracle; trees, errors and warnings vs CPython's, file by
  file, at an optimize level (--optimize N).
  tests/pegen/extract_samples.py: the syntax-error corpus for it.
- tests/pegen/compare_symtable.py + tests/java/org/python/pegen/SymtableCompare.java:
  the same for symbol tables, against CPython's _symtable.symtable().
- tests/pegen/test_action_translator.py: translator unit tests
  (python3 tests/pegen/test_action_translator.py).

## Key upstream files
- ../cpython/Tools/peg_generator/pegen/   (generator; see c_generator.py, python_generator.py)
- ../cpython/Grammar/python.gram, Grammar/Tokens, Parser/Python.asdl
- ../cpython/Parser/tokenizer.c (or lexer/ in newer versions), Parser/action_helpers.c

## Build
ant

## Build and test
ant regrtest

## Regenerate the parser
ant pegen-gen   (or python3 src/pegen/tools/generate.py; needs ../cpython;
translates actions by default, --skip-actions gives a recognizer)

## Compare with CPython (after ant compile)
../cpython/python.exe tests/pegen/compare_ast.py [--mode single] [--optimize N] PATH...
(see plan-pegen-parser.md, Commands)

## Smoke test (after ant compile)
tests/pegen/smoke.sh   (needs Python 3.15: uses ../cpython/python.exe if built,
else set PYTHON=/path/to/python3.15)
