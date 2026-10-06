"""Extract syntax-error samples from CPython's test suite, for compare_ast.py.

Usage: extract_samples.py [--cpython DIR] OUT

Writes one file per sample into two directories:

- OUT/doctests/: every example in Lib/test/test_syntax.py's doctests (most of
  them SyntaxErrors, with CPython's expected messages next to them there);
- OUT/strings/: every str constant of 1 to 600 characters in Lib/test/**/*.py
  that CPython's compile() rejects with a SyntaxError. Most are code from the
  tests of syntax errors; the rest are prose, which is still input the parser
  must reject exactly as CPython does.

Run it with the Python the parser follows (3.15), then e.g.
compare_ast.py OUT/doctests OUT/strings and compare_ast.py --mode single ....
"""

import argparse
import ast
import doctest
import pathlib
import warnings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cpython", default=pathlib.Path(__file__).resolve().parents[5] / "cpython")
    ap.add_argument("out")
    args = ap.parse_args()
    test_dir = pathlib.Path(args.cpython) / "Lib" / "test"
    out = pathlib.Path(args.out)
    warnings.simplefilter("ignore")

    doctests = out / "doctests"
    doctests.mkdir(parents=True, exist_ok=True)
    tree = ast.parse((test_dir / "test_syntax.py").read_bytes())
    texts = [n.value for n in ast.walk(tree)
             if isinstance(n, ast.Constant) and isinstance(n.value, str) and ">>>" in n.value]
    seen = set()
    parser = doctest.DocTestParser()
    for text in texts:
        for example in parser.get_examples(text):
            if example.source not in seen:
                seen.add(example.source)
                (doctests / f"d{len(seen):04d}.py").write_text(example.source, encoding="utf-8")
    print(f"{len(seen)} doctest examples in {doctests}")

    strings = out / "strings"
    strings.mkdir(parents=True, exist_ok=True)
    seen = set()
    n = 0
    for path in sorted(test_dir.rglob("*.py")):
        try:
            tree = ast.parse(path.read_bytes())
        except (SyntaxError, ValueError):
            continue
        for node in ast.walk(tree):
            v = node.value if isinstance(node, ast.Constant) else None
            if not isinstance(v, str) or not 1 <= len(v) <= 600 or "\0" in v or v in seen:
                continue
            seen.add(v)
            try:
                compile(v, "<unknown>", "exec", ast.PyCF_ONLY_AST)
            except SyntaxError:
                n += 1
                (strings / f"s{n:05d}.py").write_text(v, encoding="utf-8",
                                                      errors="surrogatepass")
            except Exception:
                pass
    print(f"{n} rejected strings in {strings}")


if __name__ == "__main__":
    main()
