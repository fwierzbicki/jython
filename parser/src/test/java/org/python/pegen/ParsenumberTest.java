package org.python.pegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.python.pegen.ast.Complex;

/**
 * NUMBER token values (Parser.parsenumber). Expected values are CPython
 * 3.15's, mostly from test_grammar's TokenTests.
 */
public class ParsenumberTest {

    private static Object parse(String s) {
        try {
            return Parser.parsenumber(s);
        } catch (Parser.IntDigitLimitError e) {
            throw new AssertionError(e.getMessage());
        }
    }

    private static void assertInt(String expected, String literal) {
        assertEquals(new BigInteger(expected), parse(literal), literal);
    }

    private static void assertFloat(double expected, String literal) {
        assertEquals(Double.valueOf(expected), parse(literal), literal);
    }

    @Test
    public void integers() {
        assertInt("0", "0");
        assertInt("0", "00");
        assertInt("0", "0_0");
        assertInt("1000", "1_000");
        assertInt("255", "0xff");
        assertInt("255", "0XFF");
        assertInt("255", "0x_ff");
        assertInt("255", "0o377");
        assertInt("255", "0O377");
        assertInt("9", "0b1001");
        assertInt("9", "0B1001");
        assertInt("123456789012345678901234567890", "123456789012345678901234567890");
        assertInt("4722366482869645213695", "0xFFFFFFFFFFFFFFFFFF");
    }

    @Test
    public void floats() {
        assertFloat(3.14, "3.14");
        assertFloat(0.5, ".5");
        assertFloat(1.0, "1.");
        assertFloat(0.0, "0e0");
        assertFloat(1e-5, "1e-5");
        assertFloat(1e100, "1e100");
        assertFloat(1e308, "1e308");
        assertFloat(Double.POSITIVE_INFINITY, "1e400");
        assertFloat(2.2250738585072014e-308, "2.2250738585072014e-308");
        assertFloat(5e-324, "5e-324");
        assertFloat(1_0.0_1e1_0, "1_0.0_1e1_0");
    }

    @Test
    public void imaginary() {
        assertEquals(new Complex(0.0, 1.0), parse("1j"));
        assertEquals(new Complex(0.0, 1.5), parse("1.5J"));
        assertEquals(new Complex(0.0, 1.0), parse("10.0e-1j"));
        assertEquals(new Complex(0.0, 100100000000.0), parse("1_0.0_1e1_0j"));
    }

    @Test
    public void digitLimit() throws Exception {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < Parser.MAX_STR_DIGITS; i++) {
            digits.append('1');
        }
        assertTrue(parse(digits.toString()) instanceof BigInteger);
        digits.append('1');
        try {
            Parser.parsenumber(digits.toString());
            fail("expected the digit limit error");
        } catch (Parser.IntDigitLimitError e) {
            assertEquals("Exceeds the limit (4300 digits) for integer string conversion: "
                    + "value has 4301 digits; use sys.set_int_max_str_digits() to increase "
                    + "the limit", e.getMessage());
        }
        // The limit applies only to decimal.
        assertTrue(parse("0x" + digits) instanceof BigInteger);
    }
}
