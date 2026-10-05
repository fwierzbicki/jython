#!/bin/sh
# Smoke test for the PEG parser (src/org/python/pegen).
#
# Compares the parser's output with CPython 3.15's (compare_ast.py: trees,
# errors and warnings, file by file) over CPython's Lib/, the sample
# directories under tests/pegen/ (accept/, reject/, single/), and the
# syntax-error corpus extract_samples.py takes from CPython's tests. Lib and
# the samples are compared again at optimize levels 1 and 2, where
# preprocess folds constants (ast.parse(..., optimize=N)). The symbol table
# is compared with CPython's _symtable.symtable() (compare_symtable.py) over
# the same files and symtable/, which holds samples aimed at it. The Java
# tokenizer's tokens are compared with CPython's (compare_tokens.py) over
# the same files.
# Differences listed in compare_known.txt are reported but don't fail.
#
# deep/ holds input nested close to (accept/) and past (reject/) the parser's
# MAXSTACK limit, checked on a small stack.
#
# Samples under pending/<reason>/accept|reject record known gaps; they are
# run and reported but do not fail the script (none at present). Move a
# sample out once it passes.
#
# Needs: `ant compile` already run and a CPython checkout (default ../cpython;
# override with CPYTHON=...). PYTHON must be Python >= 3.15; by default an
# in-tree build in $CPYTHON is used if present, else python3.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CPYTHON=${CPYTHON:-$ROOT/../cpython}
OUT=$ROOT/build/pegen-smoke
HERE=$ROOT/tests/pegen

if [ -z "${PYTHON:-}" ]; then
    for candidate in "$CPYTHON/python.exe" "$CPYTHON/python" python3; do
        if command -v "$candidate" >/dev/null 2>&1; then
            PYTHON=$candidate
            break
        fi
    done
fi
if ! "$PYTHON" -c 'import sys; sys.exit(sys.version_info < (3, 15))' 2>/dev/null; then
    echo "smoke.sh: $PYTHON is not Python >= 3.15; set PYTHON=/path/to/python3.15" >&2
    exit 2
fi

mkdir -p "$OUT/classes"
javac -nowarn -cp "$ROOT/build/classes" -d "$OUT/classes" \
    "$ROOT/tests/java/org/python/pegen/RecognizerSmoke.java" \
    "$ROOT/tests/java/org/python/pegen/AstCompare.java"
CLASSPATH="$ROOT/build/classes:$OUT/classes"

status=0

# check NAME DIR JAVA_ARGS...
# Runs RecognizerSmoke on DIR; a failure sets status=1.
check() {
    name=$1 dir=$2
    shift 2
    [ -d "$dir" ] || return 0
    echo "== $name"
    java -cp "$CLASSPATH" "$@" "$dir" || status=1
}

# pending NAME DIR JAVA_ARGS...
# Like check, but for known gaps: reports without failing.
pending() {
    name=$1 dir=$2
    shift 2
    [ -d "$dir" ] || return 0
    echo "== $name (pending: expected to fail)"
    if java -cp "$CLASSPATH" "$@" "$dir"; then
        echo "   all pass now: move $dir out of pending/"
    fi
}

SMOKE=org.python.pegen.RecognizerSmoke

# compare NAME COMPARE_ARGS...
# Runs compare_ast.py; a failure sets status=1.
compare() {
    name=$1
    shift
    echo "== $name"
    "$PYTHON" "$HERE/compare_ast.py" --no-build --known "$HERE/compare_known.txt" "$@" ||
        status=1
}

# compare_symtable NAME COMPARE_ARGS...
# Runs compare_symtable.py; a failure sets status=1.
compare_symtable() {
    name=$1
    shift
    echo "== $name"
    "$PYTHON" "$HERE/compare_symtable.py" --no-build --known "$HERE/compare_known.txt" "$@" ||
        status=1
}

# compare_tokens NAME COMPARE_ARGS...
# Runs compare_tokens.py; a failure sets status=1.
compare_tokens() {
    name=$1
    shift
    echo "== $name"
    "$PYTHON" "$HERE/compare_tokens.py" --no-build --known "$HERE/compare_known.txt" "$@" ||
        status=1
}

