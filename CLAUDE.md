# Instructions for Claude

## Git

- Never commit or push. Leave all changes uncommitted in the working
  tree; the user will review, commit and push themselves.# PEG parser for Jython 3 (experimental branch: peg-parser-main)

## Goal
Replace the ANTLR parser with a Java port of CPython's pegen-generated parser,
as groundwork for Python 3 support. Target grammar: CPython v3.15.0 (../cpython).
This branch is based on `main` (the Jython 3 rewrite: Gradle, Java 17); the
original peg-parser branch is based on `master` (Jython 2.7, Ant).

## Resuming work
Read plan.md first: its Status section says where the work stands and what is
next, the current plan tracks progress with checkboxes, and "Working notes"
lists the decisions, conventions and traps already hit. Update Status and the
checkboxes at each checkpoint.

## Ground rules
- The user makes the git commits: don't commit or stage (use mv, not git mv).
- Do not modify the existing ANTLR parser; new Java code lives in the Gradle
  subproject ./parser/ (./parser/src/main/java/org/python/pegen/), the Python
  generator tools in ./build-tools/python/pegen/.
- Keep python.gram as close to upstream as possible. Actions are translated
  mechanically by the Java generator; complex helpers are hand-ported from
  Parser/action_helpers.c into a Java class with matching names.
- Generated Java is never hand-edited.
- Correctness oracle: CPython's tokenize module and ast.dump() output,
  diffed against our output over a source corpus.

## Layout
Paths below are relative to the repository root. M = parser/src/main/java/org/python/pegen,
T = parser/src/test/java/org/python/pegen, P = parser/src/test/pegen,
G = build-tools/python/pegen.
- plan.md: design and implementation plan.
- settings.gradle includes the `parser` subproject; parser/parser.gradle builds
  it and has the pegenGen task.
- G/java_generator.py: JavaParserGenerator, a port of pegen's c_generator.py.
- G/action_translator.py: C grammar actions -> Java (fails loudly on anything
  it doesn't know); java_types.py: shared Java naming/type mapping.
- G/action_overrides.py: hand-written Java for the few actions the translator
  can't handle; generate.py rejects unused entries.
- G/asdl_java.py: Python 3 AST classes + AstFactory from CPython's
  Parser/Python.asdl (a port of asdl_c.py; run by generate.py).
- G/generate.py: CLI; writes the generated files below.
- M/GeneratedParser.java, TokenTypes.java, AstFactory.java (_PyAST_*
  constructors), ast/ (node classes; sum-type bases in ast/base/, simple sums
  as *Type enums): generated, checked in.
- M/ast/AST.java, Located.java, AstValueError.java, Complex.java, Bytes.java,
  Singleton.java: hand-written AST support and Constant value classes
  (str=String, int=BigInteger, float=Double).
- M/Parser.java, Token.java, TokenSource.java: hand-written runtime (port of
  Parser/pegen.c); TokenSource also stands in for the tokenizer state the
  parser reads. PythonSyntaxError.java: the exception.
- M/StringParser.java: port of Parser/string_parser.c (str and bytes literal
  decoding, on UTF-8 bytes as in C); UnicodeNames.java: the \N{...} name
  lookup (unicodedata.c _getcode: derived names computed, the rest from the
  JDK's Character.codePointOf, restricted to the names CPython accepts).
- M/ActionHelpers.java: helpers actions call, C names kept; ports of the
  pegen.h macros, action_helpers.c and pegen_errors.c.
- T/StringParserTest.java, ParsenumberTest.java: JUnit 5 tests
  (`./gradlew :parser:test`).
- P/smoke.sh: the test suite: compare_ast.py over Lib, the sample dirs and the
  error corpus (compare_known.txt lists expected differences); pending/ holds
  known gaps that are reported but don't fail.
- P/dump_tokens.py: dumps CPython's C tokens (and source, metadata);
  T/TokenDump.java reads them as a TokenSource.
- T/RecognizerSmoke.java: accept/reject driver (smoke.sh's small-stack and
  pending/ checks).
- P/compare_ast.py + T/AstCompare.java: the correctness oracle; trees, errors
  and warnings vs CPython's, file by file. P/extract_samples.py: the
  syntax-error corpus for it.
- P/test_action_translator.py: translator unit tests
  (python3 parser/src/test/pegen/test_action_translator.py).

## Key upstream files
- ../cpython/Tools/peg_generator/pegen/   (generator; see c_generator.py, python_generator.py)
- ../cpython/Grammar/python.gram, Grammar/Tokens, Parser/Python.asdl
- ../cpython/Parser/tokenizer.c (or lexer/ in newer versions), Parser/action_helpers.c

## Build
./gradlew :parser:compileJava   (./gradlew build builds every subproject)

## Build and test
./gradlew :parser:test

## Regenerate the parser
./gradlew :parser:pegenGen [-Ppegen.python=python3] [-Ppegen.cpython=../cpython]
[-Ppegen.args=...]   (or python3 build-tools/python/pegen/generate.py; needs
../cpython; translates actions by default, --skip-actions gives a recognizer)

## Compare with CPython (after ./gradlew :parser:compileJava)
../cpython/python.exe parser/src/test/pegen/compare_ast.py [--mode single] PATH...
(see plan.md, Commands)

## Smoke test (after ./gradlew :parser:compileJava)
parser/src/test/pegen/smoke.sh   (needs Python 3.15: uses ../cpython/python.exe
if built, else set PYTHON=/path/to/python3.15)
