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


# Python.asdl's simple sums: Java enums named with a Type suffix (see asdl_java.py).
SIMPLE_SUMS = frozenset({"expr_context", "boolop", "operator", "unaryop", "cmpop"})


class JavaTypeMap:
    """Maps the C types used in python.gram to Java types.

    With ast_types (a parser with actions), AST types map to the generated
    classes of org.python.pegen.ast: expr_ty -> expr, operator_ty ->
    operatorType, asdl_expr_seq* -> List<expr>, and pegen.h structs such as
    KeyValuePair* to the classes of the same name in ActionHelpers.

    Without (a parser that skips actions), every AST type is Object and every
    sequence List<Object>: rules then return a single dummy value, which could
    not be both a List and an expr.
    """

    def __init__(self, ast_types: bool = False):
        self.ast_types = ast_types

    # ASDL builtin types, as asdl_java.py maps them.
    BUILTINS = {"identifier": "String", "string": "String", "constant": "Object"}

    @classmethod
    def ast_name(cls, asdl_type: str) -> str:
        if asdl_type in cls.BUILTINS:
            return cls.BUILTINS[asdl_type]
        return asdl_type + "Type" if asdl_type in SIMPLE_SUMS else asdl_type

    def java_type(self, c_type: str | None) -> str:
        if c_type is None:
            return "Object"
        t = c_type.replace(" ", "")
        if t == "void*":
            return "Object"
        if t == "Token*":
            return "Token"
        if t == "asdl_seq*":
            return "List<Object>"
        m = re.fullmatch(r"asdl_(\w+)_seq\*", t)
        if m:
            if not self.ast_types:
                return "List<Object>"
            # asdl_int_seq holds cmpop values (_PyPegen_get_cmpops).
            element = "cmpop" if m.group(1) == "int" else m.group(1)
            return f"List<{self.ast_name(element)}>"
        m = re.fullmatch(r"(\w+)_ty", t)
        if m:
            return self.ast_name(m.group(1)) if self.ast_types else "Object"
        m = re.fullmatch(r"([A-Z]\w*)\*", t)
        if m:
            return m.group(1) if self.ast_types else "Object"
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


def void_cast(java_type: str, expr: str) -> str:
    """A C void * value used as java_type in a default action. It may be
    _PyPegen_dummy_name's Name, which C reinterprets as any type; the
    ActionHelpers.voidAs* helpers substitute a placeholder of the right type."""
    if java_type == "Object":
        return expr
    if java_type.startswith("List<"):
        return f"({java_type}) (List<?>) voidAsList({expr})"
    return f"voidAs({java_type}.class, {expr})"


def cast(java_type: str, expr: str) -> str:
    if java_type == "Object":
        return expr
    if java_type.startswith("List<"):
        # Java rejects a direct cast between List<Object> and List<expr>.
        return f"({java_type}) (List<?>) {expr}"
    return f"({java_type}) {expr}"
