"""Hand-written Java for grammar actions ActionTranslator cannot translate.

Keys are (rule name, C action text exactly as pegen stores it: tokens
separated by single spaces).  generate.py fails if an action cannot be
translated and has no entry here, and also if an entry here matches no
action -- so a changed upstream action cannot silently keep a stale
override.  Keep this table small; prefer extending the translator.
"""

OVERRIDES = {
    # C reads the token's raw bytes (a->bytes, a PyBytes object); the Java
    # Token keeps its text in `string`.
    (
        "invalid_kwarg",
        'RAISE_SYNTAX_ERROR_KNOWN_RANGE ( a , b , "cannot assign to %s" , PyBytes_AS_STRING ( a -> bytes ) )',
    ): 'RAISE_SYNTAX_ERROR_KNOWN_RANGE(p, a, b, "cannot assign to %s", a.string)',
}
