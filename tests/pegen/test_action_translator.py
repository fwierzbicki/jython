"""Unit tests for src/pegen/tools/action_translator.py.

Run: python3 tests/pegen/test_action_translator.py   (needs Python >= 3.10;
does not need pegen or a CPython checkout)
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src", "pegen", "tools"))

from action_translator import ActionTranslationError, ActionTranslator  # noqa: E402

EXTRA = "_start_lineno, _start_col_offset, _end_lineno, _end_col_offset, p.arena"


def tr(action, **local_types):
    return ActionTranslator().translate(action, local_types)


class TranslateTest(unittest.TestCase):
    def test_extra_null_and_arena(self):
        self.assertEqual(tr("_PyAST_Pass ( EXTRA )"), f"_PyAST_Pass({EXTRA})")
        self.assertEqual(
            tr("_PyAST_ImportFrom ( NULL , b , 0 , EXTRA )", b="asdl_alias_seq*"),
            f"_PyAST_ImportFrom(null, b, 0, {EXTRA})",
        )
        self.assertEqual(tr("_PyAST_Expression ( a , p -> arena )", a="expr_ty"), "_PyAST_Expression(a, p.arena)")

    def test_casts(self):
        # AST types are Object for now, so their casts disappear; sequences become List casts.
        self.assertEqual(
            tr("( asdl_stmt_seq* ) _PyPegen_singleton_seq ( p , a )", a="stmt_ty"),
            "(List<Object>) _PyPegen_singleton_seq(p, a)",
        )
        self.assertEqual(tr("( ( expr_ty ) b )", b="void*"), "(b)")

    def test_macros_take_p_and_drop_type_argument(self):
        self.assertEqual(
            tr("CHECK ( asdl_expr_seq* , _PyPegen_get_exprs ( p , a ) )", a="asdl_seq*"),
            "(List<Object>) CHECK(p, _PyPegen_get_exprs(p, a))",
        )
        self.assertEqual(tr("CHECK ( stmt_ty , _PyAST_Pass ( EXTRA ) )"), f"CHECK(p, _PyAST_Pass({EXTRA}))")
        self.assertEqual(
            tr('CHECK_VERSION ( void* , 10 , "msg" , RAISE_SYNTAX_ERROR ( "m" ) )'),
            'CHECK_VERSION(p, 10, "msg", RAISE_SYNTAX_ERROR(p, "m"))',
        )
        self.assertEqual(
            tr("PyPegen_last_item ( b , expr_ty )", b="asdl_expr_seq*"),
            "PyPegen_last_item(b)",
        )

    def test_union_member_access(self):
        self.assertEqual(tr("a -> v . Name . id", a="expr_ty"), "((Name) a).id")
        self.assertEqual(
            tr("( b ) ? ( ( expr_ty ) b ) -> v . Call . args : NULL", b="void*"),
            "(b) != null ? ((Call) (b)).args : null",
        )

    def test_field_access_casts_to_class_of_c_type(self):
        self.assertEqual(tr("a -> lineno", a="expr_ty"), "((expr) a).lineno")
        self.assertEqual(tr("a -> lineno", a="Token*"), "a.lineno")
        self.assertEqual(tr("b -> kind", b="AugOperator*"), "((AugOperator) b).kind")
        self.assertEqual(tr("a -> key", a="KeyValuePair*"), "((KeyValuePair) a).key")

    def test_kind_comparison_becomes_instanceof(self):
        self.assertEqual(
            tr('e -> kind == Tuple_kind ? "x" : "y"', e="expr_ty"),
            '(e instanceof Tuple) ? "x" : "y"',
        )

    def test_conditions(self):
        self.assertEqual(tr("lazy ? 1 : 0", lazy="expr_ty"), "lazy != null ? 1 : 0")
        self.assertEqual(
            tr('PyErr_Occurred ( ) ? NULL : RAISE_SYNTAX_ERROR_ON_NEXT_TOKEN ( "m" )'),
            'p.errorOccurred() ? null : RAISE_SYNTAX_ERROR_ON_NEXT_TOKEN(p, "m")',
        )
        self.assertEqual(
            tr("_PyPegen_check_barry_as_flufl ( p , tok ) ? NULL : tok", tok="Token*"),
            "_PyPegen_check_barry_as_flufl(p, tok) ? null : tok",
        )
        self.assertEqual(
            tr("asdl_seq_LEN ( a ) == 1 ? asdl_seq_GET ( a , 0 ) : NULL", a="asdl_pattern_seq*"),
            "asdl_seq_LEN(a) == 1 ? asdl_seq_GET(a, 0) : null",
        )

    def test_arithmetic_and_constants(self):
        self.assertEqual(
            tr("RAISE_ERROR_KNOWN_LOCATION ( p , PyExc_SyntaxError , a -> lineno , a -> end_col_offset - 1 , - 1 )",
               a="expr_ty"),
            "RAISE_ERROR_KNOWN_LOCATION(p, PyExc_SyntaxError, ((expr) a).lineno, ((expr) a).end_col_offset - 1, -1)",
        )
        self.assertEqual(tr("_PyAST_BinOp ( a , Add , b , EXTRA )", a="expr_ty", b="expr_ty"),
                         f"_PyAST_BinOp(a, Add, b, {EXTRA})")

    def test_java_keyword_variables_are_renamed(self):
        self.assertEqual(tr("_PyPegen_f ( p , default )", default="expr_ty"), "_PyPegen_f(p, default_)")


class ErrorTest(unittest.TestCase):
    def assertFails(self, action, message, **local_types):
        with self.assertRaises(ActionTranslationError) as cm:
            tr(action, **local_types)
        self.assertIn(message, str(cm.exception))

    def test_unknown_function(self):
        self.assertFails("PyBytes_AS_STRING ( a -> bytes )", "unknown function 'PyBytes_AS_STRING'", a="Token*")

    def test_unknown_identifier(self):
        self.assertFails("_PyAST_Name ( zz )", "unknown identifier 'zz'")

    def test_condition_must_be_decidable(self):
        # A call returning a pointer can't be told apart from one returning a flag.
        self.assertFails("_PyPegen_get_expr_name ( a ) ? 1 : 0", "pointer or a flag", a="expr_ty")

    def test_field_of_untyped_value(self):
        self.assertFails("_PyPegen_f ( p ) -> lineno", "unknown type")

    def test_unsupported_syntax(self):
        self.assertFails("a [ 0 ]", "unexpected text", a="asdl_seq*")
        self.assertFails("_PyAST_Pass ( EXTRA ) ;", "unexpected text")


if __name__ == "__main__":
    unittest.main()
