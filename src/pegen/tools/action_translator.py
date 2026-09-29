"""Translate python.gram's C actions into Java.

pegen stores each action as space-separated C tokens, e.g.
    _PyAST_Attribute ( a , b -> v . Name . id , Load , EXTRA )
ActionTranslator parses the small subset of C the actions use (calls, casts,
?:, ==, -, -> and .) and prints the equivalent Java:
    _PyAST_Attribute(a, ((Name) b).id, Load, _start_lineno, ...)

Anything outside the known vocabulary raises ActionTranslationError naming
the construct; such actions get a hand-written replacement in
action_overrides.py.  Rules, in brief:

- pegen.h macros become static methods of the same name.  Those whose C body
  uses the parser implicitly get `p` as a first argument; a type argument is
  dropped and becomes a cast of the result (CHECK(T, x) -> (T) CHECK(p, x)).
- EXTRA expands as in pegen.h; NULL becomes null; p->arena becomes p.arena.
- x->v.Kind.field becomes ((Kind) x).field; x->kind == Kind_kind becomes
  (x instanceof Kind); other x->field casts x to the Java class of its C type.
- C casts become Java casts through JavaTypeMap (dropped when the Java type
  is Object).
- The condition of ?: must be boolean in Java: a pointer (a grammar variable,
  possibly cast or parenthesized) becomes `x != null`; comparisons and the
  helpers in BOOLEAN_FUNCTIONS are used as they are.
- _PyPegen_*, _PyAST_* and asdl_seq_* calls keep their names and arguments;
  the Java helpers carry the same names as action_helpers.c and pegen.c.
"""

import re
from dataclasses import dataclass

from java_types import JavaTypeMap, cast, java_ident

# pegen.h macros: name -> (adds implicit p, index of a type argument or None).
MACROS = {
    "CHECK": (True, 0),
    "CHECK_NULL_ALLOWED": (True, 0),
    "CHECK_VERSION": (True, 0),
    "PyPegen_first_item": (False, 1),
    "PyPegen_last_item": (False, 1),
    "RAISE_SYNTAX_ERROR": (True, None),
    "RAISE_INDENTATION_ERROR": (True, None),
    "RAISE_SYNTAX_ERROR_ON_NEXT_TOKEN": (True, None),
    "RAISE_SYNTAX_ERROR_KNOWN_RANGE": (True, None),
    "RAISE_SYNTAX_ERROR_KNOWN_LOCATION": (True, None),
    "RAISE_SYNTAX_ERROR_STARTING_FROM": (True, None),
    "RAISE_SYNTAX_ERROR_INVALID_TARGET": (True, None),
}

# Functions (not macros) whose names pass through unchanged.
FUNCTION_PREFIXES = ("_PyPegen_", "_PyAST_", "asdl_seq_")
FUNCTIONS = {"NEW_TYPE_COMMENT", "RAISE_ERROR_KNOWN_LOCATION"}

# Helpers returning C int that the Java helpers declare boolean.
BOOLEAN_FUNCTIONS = {"_PyPegen_check_legacy_stmt", "_PyPegen_check_barry_as_flufl", "PyErr_Occurred"}

# Rewritten calls: C function -> Java expression taking no arguments.
NULLARY_CALLS = {"PyErr_Occurred": "p.errorOccurred()"}

# Identifiers usable as values, resolved by static imports in the generated parser.
CONSTANTS = {
    # Python.asdl enum values (expr_context, boolop, operator, unaryop, cmpop)
    "Load", "Store", "Del",
    "And", "Or",
    "Add", "Sub", "Mult", "MatMult", "Div", "Mod", "Pow", "LShift", "RShift",
    "BitOr", "BitXor", "BitAnd", "FloorDiv",
    "Invert", "Not", "UAdd", "USub",
    "Eq", "NotEq", "Lt", "LtE", "Gt", "GtE", "Is", "IsNot", "In", "NotIn",
    # pegen.h TARGETS_TYPE
    "STAR_TARGETS", "DEL_TARGETS", "FOR_TARGETS",
    # CPython singletons and exception types
    "Py_None", "Py_True", "Py_False", "Py_Ellipsis",
    "PyExc_SyntaxError", "PyExc_IndentationError",
}

