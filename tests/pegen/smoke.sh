#!/bin/sh
# Recognizer smoke test for the PEG parser (src/org/python/pegen).
#
# Tokenizes CPython's Lib/ with Python 3.15 (the oracle) and checks that
# GeneratedParser accepts every file that Python can parse, then runs the
# sample directories under tests/pegen/ (accept/, reject/, single/...).
#
# Samples under pending/ record known gaps; they are run and reported but do
# not fail the script. pending/actions/ needs grammar actions,
# pending/tokenizer/ needs the Java tokenizer, and pending/stack/ is the
# stack-depth TODO in Parser.MAXSTACK. Move a sample out once it passes.
#
# Needs: `ant compile` already run and a CPython checkout (default ../cpython;
# override with CPYTHON=...). PYTHON must be Python >= 3.15; by default an
# in-tree build in $CPYTHON is used if present, else python3.
# The recognizer checks assume the checked-in parser skips actions (the default).
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
    "$ROOT/tests/java/org/python/pegen/RecognizerSmoke.java"
CLASSPATH="$ROOT/build/classes:$OUT/classes"

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

check lib "" "$CPYTHON/Lib" -Xss16m $SMOKE --expect accept
check reject --all "$HERE/reject" -Xss16m $SMOKE --expect reject
# Run with a 1 MB stack (the Linux x64 default) so a JVM StackOverflowError
# can't be hidden by a large -Xss.
check accept "" "$HERE/accept" -Xss1m $SMOKE --expect accept
# Samples parsed as single_input (compile(..., "single")).
check single-accept "" "$HERE/single/accept" -Xss16m $SMOKE --mode single --expect accept
check single-reject --all "$HERE/single/reject" -Xss16m $SMOKE --mode single --expect reject

pending actions-reject --all "$HERE/pending/actions/reject" -Xss16m $SMOKE --expect reject
pending actions-single-reject --all "$HERE/pending/actions/single/reject" -Xss16m $SMOKE --mode single --expect reject
pending tokenizer-reject --all "$HERE/pending/tokenizer/reject" -Xss16m $SMOKE --expect reject
pending stack-accept "" "$HERE/pending/stack/accept" -Xss1m $SMOKE --expect accept

# The parser with actions translated must keep compiling against the runtime,
# ActionHelpers and AstFactory (whose unported stubs return null).
echo "== actions-compile"
if PYTHONDONTWRITEBYTECODE=1 "$PYTHON" "$ROOT/src/pegen/tools/generate.py" \
        --cpython "$CPYTHON" --actions --output-dir "$OUT/actions" >/dev/null &&
    mkdir -p "$OUT/actions/classes" &&
    javac -nowarn -cp "$ROOT/build/classes" -d "$OUT/actions/classes" \
        "$OUT/actions/GeneratedParser.java"; then
    echo "parser with actions compiles"
else
    status=1
fi

exit $status