# The Java drivers of compare_ast.py, compare_symtable.py and
# compare_tokens.py, compiled once for all the runs below.
mkdir -p "$ROOT/build/pegen-compare/classes" "$ROOT/build/pegen-symtable/classes" \
    "$ROOT/build/pegen-tokens/classes"
javac -nowarn -cp "$ROOT/build/classes" -d "$ROOT/build/pegen-tokens/classes" \
    "$ROOT/tests/java/org/python/pegen/TokenCompare.java"
javac -nowarn -cp "$ROOT/build/classes" -d "$ROOT/build/pegen-compare/classes" \
    "$ROOT/tests/java/org/python/pegen/AstCompare.java"
javac -nowarn -cp "$ROOT/build/classes" -d "$ROOT/build/pegen-symtable/classes" \
    "$ROOT/tests/java/org/python/pegen/SymtableCompare.java" \
    "$ROOT/tests/java/org/python/pegen/AstCompare.java"

compare lib "$CPYTHON/Lib"
compare samples "$HERE/accept" "$HERE/reject" "$HERE/symtable"
# Samples parsed as single_input (compile(..., "single")).
compare single-samples --mode single "$HERE/single"

# Preprocess folding, and docstrings removed at level 2.
for level in 1 2; do
    compare "lib-O$level" --optimize $level "$CPYTHON/Lib"
    compare "samples-O$level" --optimize $level "$HERE/accept" "$HERE/reject" "$HERE/symtable"
    compare "single-samples-O$level" --mode single --optimize $level "$HERE/single"
done

# The syntax-error corpus from CPython's tests, as file and as single input.
"$PYTHON" "$HERE/extract_samples.py" --cpython "$CPYTHON" "$OUT/samples" >/dev/null
compare corpus "$OUT/samples/doctests" "$OUT/samples/strings"
compare single-corpus --mode single "$OUT/samples/doctests" "$OUT/samples/strings"

# The symbol table, as _symtable.symtable() builds it (no preprocess).
compare_symtable symtable-lib "$CPYTHON/Lib"
compare_symtable symtable-samples "$HERE/accept" "$HERE/reject" "$HERE/symtable"
compare_symtable symtable-single-samples --mode single "$HERE/single"
compare_symtable symtable-corpus "$OUT/samples/doctests" "$OUT/samples/strings"
compare_symtable symtable-single-corpus --mode single "$OUT/samples/doctests" \
    "$OUT/samples/strings"

# The Java tokenizer's tokens, against CPython's (dump_tokens.py).
compare_tokens tokens-lib "$CPYTHON/Lib"
compare_tokens tokens-samples "$HERE/accept" "$HERE/reject" "$HERE/symtable" \
    "$HERE/single" "$HERE/deep"
compare_tokens tokens-corpus "$OUT/samples/doctests" "$OUT/samples/strings"

# Run with a 256 KB stack (a quarter of the Linux x64 default) so a JVM
# StackOverflowError can't be hidden by a large -Xss. The parser and the
# compiler stages (preprocess, symtable) run on a thread with their own stack
# (LargeStack.STACK_SIZE), so nesting just under MAXSTACK must parse,
# preprocess and get a symbol table, and just over it must be rejected
# (MemoryError), whatever the caller's.
check accept-small-stack "$HERE/accept" -Xss256k $SMOKE --expect accept
check symtable-small-stack "$HERE/symtable/accept" -Xss256k $SMOKE --expect accept
check deep-accept "$HERE/deep/accept" -Xss256k $SMOKE --expect accept
check deep-reject "$HERE/deep/reject" -Xss256k $SMOKE --expect reject

# The recognizer (the parser generated with --skip-actions) must keep
# compiling against the runtime.
echo "== recognizer-compile"
if PYTHONDONTWRITEBYTECODE=1 "$PYTHON" "$ROOT/src/pegen/tools/generate.py" \
        --cpython "$CPYTHON" --skip-actions --output-dir "$OUT/recognizer" >/dev/null &&
    mkdir -p "$OUT/recognizer/classes" &&
    javac -nowarn -cp "$ROOT/build/classes" -d "$OUT/recognizer/classes" \
        "$OUT/recognizer/GeneratedParser.java"; then
    echo "recognizer compiles"
else
    status=1
fi

exit $status
