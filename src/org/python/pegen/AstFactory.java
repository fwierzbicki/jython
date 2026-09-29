package org.python.pegen;

import java.util.List;

/**
 * The _PyAST_* node constructors grammar actions call (CPython's
 * Python/Python-ast.c), under their C names.
 *
 * <p>Placeholder: the Python 3 AST does not exist yet (it will most likely be
 * generated from Parser/Python.asdl). Until then the constructors are stubs
 * returning null, whose signatures were derived once from
 * Include/internal/pycore_ast.h, and only the node types and enum values the
 * generated parser names are declared here.
 *
 * <p>GeneratedParser imports all of this statically.
 */
public final class AstFactory {

    private AstFactory() {}

    // ---- Placeholder node types: only what GeneratedParser reads ----

    /** Base of located nodes: the attributes every one of them has. */
    public static class AST {
        public int lineno, col_offset, end_lineno, end_col_offset;
    }

    public static class expr extends AST {}

    public static class Name extends expr {
        public Object id;
        public Object ctx;
    }

    public static class Call extends expr {
        public Object func;
        public List<Object> args;
        public List<Object> keywords;
    }

    public static class Tuple extends expr {
        public List<Object> elts;
        public Object ctx;
    }

    // ---- Python.asdl enums ----

    public enum expr_context {
        Load, Store, Del
    }

    public enum boolop {
        And, Or
    }

    public enum operator {
        Add, Sub, Mult, MatMult, Div, Mod, Pow, LShift, RShift, BitOr, BitXor, BitAnd, FloorDiv
    }

    public enum unaryop {
        Invert, Not, UAdd, USub
    }

    public enum cmpop {
        Eq, NotEq, Lt, LtE, Gt, GtE, Is, IsNot, In, NotIn
    }

    // Enum values by their bare C names, as actions use them.
    public static final expr_context Load = expr_context.Load, Store = expr_context.Store,
            Del = expr_context.Del;
    public static final boolop And = boolop.And, Or = boolop.Or;
    public static final operator Add = operator.Add, Sub = operator.Sub, Mult = operator.Mult,
            MatMult = operator.MatMult, Div = operator.Div, Mod = operator.Mod,
            Pow = operator.Pow, LShift = operator.LShift, RShift = operator.RShift,
            BitOr = operator.BitOr, BitXor = operator.BitXor, BitAnd = operator.BitAnd,
            FloorDiv = operator.FloorDiv;
    public static final unaryop Invert = unaryop.Invert, Not = unaryop.Not, UAdd = unaryop.UAdd,
            USub = unaryop.USub;
    public static final cmpop Eq = cmpop.Eq, NotEq = cmpop.NotEq, Lt = cmpop.Lt, LtE = cmpop.LtE,
            Gt = cmpop.Gt, GtE = cmpop.GtE, Is = cmpop.Is, IsNot = cmpop.IsNot, In = cmpop.In,
            NotIn = cmpop.NotIn;

    // ---- Node constructors (stubs) ----

    public static Object _PyAST_AnnAssign(Object target, Object annotation, Object value, int simple, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Assert(Object test, Object msg, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Assign(List<Object> targets, Object value, Object type_comment, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_AsyncFor(Object target, Object iter, List<Object> body, List<Object> orelse, Object type_comment, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_AsyncFunctionDef(Object name, Object args, List<Object> body, List<Object> decorator_list, Object returns, Object type_comment, List<Object> type_params, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_AsyncWith(List<Object> items, List<Object> body, Object type_comment, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Attribute(Object value, Object attr, Object ctx, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_AugAssign(Object target, Object op, Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Await(Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_BinOp(Object left, Object op, Object right, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_BoolOp(Object op, List<Object> values, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Break(int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Call(Object func, List<Object> args, List<Object> keywords, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_ClassDef(Object name, List<Object> bases, List<Object> keywords, List<Object> body, List<Object> decorator_list, List<Object> type_params, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Compare(Object left, List<Object> ops, List<Object> comparators, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Constant(Object value, Object kind, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Continue(int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Delete(List<Object> targets, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Dict(List<Object> keys, List<Object> values, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_DictComp(Object key, Object value, List<Object> generators, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_ExceptHandler(Object type, Object name, List<Object> body, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Expr(Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Expression(Object body, Object arena) {
        return null;
    }

    public static Object _PyAST_For(Object target, Object iter, List<Object> body, List<Object> orelse, Object type_comment, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_FunctionDef(Object name, Object args, List<Object> body, List<Object> decorator_list, Object returns, Object type_comment, List<Object> type_params, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_FunctionType(List<Object> argtypes, Object returns, Object arena) {
        return null;
    }

    public static Object _PyAST_GeneratorExp(Object elt, List<Object> generators, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Global(List<Object> names, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_If(Object test, List<Object> body, List<Object> orelse, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_IfExp(Object test, Object body, Object orelse, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Import(List<Object> names, int is_lazy, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_ImportFrom(Object module, List<Object> names, int level, int is_lazy, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Interactive(List<Object> body, Object arena) {
        return null;
    }

    public static Object _PyAST_Lambda(Object args, Object body, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_List(List<Object> elts, Object ctx, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_ListComp(Object elt, List<Object> generators, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Match(Object subject, List<Object> cases, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_MatchAs(Object pattern, Object name, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_MatchClass(Object cls, List<Object> patterns, List<Object> kwd_attrs, List<Object> kwd_patterns, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_MatchMapping(List<Object> keys, List<Object> patterns, Object rest, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_MatchOr(List<Object> patterns, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_MatchSequence(List<Object> patterns, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_MatchSingleton(Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_MatchStar(Object name, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_MatchValue(Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_NamedExpr(Object target, Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Nonlocal(List<Object> names, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_ParamSpec(Object name, Object default_value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Pass(int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Raise(Object exc, Object cause, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Return(Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Set(List<Object> elts, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_SetComp(Object elt, List<Object> generators, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Slice(Object lower, Object upper, Object step, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Starred(Object value, Object ctx, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Subscript(Object value, Object slice, Object ctx, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Try(List<Object> body, List<Object> handlers, List<Object> orelse, List<Object> finalbody, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_TryStar(List<Object> body, List<Object> handlers, List<Object> orelse, List<Object> finalbody, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Tuple(List<Object> elts, Object ctx, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_TypeAlias(Object name, List<Object> type_params, Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_TypeVar(Object name, Object bound, Object default_value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_TypeVarTuple(Object name, Object default_value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_UnaryOp(Object op, Object operand, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_While(Object test, List<Object> body, List<Object> orelse, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_With(List<Object> items, List<Object> body, Object type_comment, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_Yield(Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_YieldFrom(Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_alias(Object name, Object asname, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_arg(Object arg, Object annotation, Object type_comment, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_comprehension(Object target, Object iter, List<Object> ifs, int is_async, Object arena) {
        return null;
    }

    public static Object _PyAST_keyword(Object arg, Object value, int lineno, int col_offset, int end_lineno, int end_col_offset, Object arena) {
        return null;
    }

    public static Object _PyAST_match_case(Object pattern, Object guard, List<Object> body, Object arena) {
        return null;
    }

    public static Object _PyAST_withitem(Object context_expr, Object optional_vars, Object arena) {
        return null;
    }
}
