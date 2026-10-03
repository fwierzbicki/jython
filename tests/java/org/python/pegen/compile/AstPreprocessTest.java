package org.python.pegen.compile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
import org.python.pegen.Parser;
import org.python.pegen.PythonSyntaxError;
import org.python.pegen.ast.Constant;
import org.python.pegen.ast.Expression;
import org.python.pegen.ast.FunctionDef;
import org.python.pegen.ast.Module;
import org.python.pegen.ast.Pass;
import org.python.pegen.ast.Return;
import org.python.pegen.ast.Try;
import org.python.pegen.ast.UnaryOp;
import org.python.pegen.ast.arguments;
import org.python.pegen.ast.unaryopType;
import org.python.pegen.ast.base.expr;

/**
 * AstPreprocess, for what compare_ast.py can't see: a PEP 765 warning that
 * the warnings filters make an error, where warnings go, and running out of
 * stack. Expected values are CPython 3.15's.
 */
public class AstPreprocessTest {

    /** The source the tree below is laid out as. */
    private static final String SOURCE = "def f():\n"
            + "    try:\n"
            + "        pass\n"
            + "    finally:\n"
            + "        return  1\n";

    @SafeVarargs
    private static <T> List<T> list(T... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    /** SOURCE's tree. */
    private static Module returnInFinally() {
        Return ret = new Return(new Constant(BigInteger.ONE, null, 5, 16, 5, 17), 5, 8, 5, 17);
        Try t = new Try(list(new Pass(3, 8, 3, 12)), list(), list(), list(ret), 2, 4, 5, 17);
        arguments args = new arguments(list(), list(), null, list(), list(), null, list());
        FunctionDef f = new FunctionDef("f", args, list(t), list(), null, null, list(),
                1, 0, 5, 17);
        return new Module(list(f), Collections.emptyList());
    }

    @Test
    public void warningsGoToTheHandler() {
        List<Parser.ParserWarning> warnings = new ArrayList<>();
        Compile.new_compiler(returnInFinally(), "f.py", new Compile.PyCompilerFlags(), -1,
                "mod", warnings::add);
        assertEquals(1, warnings.size());
        Parser.ParserWarning w = warnings.get(0);
        assertEquals("SyntaxWarning", w.category);
        assertEquals("'return' in a 'finally' block", w.message);
        assertEquals("f.py", w.filename);
        assertEquals(5, w.lineno);
        assertEquals("mod", w.module);
    }

    @Test
    public void noWarningsForOnlyAst() {
        // _PyCompile_AstPreprocess doesn't enable them, so needs no handler.
        Compile._PyCompile_AstPreprocess(returnInFinally(), "<unknown>",
                new Compile.PyCompilerFlags(Compile.PyCF_OPTIMIZED_AST), 1, false);
    }

    @Test
    public void warningAsErrorWithoutFile() {
        try {
            Compile.new_compiler(returnInFinally(), "<unknown>", new Compile.PyCompilerFlags(),
                    -1, null, w -> false);
            fail("expected a SyntaxError");
        } catch (PythonSyntaxError e) {
            assertEquals("SyntaxError", e.type);
            assertEquals("'return' in a 'finally' block", e.msg);
            assertEquals(5, e.lineno);
            assertEquals(9, e.offset);
            assertEquals(5, e.end_lineno);
            assertEquals(18, e.end_offset);
            assertNull(e.text);
        }
    }

    @Test
    public void warningAsErrorReadsTextFromFile() throws IOException {
        File f = File.createTempFile("preprocess", ".py");
        try {
            Files.write(f.toPath(), SOURCE.getBytes(StandardCharsets.UTF_8));
            Compile.new_compiler(returnInFinally(), f.getPath(), new Compile.PyCompilerFlags(),
                    -1, null, w -> false);
            fail("expected a SyntaxError");
        } catch (PythonSyntaxError e) {
            assertEquals(9, e.offset);
            assertEquals("        return  1\n", e.text);
        } finally {
            f.delete();
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
            Compile._PyCompile_AstPreprocess(new Expression(e), "<unknown>",
                    new Compile.PyCompilerFlags(Compile.PyCF_ONLY_AST), -1, true);
            fail("expected a RecursionError");
        } catch (PythonSyntaxError x) {
            assertEquals("RecursionError", x.type);
            assertTrue(x.msg, x.msg.endsWith("during compilation"));
        }
    }

    @Test
    public void matchNumbers() {
        BigInteger big = BigInteger.TEN.pow(400);
        assertEquals(big.negate(), AstPreprocess.PyNumber_Negative(big));
        assertEquals(-0.0, (Double) AstPreprocess.PyNumber_Negative(0.0), 0.0);
        assertTrue(Double.doubleToRawLongBits(
                (Double) AstPreprocess.PyNumber_Negative(0.0)) < 0);
        // int + complex makes the int a float first: too large is OverflowError.
        assertNull(AstPreprocess.PyNumber_Add(big, new org.python.pegen.ast.Complex(0, 1)));
        // 2**53 + 1 rounds to even as a float.
        Object z = AstPreprocess.PyNumber_Add(BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE),
                new org.python.pegen.ast.Complex(0, 1));
        assertEquals(new org.python.pegen.ast.Complex(9007199254740992.0, 1), z);
        // real - complex negates the imaginary part, 0 included.
        assertEquals(new org.python.pegen.ast.Complex(0, -0.0),
                AstPreprocess.PyNumber_Subtract(BigInteger.ZERO,
                        new org.python.pegen.ast.Complex(0, 0)));
        // Nothing else is folded.
        assertNull(AstPreprocess.PyNumber_Add("a", "b"));
    }
}
