#!/bin/sh
# Smoke test for the PEG parser (parser/src/main/java/org/python/pegen).
#
# Compares the parser's output with CPython 3.15's (compare_ast.py: trees,
# errors and warnings, file by file) over CPython's Lib/, the sample
# directories under parser/src/test/pegen/ (accept/, reject/, single/), and the
# syntax-error corpus extract_samples.py takes from CPython's tests.
# Differences listed in compare_known.txt are reported but don't fail.
#
# Samples under pending/ record known gaps; they are run and reported but do
# not fail the script. pending/tokenizer/ needs the Java tokenizer, and
# pending/stack/ is the stack-depth TODO in Parser.MAXSTACK. Move a sample out
# once it passes.
#
# Needs: `./gradlew :parser:compileJava` already run and a CPython checkout (default ../cpython;
# override with CPYTHON=...). PYTHON must be Python >= 3.15; by default an
# in-tree build in $CPYTHON is used if present, else python3.
set -eu

ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
CLASSES=$ROOT/parser/build/classes/java/main
JAVA_TESTS=$ROOT/parser/src/test/java/org/python/pegen
CPYTHON=${CPYTHON:-$ROOT/../cpython}
OUT=$ROOT/parser/build/pegen-smoke
HERE=$ROOT/parser/src/test/pegen

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
javac -nowarn -cp "$CLASSES" -d "$OUT/classes" \
    "$JAVA_TESTS/RecognizerSmoke.java" \
    "$JAVA_TESTS/TokenDump.java"
CLASSPATH="$CLASSES:$OUT/classes"

status=0

# check NAME DUMP_FLAGS DIR JAVA_ARGS...
# Dumps DIR and runs RecognizerSmoke on it; a failure sets status=1.
check() {
    name=$1 dump_flags=$2 dir=$3
    shift 3
    [ -d "$dir" ] || return 0
    echo "== $name"
    "$PYTHON" "$HERE/dump_tokens.py" $dump_flags "$dir" "$OUT/$name.tokens"
    java -cp "$CLASSPATH" "$@" "$OUT/$name.tokens" || status=1
}

# pending NAME DUMP_FLAGS DIR JAVA_ARGS...
# Like check, but for known gaps: reports without failing.
pending() {
    name=$1 dump_flags=$2 dir=$3
    shift 3
    [ -d "$dir" ] || return 0
    echo "== $name (pending: expected to fail)"
    "$PYTHON" "$HERE/dump_tokens.py" $dump_flags "$dir" "$OUT/$name.tokens"
    if java -cp "$CLASSPATH" "$@" "$OUT/$name.tokens"; then
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

# compare_ast.py's Java driver, compiled once for all the runs below.
mkdir -p "$ROOT/parser/build/pegen-compare/classes"
javac -nowarn -cp "$CLASSES" -d "$ROOT/parser/build/pegen-compare/classes" \
    "$JAVA_TESTS/AstCompare.java" \
    "$JAVA_TESTS/TokenDump.java"

compare lib "$CPYTHON/Lib"
compare samples "$HERE/accept" "$HERE/reject"
# Samples parsed as single_input (compile(..., "single")).
compare single-samples --mode single "$HERE/single"

# The syntax-error corpus from CPython's tests, as file and as single input.
"$PYTHON" "$HERE/extract_samples.py" --cpython "$CPYTHON" "$OUT/samples" >/dev/null
compare corpus "$OUT/samples/doctests" "$OUT/samples/strings"
compare single-corpus --mode single "$OUT/samples/doctests" "$OUT/samples/strings"

# Run with a 1 MB stack (the Linux x64 default) so a JVM StackOverflowError
# can't be hidden by a large -Xss.
check accept-small-stack "" "$HERE/accept" -Xss1m $SMOKE --expect accept

pending tokenizer-reject --all "$HERE/pending/tokenizer/reject" -Xss16m $SMOKE --expect reject
pending stack-accept "" "$HERE/pending/stack/accept" -Xss1m $SMOKE --expect accept

# The recognizer (the parser generated with --skip-actions) must keep
# compiling against the runtime.
echo "== recognizer-compile"
if PYTHONDONTWRITEBYTECODE=1 "$PYTHON" "$ROOT/build-tools/python/pegen/generate.py" \
        --cpython "$CPYTHON" --skip-actions --output-dir "$OUT/recognizer" >/dev/null &&
    mkdir -p "$OUT/recognizer/classes" &&
    javac -nowarn -cp "$CLASSES" -d "$OUT/recognizer/classes" \
        "$OUT/recognizer/GeneratedParser.java"; then
    echo "recognizer compiles"
else
    status=1
fi

exit $status
