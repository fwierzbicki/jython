---
name: adversarial-parser-review
description: Adversarially review parser, tokenizer, generator, or action-helper changes by trying to break them. Use when asked to review, stress-test, or red-team a change on the peg-parser branch.
disable-model-invocation: true
context: fork
---

# Adversarial parser review

Your job is to break the change under review, not to approve it. Assume
it contains bugs until you've failed to find any after real effort.

## Rules
- Every finding must come with a concrete Python input that demonstrates
  it, checked against the oracle: CPython 3.15's tokenize module and
  ast.dump(include_attributes=True). No input, no finding.
- Separate confirmed findings (reproduced) from suspicions (couldn't
  reproduce yet). Never mix them.
- Don't propose style changes. Only divergence from CPython, crashes,
  hangs, and deviations from the ground rules in CLAUDE.md count.
- Save each confirmed input as a regression test.

## Where to attack
- Positions: CPython col_offset values are UTF-8 byte offsets; Java
  strings are UTF-16. Try non-ASCII identifiers and strings, astral
  characters (emoji), and combining characters before the node.
- Tokenizer edges: CRLF and lone CR, form feeds, tabs vs spaces in
  indentation, BOM plus encoding cookie, backslash continuation at EOF,
  unterminated strings and brackets at EOF, empty file, no trailing newline.
- f-/t-strings: nested quotes reusing the outer quote type, deep nesting,
  `=` specifiers, format specs containing braces, backslashes and
  comments inside replacement fields, multiline expressions.
- Soft keywords as ordinary names: `match = 1`, `match(x)`, `case[0]`,
  `type = type`, `lazy = 1`, `_` in and out of patterns.
- Depth: deeply nested parentheses, expressions, and blocks. Java stack
  overflow must become the same error CPython raises, not a crash.
- Performance: inputs that defeat memoization or trigger heavy
  backtracking (long chains of ambiguous prefixes).
- Upstream parity: compare ported helpers against the C they came from
  and look for branches that were dropped or reordered.

## Output
A list of confirmed findings (input, our output, CPython's output, likely
cause), then suspicions, then what you tried that held up.
