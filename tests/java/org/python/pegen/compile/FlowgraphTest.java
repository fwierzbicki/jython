package org.python.pegen.compile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;

import org.junit.Test;
import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Singleton;

/**
 * Constant folding's operations (Abstract, LibM) on cases compare_flowgraph.py
 * can't pin down by itself: values whose last bit glibc decides, identity, and
 * the stack-effect tables. Expected values are CPython 3.15's on Linux.
 */
public class FlowgraphTest {

    private static BigInteger i(long v) {
        return BigInteger.valueOf(v);
    }

    private static void assertBits(double expected, Object actual) {
        assertEquals(Double.toHexString(expected), Double.toHexString((Double) actual));
    }

    @Test
    public void trueDivisionIsCorrectlyRounded() {
        assertBits(1.0 / 3, Abstract.PyNumber_TrueDivide(i(1), i(3)));
        // 2**53 + 1 is a tie: rounds to even
        assertBits(9007199254740992.0, Abstract.PyNumber_TrueDivide(i(9007199254740993L), i(1)));
        // 0 / -5 is -0.0
        assertBits(-0.0, Abstract.PyNumber_TrueDivide(i(0), i(-5)));
        // the smallest subnormal, and half of it (a tie, to even: 0)
        BigInteger p1074 = BigInteger.ONE.shiftLeft(1074);
        assertBits(Double.MIN_VALUE, Abstract.PyNumber_TrueDivide(i(1), p1074));
        assertBits(0.0, Abstract.PyNumber_TrueDivide(i(1), p1074.shiftLeft(1)));
        assertNull(Abstract.PyNumber_TrueDivide(BigInteger.TEN.pow(400), i(1)));
        assertNull(Abstract.PyNumber_TrueDivide(i(1), i(0)));
    }

    @Test
    public void floorDivisionAndModulo() {
        assertEquals(i(-4), Abstract.PyNumber_FloorDivide(i(-7), i(2)));
        assertEquals(i(2), Abstract.PyNumber_Remainder(i(-7), i(3)));
        assertEquals(i(-2), Abstract.PyNumber_Remainder(i(7), i(-3)));
        assertBits(0.5, Abstract.PyNumber_Remainder(-7.5, 2.0));
        assertBits(-4.0, Abstract.PyNumber_FloorDivide(-7.5, 2.0));
        assertBits(0.0, Abstract.PyNumber_Remainder(-0.0, 5.0));
        assertBits(-0.0, Abstract.PyNumber_Remainder(0.0, -5.0));
        assertNull(Abstract.PyNumber_FloorDivide(1.0, 0.0));
        assertNull(Abstract.PyNumber_Remainder(new Complex(0, 1), i(2)));
    }

    @Test
    public void powers() {
        assertBits(0.001, Abstract.PyNumber_Power(i(10), i(-3)));
        assertBits(Math.sqrt(2.0), Abstract.PyNumber_Power(2.0, 0.5));
        // 1.1 ** 2.5: glibc's, which is the correctly rounded value
        assertBits(1.2690587062858836, Abstract.PyNumber_Power(1.1, 2.5));
        assertNull(Abstract.PyNumber_Power(10.0, 400.0));
        assertNull(Abstract.PyNumber_Power(0.0, -1.0));
        // a negative number to a fractional power is complex
        assertTrue(Abstract.PyNumber_Power(-8.0, 1.0 / 3) instanceof Complex);
        // cos(inf) sets EDOM: ZeroDivisionError in C
        assertNull(Abstract.PyNumber_Power(new Complex(2, 3), Double.POSITIVE_INFINITY));
        Complex z = (Complex) Abstract.PyNumber_Power(new Complex(0, 1), i(2));
        assertEquals(-1.0, z.real, 0.0);
        assertEquals(0.0, z.imag, 0.0);
    }

    @Test
    public void libm() {
        assertBits(5.0, LibM.hypot(3.0, 4.0));
        assertBits(Double.POSITIVE_INFINITY, LibM.hypot(Double.NaN, Double.NEGATIVE_INFINITY));
        assertBits(2.356194490192345, LibM.atan2(Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY));
        assertBits(-Math.PI, LibM.atan2(-0.0, -1.0));
        assertBits(-0.0, LibM.atan2(-0.0, 1.0));
        assertBits(1.0, LibM.exp(0.0));
        assertBits(Double.MIN_VALUE, LibM.exp(-744.5));
        assertBits(0.0, LibM.exp(-745.14));
        assertBits(0.0, LibM.log(1.0));
        assertTrue(Double.isNaN(LibM.sin(Double.POSITIVE_INFINITY)));
        // glibc: math.sin(1e22), whose argument reduction needs many digits of pi
        assertBits(-0.8522008497671888, LibM.sin(1e22));
        assertBits(0.5403023058681398, LibM.cos(1.0));
    }

    @Test
    public void sequences() {
        assertEquals("abab", Abstract.PyNumber_Multiply(i(2), "ab"));
        assertEquals("", Abstract.PyNumber_Multiply("ab", i(-1)));
        assertNull(Abstract.PyNumber_Multiply("ab", 2.0));
        assertEquals("😀", Abstract.PyObject_GetItem("😀x", i(0)));
        assertEquals("cba", Abstract.PyObject_GetItem("abc", new PySlice(Singleton.None,
                Singleton.None, i(-1))));
        assertEquals(i(255), Abstract.PyObject_GetItem(new Bytes(new byte[] {-1}), i(0)));
        assertNull(Abstract.PyObject_GetItem("abc", i(3)));
        assertNull(Abstract.PyObject_GetItem("abc", 1.0));
        PyFrozenSet s = Abstract.PyFrozenSet_New(new PyTuple(i(1), 1.0, Singleton.True, i(2)));
        assertEquals(Arrays.asList(i(1), i(2)), s.items);
    }

    @Test
    public void identity() {
        String s = "abc";
        assertSame(s, Abstract.PyNumber_Add(s, ""));
        assertSame(s, Abstract.PyObject_GetItem(s, new PySlice(Singleton.None, Singleton.None,
                Singleton.None)));
        BigInteger big = BigInteger.TEN.pow(30);
        assertSame(big, Abstract.PyNumber_Positive(big));
        // small ints, () and one Latin-1 character are shared in C; big ints aren't
        assertTrue(Abstract.Py_Is(i(1024), Abstract.PyNumber_Add(i(1000), i(24))));
        assertFalse(Abstract.Py_Is(i(1025), Abstract.PyNumber_Add(i(1000), i(25))));
        assertTrue(Abstract.Py_Is(new PyTuple(), new PyTuple()));
        assertTrue(Abstract.Py_Is("é", Abstract.PyObject_GetItem("hé", i(1))));
        assertFalse(Abstract.Py_Is("Ā", Abstract.PyObject_GetItem("hĀ", i(1))));
        assertSame(Singleton.True, Abstract.PyNumber_And(Singleton.True, Singleton.True));
    }

    @Test
    public void stackEffects() {
        // C: PyCompile_OpcodeStackEffect, as _opcode.stack_effect gives it
        assertEquals(-1, Flowgraph.PyCompile_OpcodeStackEffect(Opcode.BINARY_OP, 0));
        assertEquals(1 - 3, Flowgraph.PyCompile_OpcodeStackEffect(Opcode.BUILD_TUPLE, 3));
        assertEquals(1, Flowgraph.PyCompile_OpcodeStackEffect(Opcode.SETUP_FINALLY, 0));
        assertEquals(0, Flowgraph.PyCompile_OpcodeStackEffectWithJump(Opcode.SETUP_FINALLY, 0,
                0));
    }
}
