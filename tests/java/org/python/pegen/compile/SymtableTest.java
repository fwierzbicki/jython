package org.python.pegen.compile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.python.pegen.PythonSyntaxError;
import org.python.pegen.ast.AnnAssign;
import org.python.pegen.ast.Assign;
import org.python.pegen.ast.AsyncFunctionDef;
import org.python.pegen.ast.Await;
import org.python.pegen.ast.Call;
import org.python.pegen.ast.ClassDef;
import org.python.pegen.ast.Constant;
import org.python.pegen.ast.Expr;
import org.python.pegen.ast.Expression;
import org.python.pegen.ast.FunctionDef;
import org.python.pegen.ast.If;
import org.python.pegen.ast.ImportFrom;
import org.python.pegen.ast.ListComp;
import org.python.pegen.ast.Module;
import org.python.pegen.ast.Name;
import org.python.pegen.ast.Pass;
import org.python.pegen.ast.Return;
import org.python.pegen.ast.TypeVar;
import org.python.pegen.ast.UnaryOp;
import org.python.pegen.ast.Yield;
import org.python.pegen.ast.alias;
import org.python.pegen.ast.arg;
import org.python.pegen.ast.arguments;
import org.python.pegen.ast.comprehension;
import org.python.pegen.ast.expr_contextType;
import org.python.pegen.ast.unaryopType;
import org.python.pegen.ast.base.expr;
import org.python.pegen.ast.base.mod;
import org.python.pegen.ast.base.stmt;
import org.python.pegen.ast.base.type_param;
import org.python.pegen.compile.Symtable.BlockKey;
import org.python.pegen.compile.Symtable.PySTEntryObject;
import org.python.pegen.compile.Symtable._Py_block_ty;
import org.python.pegen.compile.Symtable._Py_comprehension_ty;

/**
 * Symtable, for what compare_symtable.py can't see: the ste_* flags the
 * _symtable module doesn't show (checked against what CPython 3.15's code
 * objects show of them: co_flags, co_cellvars), the keys blocks are looked
 * up by, the symbol table compiler_setup builds after preprocess,
 * SyntaxError.text read from a real file, running out of stack, and name
 * mangling.
 *
 * <p>Trees are built by hand; locations matter only where an error is
 * checked, and are CPython's there.
 */
public class SymtableTest {