EXTRA = ["_start_lineno", "_start_col_offset", "_end_lineno", "_end_col_offset", "p.arena"]

TOKEN_RE = re.compile(
    r'\s*(?:(?P<str>"(?:\\.|[^"\\])*")|(?P<op>->|==|!=|[(),?:*.\-+])'
    r"|(?P<num>\d+)|(?P<id>[A-Za-z_]\w*))"
)


class ActionTranslationError(Exception):
    pass


@dataclass
class Value:
    """A translated subexpression."""

    text: str
    ctype: str | None = None  # C type, when known
    is_boolean: bool = False  # already a Java boolean
    is_variable: bool = False  # a grammar variable, possibly parenthesized or cast
    kind_of: str | None = None  # for x->kind: the Java text of x


def tokenize(action: str) -> list[str]:
    tokens = []
    pos = 0
    action = action.rstrip()
    while pos < len(action):
        m = TOKEN_RE.match(action, pos)
        if not m or m.end() == pos:
            raise ActionTranslationError(f"unexpected text {action[pos:].strip()!r}")
        tokens.append(m.group(m.lastgroup))
        pos = m.end()
    return tokens


def is_c_type_name(tok: str | None) -> bool:
    if tok is None or tok in CONSTANTS or tok in ("NULL", "EXTRA"):
        return False
    return bool(re.fullmatch(r"\w+_ty|asdl_\w+|void|[A-Z]\w*", tok))


