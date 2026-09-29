"""Java names, literals and types shared by the Java generator and the action translator."""

import re


JAVA_KEYWORDS = frozenset(
    """
    abstract assert boolean break byte case catch char class const continue
    default do double else enum extends final finally float for goto if
    implements import instanceof int interface long native new package private
    protected public return short static strictfp super switch synchronized
    this throw throws transient try void volatile while true false null var
    yield record _
    """.split()
)

# Names the generated code uses for itself; grammar variables must not shadow them.
RESERVED_LOCALS = frozenset({"p"})


def java_ident(name: str) -> str:
    """Rename a grammar variable that would clash with Java or the generated code."""
    if name in JAVA_KEYWORDS or name in RESERVED_LOCALS:
        return name + "_"
    return name


def java_string(value: str) -> str:
    """Quote value as a Java string literal."""
    out = ['"']
    for ch in value:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ord(ch) < 0x20 or ord(ch) > 0x7E:
            out.extend(f"\\u{u:04x}" for u in _utf16_units(ch))
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def _utf16_units(ch: str) -> list[int]:
    data = ch.encode("utf-16-be")
    return [int.from_bytes(data[i : i + 2], "big") for i in range(0, len(data), 2)]


def java_comment(text: str) -> str:
    """Make text safe inside a // comment (javac decodes \\u escapes even there)."""
    return text.replace("\\", "\\\\").replace("\n", " ").replace("\r", " ")


class JavaTypeMap:
    """Maps the C types used in python.gram to Java types.

    The Python 3 AST does not exist yet, so AST node types (expr_ty, ...) and
    the action_helpers structs (CmpopExprPair*, ...) map to Object for now.
    Change the table here once real classes exist; nothing else in the
    generator depends on these names.
    """

    TABLE = {
        "void*": "Object",
        "Token*": "Token",
        "asdl_seq*": "List<Object>",
    }

    def java_type(self, c_type: str | None) -> str:
        if c_type is None:
            return "Object"
        t = c_type.replace(" ", "")
        if t in self.TABLE:
            return self.TABLE[t]
        if re.fullmatch(r"asdl_\w+_seq\*", t):
            return "List<Object>"
        if re.fullmatch(r"\w+_ty", t):
            return "Object"
        if re.fullmatch(r"[A-Z]\w*\*", t):
            return "Object"
        raise ValueError(f"No Java type for C type {c_type!r}")

    def java_class(self, c_type: str) -> str:
        """The class whose fields `x->field` reads, for x of the given C type:
        expr_ty -> expr (the Python.asdl node class), KeyValuePair* ->
        KeyValuePair, Token* -> Token."""
        t = c_type.replace(" ", "")
        if t == "Token*":
            return "Token"
        m = re.fullmatch(r"(\w+)_ty", t) or re.fullmatch(r"([A-Z]\w*)\*", t)
        if m:
            return m.group(1)
        raise ValueError(f"No Java class for fields of C type {c_type!r}")

    def dummy(self, java_type: str) -> str:
        """Expression for _PyPegen_dummy_name(p) usable where java_type is expected."""
        if java_type == "Token":
            return "p.dummyToken()"
        return cast(java_type, "p.dummyName()")


def cast(java_type: str, expr: str) -> str:
    if java_type == "Object":
        return expr
    return f"({java_type}) {expr}"