    @SafeVarargs
    private static <T> List<T> list(T... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static Name load(String id) {
        return new Name(id, expr_contextType.Load, 1, 0, 1, id.length());
    }

    private static Name store(String id) {
        return new Name(id, expr_contextType.Store, 1, 0, 1, id.length());
    }

    private static arg arg(String name, expr annotation) {
        return new arg(name, annotation, null, 1, 0, 1, name.length());
    }

    private static arguments args(List<arg> args, arg vararg, arg kwarg) {
        return new arguments(list(), args, vararg, list(), list(), kwarg, list());
    }

    private static arguments noArgs() {
        return args(list(), null, null);
    }

    private static Expr expr(expr e) {
        return new Expr(e, 1, 0, 1, 1);
    }

    private static Expr docstring() {
        return expr(new Constant("doc", null, 1, 0, 1, 5));
    }

    private static Module module(stmt... body) {
        return new Module(list(body), Collections.emptyList());
    }

    private static Symtable build(mod m) {
        return build(m, 0);
    }

    private static Symtable build(mod m, int flags) {
        return Symtable._Py_SymtableStringObjectFlags(m, "<unknown>",
                new Compile.PyCompilerFlags(flags));
    }

    /**
     * class C:
     *     "doc"
     *     def m(self, *a, **k):
     *         "doc"
     *         yield super()
     */
    @Test
    public void classAndMethodFlags() {
        FunctionDef m = new FunctionDef("m",
                args(list(arg("self", null)), arg("a", null), arg("k", null)),
                list(docstring(),
                        expr(new Yield(new Call(load("super"), list(), list(), 1, 0, 1, 1),
                                1, 0, 1, 1))),
                list(), null, null, list(), 1, 0, 1, 1);
        ClassDef c = new ClassDef("C", list(), list(), list(docstring(), m), list(), list(),
                1, 0, 1, 1);
        Symtable st = build(module(c));

        assertFalse(st.st_top.ste_has_docstring);
        PySTEntryObject cls = st._PySymtable_Lookup(new BlockKey(c));
        assertEquals(_Py_block_ty.ClassBlock, cls.ste_type);
        assertTrue(cls.ste_has_docstring);
        // co_cellvars ('__class__', '__classdict__'): m uses super, and m's
        // annotation block (a child of C) uses __classdict__.
        assertTrue(cls.ste_needs_class_closure);
        assertTrue(cls.ste_needs_classdict);
        assertFalse(cls.ste_has_conditional_annotations);

        PySTEntryObject meth = st._PySymtable_Lookup(new BlockKey(m));
        // co_flags VARARGS, VARKEYWORDS, GENERATOR, HAS_DOCSTRING, METHOD
        assertTrue(meth.ste_method);
        assertTrue(meth.ste_generator);
        assertFalse(meth.ste_coroutine);
        assertTrue(meth.ste_varargs);
        assertTrue(meth.ste_varkeywords);
        assertTrue(meth.ste_has_docstring);
        assertFalse(meth.ste_returns_value);
        assertFalse(meth.ste_nested);
        assertEquals(Arrays.asList("self", "a", "k"), meth.ste_varnames);
        assertEquals(Symtable.FREE, Symtable._PyST_GetScope(meth, "__class__"));
    }

    /**
     * async def f():
     *     return [x async for x in y]
     */
    @Test
    public void asyncFunctionAndInlinedComprehension() {
        ListComp comp = new ListComp(load("x"),
                list(new comprehension(store("x"), load("y"), list(), 1)), 1, 0, 1, 1);
        AsyncFunctionDef f = new AsyncFunctionDef("f", noArgs(),
                list(new Return(comp, 1, 0, 1, 1)), list(), null, null, list(), 1, 0, 1, 1);
        Symtable st = build(module(f));

        PySTEntryObject fn = st._PySymtable_Lookup(new BlockKey(f));
        // co_flags COROUTINE
        assertTrue(fn.ste_coroutine);
        assertFalse(fn.ste_generator);
        assertTrue(fn.ste_returns_value);
        assertFalse(fn.ste_method);

        PySTEntryObject lc = st._PySymtable_Lookup(new BlockKey(comp));
        assertEquals(_Py_comprehension_ty.ListComprehension, lc.ste_comprehension);
        assertTrue(lc.ste_comp_inlined);
        assertTrue(lc.ste_coroutine);
        assertFalse(fn.ste_children.contains(lc));
        assertEquals(Symtable.LOCAL, Symtable._PyST_GetScope(fn, "x"));
    }

    /** await y, at module level: only with PyCF_ALLOW_TOP_LEVEL_AWAIT. */
    @Test
    public void topLevelAwait() {
        Symtable st = build(module(expr(new Await(load("y"), 1, 0, 1, 7))),
                Compile.PyCF_ALLOW_TOP_LEVEL_AWAIT);
        assertTrue(st.st_top.ste_coroutine);
        try {
            build(module(expr(new Await(load("y"), 1, 0, 1, 7))));
            fail("expected a SyntaxError");
        } catch (PythonSyntaxError e) {
            assertEquals("'await' outside function", e.msg);
            assertEquals(1, e.offset);
            assertEquals(8, e.end_offset);
        }
    }

    /**
     * x: int
     * def f[T: int = str](a: T): pass
     *
     * <p>The keys codegen looks blocks up by, the two made from an address +
     * 1 included.
     */
    @Test
    public void blockKeys() {
        AnnAssign ann = new AnnAssign(store("x"), load("int"), null, 1, 1, 0, 1, 6);
        TypeVar t = new TypeVar("T", load("int"), load("str"), 1, 6, 1, 18);
        List<type_param> type_params = list((type_param) t);
        arguments a = args(list(arg("a", load("T"))), null, null);
        FunctionDef f = new FunctionDef("f", a, list(new Pass(1, 0, 1, 4)), list(), null, null,
                type_params, 1, 0, 1, 1);
        Module m = module(ann, f);
        Symtable st = build(m);

        PySTEntryObject top = st.st_top;
        assertSame(top, st._PySymtable_Lookup(new BlockKey(m)));
        PySTEntryObject moduleAnnotations = st._PySymtable_Lookup(new BlockKey(top.ste_id, 1));
        assertSame(top.ste_annotation_block, moduleAnnotations);
        assertEquals(_Py_block_ty.AnnotationBlock, moduleAnnotations.ste_type);
        // Module-level annotations are always conditional: co_cellvars
        // ('__conditional_annotations__',).
        assertTrue(top.ste_has_conditional_annotations);
        assertTrue(top.ste_annotations_used);

        PySTEntryObject generic = st._PySymtable_Lookup(new BlockKey(type_params));
        assertEquals(_Py_block_ty.TypeParametersBlock, generic.ste_type);
        PySTEntryObject bound = st._PySymtable_Lookup(new BlockKey(t));
        assertEquals(_Py_block_ty.TypeVariableBlock, bound.ste_type);
        assertEquals("a TypeVar bound", bound.ste_scope_info);
        PySTEntryObject default_ = st._PySymtable_Lookup(new BlockKey(t, 1));
        assertEquals("a TypeVar default", default_.ste_scope_info);

        PySTEntryObject annotate = st._PySymtable_Lookup(new BlockKey(a));
        assertEquals(_Py_block_ty.AnnotationBlock, annotate.ste_type);
        assertEquals("__annotate__", annotate.ste_name);
        assertEquals("f", annotate.ste_function_name);
        assertTrue(annotate.ste_annotations_used);
        assertEquals(_Py_block_ty.FunctionBlock, st._PySymtable_Lookup(new BlockKey(f)).ste_type);
        assertNull(st._PySymtable_LookupOptional(new BlockKey(f, 1)));
    }

    /**
     * class C:
     *     if x:
     *         a: int
     */
    @Test
    public void conditionalAnnotationsInClass() {
        AnnAssign ann = new AnnAssign(store("a"), load("int"), null, 1, 1, 0, 1, 6);
        ClassDef c = new ClassDef("C", list(), list(),
                list(new If(load("x"), list(ann), list(), 1, 0, 1, 1)), list(), list(),
                1, 0, 1, 1);
        Symtable st = build(module(c));
        PySTEntryObject cls = st._PySymtable_Lookup(new BlockKey(c));
        // co_cellvars ('__classdict__', '__conditional_annotations__')
        assertTrue(cls.ste_has_conditional_annotations);
        assertTrue(cls.ste_needs_classdict);
        assertFalse(cls.ste_needs_class_closure);
        assertTrue(cls.ste_annotation_block.ste_can_see_class_scope);
        assertFalse(st.st_top.ste_has_conditional_annotations);
    }

    /**
     * class C:
     *     x = [__class__ for _ in y]
     *
     * <p>The comprehension is inlined into the class body, where __class__
     * may not be free (co_cellvars () and __class__ in co_names), so
     * inline_comprehension removes it from the comprehension's symbols:
     * compare_symtable.py can't see that, as an inlined block isn't in the
     * tree.
     */
    @Test
    public void dunderClassInComprehensionInClass() {
        ListComp comp = new ListComp(load("__class__"),
                list(new comprehension(store("_"), load("y"), list(), 0)), 1, 0, 1, 1);
        ClassDef c = new ClassDef("C", list(), list(),
                list(new Assign(list(store("x")), comp, null, 1, 0, 1, 1)), list(), list(),
                1, 0, 1, 1);
        Symtable st = build(module(c));
        PySTEntryObject cls = st._PySymtable_Lookup(new BlockKey(c));
        PySTEntryObject lc = st._PySymtable_Lookup(new BlockKey(comp));
        assertTrue(lc.ste_comp_inlined);
        assertFalse(lc.ste_symbols.containsKey("__class__"));
        assertEquals(Symtable.GLOBAL_IMPLICIT, Symtable._PyST_GetScope(cls, "__class__"));
        assertFalse(cls.ste_needs_class_closure);
    }

    /**
     * from __future__ import annotations
     * if x: from __future__ import division     (on the same line)
     *
     * <p>check_import_from's test for a later import on the line the last
     * future import ends on. Source can't put a compound statement there, so
     * only a tree made by hand reaches it.
     */
    @Test
    public void futureImportLaterOnSameLine() {
        ImportFrom first = new ImportFrom("__future__",
                list(new alias("annotations", null, 1, 23, 1, 34)), 0, 0, 1, 0, 1, 34);
        ImportFrom second = new ImportFrom("__future__",
                list(new alias("division", null, 1, 64, 1, 72)), 0, 0, 1, 42, 1, 72);
        If i = new If(load("x"), list(second), list(), 1, 36, 1, 72);
        try {
            build(module(first, i));
            fail("expected a SyntaxError");
        } catch (PythonSyntaxError e) {
            assertEquals("from __future__ imports must occur at the beginning of the file",
                    e.msg);
            assertEquals(43, e.offset);
            assertEquals(73, e.end_offset);
        }
    }

    /**
     * new_compiler builds the symbol table of the preprocessed tree, where
     * __debug__ is a constant; _symtable sees the tree as parsed.
     */
    @Test
    public void compilerSymtableIsBuiltAfterPreprocess() {
        Compile c = Compile.new_compiler(module(expr(load("__debug__"))), "<unknown>", null, 0,
                null, w -> true);
        assertNotNull(c.c_st);
        assertFalse(c.c_st.st_top.ste_symbols.containsKey("__debug__"));
        Symtable st = build(module(expr(load("__debug__"))));
        assertEquals(Symtable.USE | Symtable.GLOBAL_IMPLICIT << Symtable.SCOPE_OFFSET,
                Symtable._PyST_GetSymbol(st.st_top, "__debug__"));
    }

    /** "def f(a, a):\n    pass\n" */
    @Test
    public void errorReadsTextFromFile() throws IOException {
        String source = "def f(a, a):\n    pass\n";
        arguments a = args(list(new arg("a", null, null, 1, 6, 1, 7),
                new arg("a", null, null, 1, 9, 1, 10)), null, null);
        Module m = module(new FunctionDef("f", a, list(new Pass(2, 4, 2, 8)), list(), null,
                null, list(), 1, 0, 2, 8));
        File file = File.createTempFile("symtable", ".py");
        try {
            Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
            Symtable._Py_SymtableStringObjectFlags(m, file.getPath(),
                    new Compile.PyCompilerFlags());
            fail("expected a SyntaxError");
        } catch (PythonSyntaxError e) {
            assertEquals("SyntaxError", e.type);
            assertEquals("duplicate parameter 'a' in function definition", e.msg);
            assertEquals(1, e.lineno);
            assertEquals(10, e.offset);
            assertEquals(1, e.end_lineno);
            assertEquals(11, e.end_offset);
            assertEquals("def f(a, a):\n", e.text);
        } finally {
            file.delete();
        }
    }

    /** C's Py_EnterRecursiveCall raises RecursionError; here the JVM's stack runs out. */
    @Test
    public void stackOverflowIsRecursionError() {
        expr e = new Constant(BigInteger.ONE, null, 1, 0, 1, 1);
        for (int i = 0; i < 1_000_000; i++) {
            e = new UnaryOp(unaryopType.Not, e, 1, 0, 1, 1);
        }
        try {
            build(new Expression(e));
            fail("expected a RecursionError");
        } catch (PythonSyntaxError x) {
            assertEquals("RecursionError", x.type);
            assertTrue(x.msg, x.msg.endsWith("during compilation"));
        }
    }

    @Test
    public void mangle() {
        assertEquals("__x", Symtable._Py_Mangle(null, "__x"));
        assertEquals("_C__x", Symtable._Py_Mangle("C", "__x"));
        assertEquals("_C__x", Symtable._Py_Mangle("__C", "__x"));
        assertEquals("__x", Symtable._Py_Mangle("___", "__x"));
        assertEquals("__x__", Symtable._Py_Mangle("C", "__x__"));
        assertEquals("__x.y", Symtable._Py_Mangle("C", "__x.y"));
        assertEquals("_x", Symtable._Py_Mangle("C", "_x"));
        assertEquals("_", Symtable._Py_Mangle("C", "_"));
        assertEquals("__", Symtable._Py_Mangle("C", "__"));
        assertEquals("___", Symtable._Py_Mangle("C", "___"));
        // A class name and a name outside the BMP (U+00E9, U+20000).
        assertEquals("_\u00e9__\ud840\udc00", Symtable._Py_Mangle("_\u00e9", "__\ud840\udc00"));

        assertTrue(Symtable._Py_IsPrivateName("__x"));
        assertFalse(Symtable._Py_IsPrivateName("__x__"));
        assertFalse(Symtable._Py_IsPrivateName("__"));
        assertFalse(Symtable._Py_IsPrivateName("_x"));
    }
}
