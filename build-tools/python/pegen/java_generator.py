"""Java parser generator for pegen.

A port of CPython's Tools/peg_generator/pegen/c_generator.py that emits Java
instead of C.  Class and method names deliberately mirror c_generator.py
(JavaCallMakerVisitor ~ CCallMakerVisitor, JavaParserGenerator ~
CParserGenerator) so the two can be read side by side; the grammar analysis
(nullables, left recursion, leaders, artificial rules) is inherited unchanged
from pegen's ParserGenerator.

The generated class holds a field `p` referring to the runtime
(org.python.pegen.Parser, a port of Parser/pegen.c).  Runtime fields keep
their pegen.h names (p.mark, p.error_indicator, ...) so action text written
for C translates mechanically.
"""

import ast
import os.path
import re
from collections.abc import Callable
from dataclasses import dataclass
from enum import Enum
from typing import IO, Any

from pegen import grammar
from pegen.grammar import (
    Alt,
    Cut,
    Forced,
    Gather,
    GrammarVisitor,
    Group,
    Leaf,
    Lookahead,
    NamedItem,
    NameLeaf,
    NegativeLookahead,
    Opt,
    PositiveLookahead,
    Repeat0,
    Repeat1,
    Rhs,
    Rule,
    StringLeaf,
)
from pegen.parser_generator import ParserGenerator

from action_translator import ActionTranslationError, ActionTranslator
from java_types import JavaTypeMap, cast, java_comment, java_ident, java_string, void_cast


class NodeTypes(Enum):
    NAME_TOKEN = 0
    NUMBER_TOKEN = 1
    STRING_TOKEN = 2
    GENERIC_TOKEN = 3
    KEYWORD = 4
    SOFT_KEYWORD = 5
    CUT_OPERATOR = 6
    F_STRING_CHUNK = 7


BASE_NODETYPES = {
    "NAME": NodeTypes.NAME_TOKEN,
    "NUMBER": NodeTypes.NUMBER_TOKEN,
    "STRING": NodeTypes.STRING_TOKEN,
    "SOFT_KEYWORD": NodeTypes.SOFT_KEYWORD,
}

# Runtime methods standing in for _PyPegen_{name}_token(p).
BASE_TOKEN_METHODS = {
    "NAME": "p.nameToken()",
    "NUMBER": "p.numberToken()",
    "STRING": "p.stringToken()",
    "SOFT_KEYWORD": "p.softKeywordToken()",
}


@dataclass
class JavaFunctionCall:
    """Java counterpart of c_generator.FunctionCall.

    `function` is the complete Java call expression (arguments included).
    str() yields the boolean condition used inside an alternative's
    if/while, which is where C relied on pointer truthiness.
    """

    function: str
    assigned_variable: str | None = None
    assigned_variable_type: str | None = None  # Java type to cast the result to
    return_type: str | None = None  # C type, as in c_generator
    nodetype: NodeTypes | None = None
    force_true: bool = False
    is_condition: bool = False  # function is already a boolean expression
    comment: str | None = None

    def value(self) -> str:
        """The call as a value expression, without assignment or test."""
        return self.function

    def __str__(self) -> str:
        expr = self.function
        if self.assigned_variable:
            if self.assigned_variable_type:
                expr = f"({self.assigned_variable} = {cast(self.assigned_variable_type, expr)})"
            else:
                expr = f"({self.assigned_variable} = {expr})"
        if self.force_true:
            # C: (_opt_var = call, !p->error_indicator)
            expr = f"p.opt({expr})"
        elif not self.is_condition:
            expr = f"{expr} != null"
        if self.comment:
            expr += f"  // {java_comment(self.comment)}"
        return expr