class ActionTranslator:
    def __init__(self, type_map: JavaTypeMap | None = None):
        self.type_map = type_map or JavaTypeMap()

    def translate(self, action: str, local_types: dict[str, str | None]) -> str:
        """Translate one action.  local_types maps the alternative's grammar
        variable names to their C types."""
        self.tokens = tokenize(action)
        self.pos = 0
        self.locals = local_types
        value = self.expr()
        if self.pos != len(self.tokens):
            raise ActionTranslationError(f"unexpected {self.peek()!r}")
        return value.text

    # -- token helpers --------------------------------------------------------

    def peek(self, offset: int = 0) -> str | None:
        i = self.pos + offset
        return self.tokens[i] if i < len(self.tokens) else None

    def take(self, expected: str | None = None) -> str:
        tok = self.peek()
        if tok is None or (expected is not None and tok != expected):
            raise ActionTranslationError(f"expected {expected or 'more input'}, found {tok!r}")
        self.pos += 1
        return tok

    # -- grammar --------------------------------------------------------------

    def expr(self) -> Value:
        cond = self.equality()
        if self.peek() != "?":
            return cond
        self.take("?")
        then = self.expr()
        self.take(":")
        other = self.expr()
        return Value(f"{self.condition(cond)} ? {then.text} : {other.text}")

    def condition(self, value: Value) -> str:
        if value.is_boolean:
            return value.text
        if value.is_variable:
            return f"{value.text} != null"
        raise ActionTranslationError(f"cannot tell whether {value.text!r} is a pointer or a flag")

    def equality(self) -> Value:
        left = self.additive()
        if self.peek() not in ("==", "!="):
            return left
        op = self.take()
        right = self.additive()
        kind = re.fullmatch(r"(\w+)_kind", right.text)
        if kind and left.kind_of is not None:
            test = f"({left.kind_of} instanceof {kind.group(1)})"
            return Value(test if op == "==" else f"!{test}", is_boolean=True)
        return Value(f"{left.text} {op} {right.text}", is_boolean=True)

    def additive(self) -> Value:
        left = self.unary()
        while self.peek() in ("-", "+"):
            op = self.take()
            right = self.unary()
            left = Value(f"{left.text} {op} {right.text}")
        return left

    def unary(self) -> Value:
        if self.peek() == "-":
            self.take()
            return Value(f"-{self.unary().text}")
        if self.peek() == "(" and self.is_cast():
            return self.cast()
        return self.postfix()

    def type_length(self, offset: int, closers: tuple[str, ...]) -> int:
        """Number of tokens in a C type name at offset (e.g. `asdl_seq *`)
        if one is there and is followed by one of closers, else 0."""
        tok = self.peek(offset)
        if not is_c_type_name(tok) or tok in self.locals:
            return 0
        length = 2 if self.peek(offset + 1) == "*" else 1
        return length if self.peek(offset + length) in closers else 0

    def take_type(self, length: int) -> str:
        return "".join(self.take() for _ in range(length))

    def is_cast(self) -> bool:
        return self.type_length(1, (")",)) > 0

    def cast(self) -> Value:
        self.take("(")
        ctype = self.take_type(self.type_length(0, (")",)))
        self.take(")")
        operand = self.unary()
        return Value(
            cast(self.type_map.java_type(ctype), operand.text),
            ctype=ctype,
            is_variable=operand.is_variable,
        )

    def postfix(self) -> Value:
        value = self.primary()
        while self.peek() == "->":
            self.take("->")
            value = self.member(value)
        return value

    def member(self, base: Value) -> Value:
        field = self.take()
        if base.text == "p":
            return Value(f"p.{field}")
        if field == "v" and self.peek() == ".":
            # C union access: x->v.Kind.field
            self.take(".")
            node_class = self.take()
            self.take(".")
            name = self.take()
            return Value(f"(({node_class}) {base.text}).{name}")
        if base.ctype is None:
            raise ActionTranslationError(f"unknown type for {base.text!r}->{field}")
        java_class = self.type_map.java_class(base.ctype)
        receiver = base.text if java_class == self.type_map.java_type(base.ctype) else f"(({java_class}) {base.text})"
        return Value(f"{receiver}.{field}", kind_of=base.text if field == "kind" else None)

    def primary(self) -> Value:
        tok = self.take()
        if tok == "(":
            inner = self.expr()
            self.take(")")
            return Value(f"({inner.text})", ctype=inner.ctype, is_boolean=inner.is_boolean,
                         is_variable=inner.is_variable, kind_of=inner.kind_of)
        if tok.startswith('"') or tok.isdigit():
            return Value(tok)
        if not re.fullmatch(r"[A-Za-z_]\w*", tok):
            raise ActionTranslationError(f"unexpected {tok!r}")
        if self.peek() == "(":
            return self.call(tok)
        if tok in self.locals:
            return Value(java_ident(tok), ctype=self.locals[tok], is_variable=True)
        if tok == "p":
            return Value("p", ctype="Parser*")
        if tok == "NULL":
            return Value("null")
        if tok == "EXTRA":
            return Value(", ".join(EXTRA))
        if tok in CONSTANTS or re.fullmatch(r"\w+_kind", tok):
            return Value(tok)
        raise ActionTranslationError(f"unknown identifier {tok!r}")

    def call(self, name: str) -> Value:
        self.take("(")
        args: list[str] = []
        raw_types: list[str | None] = []
        while self.peek() != ")":
            length = self.type_length(0, (",", ")"))
            if length:
                # a bare C type as a macro argument, e.g. CHECK(expr_ty, ...)
                raw_types.append(self.take_type(length))
                args.append(None)
            else:
                args.append(self.expr().text)
                raw_types.append(None)
            if self.peek() == ",":
                self.take(",")
        self.take(")")

        if name in NULLARY_CALLS:
            if args:
                raise ActionTranslationError(f"{name} takes no arguments")
            return Value(NULLARY_CALLS[name], is_boolean=name in BOOLEAN_FUNCTIONS)
        if name in MACROS:
            adds_p, type_index = MACROS[name]
            result_type = "Object"
            if type_index is not None:
                ctype = raw_types[type_index] if type_index < len(raw_types) else None
                if ctype is None:
                    raise ActionTranslationError(f"{name}: expected a type as argument {type_index + 1}")
                result_type = self.type_map.java_type(ctype)
                del args[type_index]
            if None in args:
                raise ActionTranslationError(f"{name}: unexpected type argument")
            if adds_p:
                args.insert(0, "p")
            return Value(cast(result_type, f"{name}({', '.join(args)})"))
        if None in args:
            raise ActionTranslationError(f"{name}: unexpected type argument")
        if name in FUNCTIONS or name.startswith(FUNCTION_PREFIXES):
            return Value(f"{name}({', '.join(args)})", is_boolean=name in BOOLEAN_FUNCTIONS)
        raise ActionTranslationError(f"unknown function {name!r}")