class JavaCallMakerVisitor(GrammarVisitor):
    def __init__(
        self,
        parser_generator: "JavaParserGenerator",
        exact_tokens: dict[str, int],
        non_exact_tokens: set[str],
    ):
        self.gen = parser_generator
        self.exact_tokens = exact_tokens
        self.non_exact_tokens = non_exact_tokens
        self.cache: dict[str, str] = {}

    def keyword_helper(self, keyword: str) -> JavaFunctionCall:
        return JavaFunctionCall(
            assigned_variable="_keyword",
            function=f"p.expectToken({self.gen.keywords[keyword]})",
            return_type="Token *",
            nodetype=NodeTypes.KEYWORD,
            comment=f"token='{keyword}'",
        )

    def soft_keyword_helper(self, value: str) -> JavaFunctionCall:
        return JavaFunctionCall(
            assigned_variable="_keyword",
            function=f"p.expectSoftKeyword({value})",
            return_type="expr_ty",
            nodetype=NodeTypes.SOFT_KEYWORD,
            comment=f"soft_keyword='{value}'",
        )

    def visit_NameLeaf(self, node: NameLeaf) -> JavaFunctionCall:
        name = node.value
        if name in self.non_exact_tokens:
            if name in BASE_NODETYPES:
                return JavaFunctionCall(
                    assigned_variable=f"{name.lower()}_var",
                    function=BASE_TOKEN_METHODS[name],
                    nodetype=BASE_NODETYPES[name],
                    # C says expr_ty, but _PyPegen_string_token returns the Token.
                    return_type="Token *" if name == "STRING" else "expr_ty",
                    comment=name,
                )
            return JavaFunctionCall(
                assigned_variable=f"{name.lower()}_var",
                function=f"p.expectToken({name})",
                nodetype=NodeTypes.GENERIC_TOKEN,
                return_type="Token *",
                comment=f"token='{name}'",
            )

        type = None
        rule = self.gen.all_rules.get(name.lower())
        if rule is not None:
            type = "asdl_seq *" if rule.is_loop() or rule.is_gather() else rule.type

        return JavaFunctionCall(
            assigned_variable=f"{name}_var",
            function=f"{name}_rule()",
            return_type=type,
            comment=f"{node}",
        )

    def visit_StringLeaf(self, node: StringLeaf) -> JavaFunctionCall:
        val = ast.literal_eval(node.value)
        if re.match(r"[a-zA-Z_]\w*\Z", val):  # This is a keyword
            if node.value.endswith("'"):
                return self.keyword_helper(val)
            else:
                return self.soft_keyword_helper(node.value)
        else:
            assert val in self.exact_tokens, f"{node.value} is not a known literal"
            type = self.exact_tokens[val]
            return JavaFunctionCall(
                assigned_variable="_literal",
                function=f"p.expectToken({type})",
                nodetype=NodeTypes.GENERIC_TOKEN,
                return_type="Token *",
                comment=f"token='{val}'",
            )

    def visit_NamedItem(self, node: NamedItem) -> JavaFunctionCall:
        call = self.generate_call(node.item)
        if node.name:
            call.assigned_variable = java_ident(node.name)
        if node.type:
            call.assigned_variable_type = self.gen.type_map.java_type(node.type)
        return call

    def lookahead_call_helper(self, node: Lookahead, positive: bool) -> JavaFunctionCall:
        # C passes a function pointer to one of several _PyPegen_lookahead*
        # variants.  In Java, argument evaluation order is guaranteed left to
        # right, so p.mark is read before the call runs and the helper can
        # restore it afterwards; one helper covers every variant.
        call = self.generate_call(node.node)
        return JavaFunctionCall(
            function=f"p.lookahead({'true' if positive else 'false'}, p.mark, {call.value()} != null)",
            return_type="int",
            is_condition=True,
            comment=f"{node}",
        )

    def visit_PositiveLookahead(self, node: PositiveLookahead) -> JavaFunctionCall:
        return self.lookahead_call_helper(node, True)

    def visit_NegativeLookahead(self, node: NegativeLookahead) -> JavaFunctionCall:
        return self.lookahead_call_helper(node, False)

    def visit_Forced(self, node: Forced) -> JavaFunctionCall:
        if isinstance(node.node, Leaf):
            val = ast.literal_eval(node.node.value)
            assert val in self.exact_tokens, f"{node.node.value} is not a known literal"
            type = self.exact_tokens[val]
            return JavaFunctionCall(
                assigned_variable="_literal",
                function=f"p.expectForcedToken({type}, {java_string(val)})",
                nodetype=NodeTypes.GENERIC_TOKEN,
                return_type="Token *",
                comment=f"forced_token='{val}'",
            )
        if isinstance(node.node, Group):
            call = self.visit(node.node.rhs)
            return JavaFunctionCall(
                assigned_variable="_literal",
                function=f"p.expectForcedResult({call.value()}, {java_string(str(node.node.rhs))})",
                return_type="void *",
                comment=f"forced_token=({node.node.rhs!s})",
            )
        else:
            raise NotImplementedError(f"Forced tokens don't work with {node.node} nodes")

    def visit_Opt(self, node: Opt) -> JavaFunctionCall:
        call = self.generate_call(node.node)
        # As in c_generator, an optional item is void *: C converts it
        # implicitly where it is used; the translator's fromVoidPtr does in Java.
        return JavaFunctionCall(
            assigned_variable="_opt_var",
            function=call.function,
            force_true=True,
            comment=f"{node}",
        )

    def _generate_artificial_rule_call(
        self,
        node: Any,
        prefix: str,
        rule_generation_func: Callable[[], str],
        return_type: str | None = None,
    ) -> JavaFunctionCall:
        node_str = f"{node}"
        key = f"{prefix}_{node_str}"
        if key in self.cache:
            name = self.cache[key]
        else:
            name = rule_generation_func()
            self.cache[key] = name

        return JavaFunctionCall(
            assigned_variable=f"{name}_var",
            function=f"{name}_rule()",
            return_type=return_type,
            comment=node_str,
        )

    def visit_Rhs(self, node: Rhs) -> JavaFunctionCall:
        if node.can_be_inlined:
            return self.generate_call(node.alts[0].items[0])

        return self._generate_artificial_rule_call(
            node,
            "rhs",
            lambda: self.gen.artificial_rule_from_rhs(node),
        )

    def visit_Repeat0(self, node: Repeat0) -> JavaFunctionCall:
        return self._generate_artificial_rule_call(
            node,
            "repeat0",
            lambda: self.gen.artificial_rule_from_repeat(node.node, is_repeat1=False),
            "asdl_seq *",
        )

    def visit_Repeat1(self, node: Repeat1) -> JavaFunctionCall:
        return self._generate_artificial_rule_call(
            node,
            "repeat1",
            lambda: self.gen.artificial_rule_from_repeat(node.node, is_repeat1=True),
            "asdl_seq *",
        )

    def visit_Gather(self, node: Gather) -> JavaFunctionCall:
        return self._generate_artificial_rule_call(
            node,
            "gather",
            lambda: self.gen.artificial_rule_from_gather(node),
            "asdl_seq *",
        )

    def visit_Group(self, node: Group) -> JavaFunctionCall:
        return self.generate_call(node.rhs)

    def visit_Cut(self, node: Cut) -> JavaFunctionCall:
        return JavaFunctionCall(
            assigned_variable="_cut_var",
            return_type="int",
            function="true",
            is_condition=True,
            nodetype=NodeTypes.CUT_OPERATOR,
        )

    def generate_call(self, node: Any) -> JavaFunctionCall:
        return super().visit(node)


DEFAULT_TRAILER = """
public Object parse() {
    try {
        return start_rule();
    } catch (StackOverflowError e) {
        // C: _Py_ReachedRecursionLimitWithMargin; see Parser.MAXSTACK.
        p.stackOverflow();
        return null;
    }
}
"""


class JavaParserGenerator(ParserGenerator, GrammarVisitor):
    def __init__(
        self,
        grammar: grammar.Grammar,
        tokens: dict[int, str],
        exact_tokens: dict[str, int],
        non_exact_tokens: set[str],
        file: IO[str] | None,
        skip_actions: bool = False,
        package: str = "org.python.pegen",
        class_name: str = "GeneratedParser",
        trailer: str | None = None,
        type_map: JavaTypeMap | None = None,
        overrides: dict[tuple[str, str], str] | None = None,
    ):
        super().__init__(grammar, set(tokens.values()), file)
        self.callmakervisitor: JavaCallMakerVisitor = JavaCallMakerVisitor(
            self, exact_tokens, non_exact_tokens
        )
        self.skip_actions = skip_actions
        self.package = package
        self.class_name = class_name
        # The grammar's own @header/@trailer metas are C; the Java versions are supplied here.
        self.trailer = DEFAULT_TRAILER if trailer is None else trailer
        self.type_map = type_map or JavaTypeMap(ast_types=not skip_actions)
        self.translator = ActionTranslator(self.type_map)
        # Hand-written Java for actions the translator can't handle, keyed by
        # (rule name, C action); see action_overrides.py.
        self.overrides = dict(overrides or {})
        self.used_overrides: set[tuple[str, str]] = set()
        # (rule name, C action, reason) for each action that could not be translated.
        self.untranslated: list[tuple[str, str, str]] = []
        self.cleanup_statements: list[str] = []
        self._rule_name = ""  # the rule being emitted
        self._result_type = "Object"  # Java type of _res in the rule being emitted

    def add_level(self) -> None:
        self.print("if (p.level++ == MAXSTACK) {")
        with self.indent():
            self.print("p.stackOverflow();")
        self.print("}")

    def remove_level(self) -> None:
        self.print("p.level--;")

    def add_return(self, ret_val: str) -> None:
        for stmt in self.cleanup_statements:
            self.print(stmt)
        self.remove_level()
        self.print(f"return {ret_val};")

    def generate(self, filename: str) -> None:
        self.collect_rules()
        basename = os.path.basename(filename)
        self.print(f"// @generated by pegen (java_generator.py) from {basename}")
        self.print("// DO NOT EDIT. Generated by build-tools/python/pegen/generate.py (compiler:generateParser)")
        self.print(f"package {self.package};")
        self.print()
        self.print("import java.util.ArrayList;")
        self.print("import java.util.Collections;")
        self.print("import java.util.HashMap;")
        self.print("import java.util.List;")
        self.print("import java.util.Map;")
        self.print()
        if not self.skip_actions:
            # Translated actions call the ports of action_helpers.c/pegen.c and
            # the _PyAST_* constructors under their C names.
            self.print("import org.python.pegen.ActionHelpers.*;")
            self.print("import org.python.pegen.ast.*;")
            self.print("import org.python.pegen.ast.base.*;")
            self.print()
            self.print("import static org.python.pegen.ActionHelpers.*;")
            self.print("import static org.python.pegen.AstFactory.*;")
        self.print("import static org.python.pegen.Parser.MAXSTACK;")
        self.print("import static org.python.pegen.TokenTypes.*;")
        self.print()
        self.print('@SuppressWarnings({"unchecked", "unused"})')
        self.print(f"public class {self.class_name} {{")
        with self.indent():
            self._setup_keywords()
            self._setup_soft_keywords()
            self.print()
            for i, (rulename, rule) in enumerate(self.all_rules.items(), 1000):
                comment = "  // Left-recursive" if rule.left_recursive else ""
                self.print(f"private static final int {rulename}_type = {i};{comment}")
            self.print()
            self.print("private final Parser p;")
            self.print()
            self.print(f"public {self.class_name}(Parser p) {{")
            with self.indent():
                self.print("this.p = p;")
                self.print("p.keywords = KEYWORDS;")
                self.print("p.soft_keywords = SOFT_KEYWORDS;")
            self.print("}")
            for rulename, rule in list(self.all_rules.items()):
                self.print()
                if rule.left_recursive:
                    self.print("// Left-recursive")
                self.visit(rule)
            if self.trailer:
                self.printblock(self.trailer.rstrip("\n"))
        self.print("}")

    def _setup_keywords(self) -> None:
        # C groups keywords by length for its lookup; a HashMap serves the same purpose.
        self.print("static final Map<String, Integer> KEYWORDS;")
        self.print("static {")
        with self.indent():
            self.print("Map<String, Integer> m = new HashMap<>();")
            for keyword_str, keyword_type in self.keywords.items():
                self.print(f"m.put({java_string(keyword_str)}, {keyword_type});")
            self.print("KEYWORDS = Collections.unmodifiableMap(m);")
        self.print("}")

    def _setup_soft_keywords(self) -> None:
        soft_keywords = sorted(self.soft_keywords)
        self.print("static final String[] SOFT_KEYWORDS = {")
        with self.indent():
            for keyword in soft_keywords:
                self.print(f"{java_string(keyword)},")
        self.print("};")

    def _set_up_token_start_metadata_extraction(self) -> None:
        self.print("if (p.mark == p.fill && p.fillToken() < 0) {")
        with self.indent():
            self.print("p.error_indicator = true;")
            self.add_return("null")
        self.print("}")
        self.print("int _start_lineno = p.tokens[_mark].lineno;")
        self.print("int _start_col_offset = p.tokens[_mark].col_offset;")

    def _set_up_token_end_metadata_extraction(self) -> None:
        self.print("Token _token = p.getLastNonWhitespaceToken();")
        self.print("if (_token == null) {")
        with self.indent():
            self.add_return("null")
        self.print("}")
        self.print("int _end_lineno = _token.end_lineno;")
        self.print("int _end_col_offset = _token.end_col_offset;")

    def _check_for_errors(self) -> None:
        self.print("if (p.error_indicator) {")
        with self.indent():
            self.add_return("null")
        self.print("}")

    def _check_memo(self, name: str, result_type: str) -> None:
        # C: if (_PyPegen_is_memoized(p, name_type, &_res)) return _res;
        self.print(f"Parser.Memo _memo = p.isMemoized({name}_type);")
        self.print("if (_memo != null) {")
        with self.indent():
            self.print(f"_res = {cast(result_type, '_memo.node')};")
            self.add_return("_res")
        self.print("}")

    def _set_up_rule_memoization(self, node: Rule, result_type: str) -> None:
        # Left-recursion leader: grow the seed until the match stops getting longer.
        self.print("{")
        with self.indent():
            self.add_level()
            self.print(f"{result_type} _res = null;")
            self._check_memo(node.name, result_type)
            self.print("int _mark = p.mark;")
            self.print("int _resmark = p.mark;")
            self.print("while (true) {")
            with self.indent():
                self.print(f"p.updateMemo(_mark, {node.name}_type, _res);")
                self.print("p.mark = _mark;")
                self.print(f"{result_type} _raw = {node.name}_raw();")
                self.print("if (p.error_indicator) {")
                with self.indent():
                    self.add_return("null")
                self.print("}")
                self.print("if (_raw == null || p.mark <= _resmark) {")
                with self.indent():
                    self.print("break;")
                self.print("}")
                self.print("_resmark = p.mark;")
                self.print("_res = _raw;")
            self.print("}")
            self.print("p.mark = _resmark;")
            self.add_return("_res")
        self.print("}")
        self.print()
        self.print(f"private {result_type} {node.name}_raw()")

    def _should_memoize(self, node: Rule) -> bool:
        return "memo" in node.flags and not node.left_recursive

    def _handle_default_rule_body(self, node: Rule, rhs: Rhs, result_type: str) -> None:
        memoize = self._should_memoize(node)

        self.add_level()
        self._check_for_errors()
        self.print(f"{result_type} _res = null;")
        if memoize:
            self._check_memo(node.name, result_type)
        self.print("int _mark = p.mark;")
        if any(alt.action and "EXTRA" in alt.action for alt in rhs.alts):
            self._set_up_token_start_metadata_extraction()
        # C jumps to a `done:` label on success; Java breaks out of a labeled block.
        self.print("done: {")
        with self.indent():
            self.visit(
                rhs,
                is_loop=False,
                is_gather=node.is_gather(),
                rulename=node.name,
            )
            self.print("_res = null;")
        self.print("}")
        if memoize:
            self.print(f"p.insertMemo(_mark, {node.name}_type, _res);")
        self.add_return("_res")

    def _handle_loop_rule_body(self, node: Rule, rhs: Rhs) -> None:
        memoize = self._should_memoize(node)
        is_repeat1 = node.name.startswith("_loop1")

        self.add_level()
        self._check_for_errors()
        self.print("Object _res = null;")
        if memoize:
            self._check_memo(node.name, "List<Object>")
        self.print("int _mark = p.mark;")
        if memoize:
            self.print("int _start_mark = p.mark;")
        self.print("List<Object> _children = new ArrayList<>();")
        if any(alt.action and "EXTRA" in alt.action for alt in rhs.alts):
            self._set_up_token_start_metadata_extraction()
        self.visit(
            rhs,
            is_loop=True,
            is_gather=node.is_gather(),
            rulename=node.name,
        )
        if is_repeat1:
            self.print("if (_children.isEmpty() || p.error_indicator) {")
            with self.indent():
                self.add_return("null")
            self.print("}")
        self.print("List<Object> _seq = _children;")
        if memoize and node.name:
            self.print(f"p.insertMemo(_start_mark, {node.name}_type, _seq);")
        self.add_return("_seq")

    def visit_Rule(self, node: Rule) -> None:
        is_loop = node.is_loop()
        is_gather = node.is_gather()
        rhs = node.flatten()
        self._rule_name = node.name
        if is_loop or is_gather:
            result_type = "List<Object>"
        else:
            result_type = self.type_map.java_type(node.type)

        for line in str(node).splitlines():
            self.print(f"// {java_comment(line)}")

        self.print(f"private {result_type} {node.name}_rule()")

        if node.left_recursive and node.leader:
            self._set_up_rule_memoization(node, result_type)

        self.print("{")
        with self.indent():
            if node.name.endswith("without_invalid"):
                self.print("boolean _prev_call_invalid = p.call_invalid_rules;")
                self.print("p.call_invalid_rules = false;")
                self.cleanup_statements.append("p.call_invalid_rules = _prev_call_invalid;")

            if is_loop:
                self._result_type = "Object"
                self._handle_loop_rule_body(node, rhs)
            else:
                self._result_type = result_type
                self._handle_default_rule_body(node, rhs, result_type)

            if node.name.endswith("without_invalid"):
                self.cleanup_statements.pop()

        self.print("}")

    def visit_NamedItem(self, node: NamedItem) -> None:
        call = self.callmakervisitor.generate_call(node)
        if call.assigned_variable:
            call.assigned_variable = self.dedupe(call.assigned_variable)
        self.print(call)

    def visit_Rhs(
        self, node: Rhs, is_loop: bool, is_gather: bool, rulename: str | None
    ) -> None:
        if is_loop:
            assert len(node.alts) == 1
        for alt in node.alts:
            self.visit(alt, is_loop=is_loop, is_gather=is_gather, rulename=rulename)

    def join_conditions(self, keyword: str, node: Any) -> None:
        self.print(f"{keyword} (")
        with self.indent():
            first = True
            for item in node.items:
                if first:
                    first = False
                else:
                    self.print("&&")
                self.visit(item)
        self.print(")")

    def translate_action(self, node: Alt) -> str:
        """The Java for node's action, from the overrides table or the translator."""
        key = (self._rule_name, node.action)
        if key in self.overrides:
            self.used_overrides.add(key)
            return self.overrides[key]
        # When a name is bound twice, dedupe() renames the later variable
        # (a -> a_1), so the action's `a` is the first binding.
        local_types: dict[str, str | None] = {}
        for item in node.items:
            if item.name and item.name not in local_types:
                local_types[item.name] = (
                    item.type or self.callmakervisitor.generate_call(item.item).return_type
                )
        try:
            return self.translator.translate(node.action, local_types)
        except (ActionTranslationError, ValueError) as e:
            self.untranslated.append((self._rule_name, node.action, str(e)))
            return "null /* UNTRANSLATED ACTION */"

    def action_problems(self) -> list[str]:
        """After generate(): actions that could not be translated, and
        overrides that matched no action.  Empty when the output is usable."""
        problems = [
            f"{rule}: cannot translate action ({reason}):\n    {action}"
            for rule, action, reason in self.untranslated
        ]
        for rule, action in sorted(set(self.overrides) - self.used_overrides):
            problems.append(f"{rule}: override matches no action:\n    {action}")
        return problems

    def emit_action(self, node: Alt) -> None:
        action = self.translate_action(node)
        self.print(f"_res = {cast(self._result_type, action if self._result_type == 'Object' else f'({action})')};")

        self.print("if ((_res == null || p.error_indicator) && p.errorOccurred()) {")
        with self.indent():
            self.print("p.error_indicator = true;")
            self.add_return("null")
        self.print("}")

    def emit_default_action(self, is_gather: bool, node: Alt) -> None:
        if len(self.local_variable_names) > 1:
            if is_gather:
                assert len(self.local_variable_names) == 2
                self.print(
                    f"_res = _PyPegen_seq_insert_in_front(p, "
                    f"{self.local_variable_names[0]}, {self.local_variable_names[1]});"
                )
            else:
                args = ", ".join(["p"] + self.local_variable_names)
                self.print(f"_res = {void_cast(self._result_type, f'_PyPegen_dummy_name({args})')};")
        else:
            # An untyped (void *) variable may hold the dummy name; C returns it
            # as the rule's type regardless.
            name = self.local_variable_names[0]
            if self.type_map.java_type(self._alt_var_types.get(name)) == "Object":
                self.print(f"_res = {void_cast(self._result_type, name)};")
            else:
                self.print(f"_res = {cast(self._result_type, name)};")

    def emit_dummy_action(self) -> None:
        self.print(f"_res = {self.type_map.dummy(self._result_type)};")

    def handle_alt_normal(self, node: Alt, is_gather: bool, rulename: str | None) -> None:
        self.join_conditions(keyword="if", node=node)
        self.print("{")
        # We have parsed successfully all the conditions for the option.
        with self.indent():
            # Prepare to emit the rule action and do so
            if node.action and "EXTRA" in node.action:
                self._set_up_token_end_metadata_extraction()
            if self.skip_actions:
                self.emit_dummy_action()
            elif node.action:
                self.emit_action(node)
            else:
                self.emit_default_action(is_gather, node)

            # As the current option has parsed correctly, do not continue with the rest.
            self.print("break done;")
        self.print("}")

    def handle_alt_loop(self, node: Alt, is_gather: bool, rulename: str | None) -> None:
        # Condition of the main body of the alternative
        self.join_conditions(keyword="while", node=node)
        self.print("{")
        # We have parsed successfully one item!
        with self.indent():
            # Prepare to emit the rule action and do so
            if node.action and "EXTRA" in node.action:
                self._set_up_token_end_metadata_extraction()
            if self.skip_actions:
                self.emit_dummy_action()
            elif node.action:
                self.emit_action(node)
            else:
                self.emit_default_action(is_gather, node)

            # Collect the result; the rule returns the list once the loop ends.
            self.print("_children.add(_res);")
            self.print("_mark = p.mark;")
        self.print("}")

    def visit_Alt(
        self, node: Alt, is_loop: bool, is_gather: bool, rulename: str | None
    ) -> None:
        if len(node.items) == 1 and str(node.items[0]).startswith("invalid_"):
            self.print(f"if (p.call_invalid_rules) {{ // {java_comment(str(node))}")
        else:
            self.print(f"{{ // {java_comment(str(node))}")
        with self.indent():
            self._check_for_errors()
            # Prepare variable declarations for the alternative
            vars = self.collect_vars(node)
            self._alt_var_types = vars
            for v, var_type in sorted(item for item in vars.items() if item[0] is not None):
                if v == "_cut_var":
                    self.print("boolean _cut_var = false;")
                else:
                    self.print(f"{self.type_map.java_type(var_type)} {v} = null;")

            with self.local_variable_context():
                if is_loop:
                    self.handle_alt_loop(node, is_gather, rulename)
                else:
                    self.handle_alt_normal(node, is_gather, rulename)

            self.print("p.mark = _mark;")
            if "_cut_var" in vars:
                self.print("if (_cut_var) {")
                with self.indent():
                    self.add_return("null")
                self.print("}")
        self.print("}")

    def collect_vars(self, node: Alt) -> dict[str | None, str | None]:
        types = {}
        with self.local_variable_context():
            for item in node.items:
                name, type = self.add_var(item)
                types[name] = type
        return types

    def add_var(self, node: NamedItem) -> tuple[str | None, str | None]:
        call = self.callmakervisitor.generate_call(node.item)
        name = java_ident(node.name) if node.name else call.assigned_variable
        if name is not None:
            name = self.dedupe(name)
        return_type = call.return_type if node.type is None else node.type
        return name, return_type
