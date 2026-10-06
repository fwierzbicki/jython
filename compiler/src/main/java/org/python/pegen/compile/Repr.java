package org.python.pegen.compile;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Singleton;
import org.python.pegen.lexer.UnicodeTables;

/**
 * repr() of the constants the compiler puts in messages (C: PyUnicode_FromFormat's
 * %R), and their type names (C: Py_TYPE(v)->tp_name).
 */
public final class Repr {

    private Repr() {}

    /** repr(v) for a constant: str, int, float, complex, bytes, None, True, False, Ellipsis. */
    public static String repr(Object v) {
        if (v instanceof String) {
            return strRepr((String) v);
        } else if (v instanceof BigInteger) {
            return v.toString();
        } else if (v instanceof Double) {
            return floatRepr((Double) v);
        } else if (v instanceof Complex) {
            return complexRepr((Complex) v);
        } else if (v instanceof Bytes) {
            return bytesRepr((Bytes) v);
        } else if (v instanceof Singleton) {
            return v == Singleton.Ellipsis ? "Ellipsis" : v.toString();
        } else if (v instanceof PyTuple) {
            Object[] items = ((PyTuple) v).items;
            StringBuilder b = new StringBuilder("(");
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    b.append(", ");
                }
                b.append(repr(items[i]));
            }
            if (items.length == 1) {
                b.append(',');
            }
            return b.append(')').toString();
        }
        return String.valueOf(v);
    }

    /** The name of a constant's type (C: tp_name). */
    public static String typeName(Object v) {
        if (v instanceof String) {
            return "str";
        } else if (v instanceof BigInteger) {
            return "int";
        } else if (v instanceof Double) {
            return "float";
        } else if (v instanceof Complex) {
            return "complex";
        } else if (v instanceof Bytes) {
            return "bytes";
        } else if (v == Singleton.True || v == Singleton.False) {
            return "bool";
        } else if (v == Singleton.None) {
            return "NoneType";
        } else if (v == Singleton.Ellipsis) {
            return "ellipsis";
        } else if (v instanceof PyTuple) {
            return "tuple";
        } else if (v instanceof PyFrozenSet) {
            return "frozenset";
        } else if (v instanceof PySlice) {
            return "slice";
        } else if (v instanceof PyCodeObject) {
            return "code";
        }
        throw new IllegalArgumentException("not a constant: " + v);
    }

    /** repr() of a str: unicode_repr in Objects/unicodeobject.c. */
    public static String strRepr(String s) {
        char quote = '\'';
        if (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) {
            quote = '"';
        }
        StringBuilder b = new StringBuilder();
        b.append(quote);
        for (int i = 0; i < s.length();) {
            int ch = s.codePointAt(i);
            i += Character.charCount(ch);
            if (ch == quote || ch == '\\') {
                b.append('\\').appendCodePoint(ch);
            } else if (ch == '\t') {
                b.append("\\t");
            } else if (ch == '\n') {
                b.append("\\n");
            } else if (ch == '\r') {
                b.append("\\r");
            } else if (ch < ' ' || ch == 0x7f) {
                b.append(String.format("\\x%02x", ch));
            } else if (ch < 0x7f) {
                b.appendCodePoint(ch);
            } else if (UnicodeTables._PyUnicode_IsPrintable(ch)) {
                b.appendCodePoint(ch);
            } else if (ch <= 0xff) {
                b.append(String.format("\\x%02x", ch));
            } else if (ch <= 0xffff) {
                b.append(String.format("\\u%04x", ch));
            } else {
                b.append(String.format("\\U%08x", ch));
            }
        }
        return b.append(quote).toString();
    }

    /** repr() of bytes: bytes_repr. */
    public static String bytesRepr(Bytes v) {
        byte[] data = v.toArray();
        boolean single = false, dbl = false;
        for (byte x : data) {
            single |= x == '\'';
            dbl |= x == '"';
        }
        char quote = single && !dbl ? '"' : '\'';
        StringBuilder b = new StringBuilder("b").append(quote);
        for (byte x : data) {
            int ch = x & 0xff;
            if (ch == quote || ch == '\\') {
                b.append('\\').append((char) ch);
            } else if (ch == '\t') {
                b.append("\\t");
            } else if (ch == '\n') {
                b.append("\\n");
            } else if (ch == '\r') {
                b.append("\\r");
            } else if (ch < ' ' || ch >= 0x7f) {
                b.append(String.format("\\x%02x", ch));
            } else {
                b.append((char) ch);
            }
        }
        return b.append(quote).toString();
    }

    /**
     * repr() of a float: the shortest digits that round-trip (Java 19+'s
     * Double.toString), laid out as Python does: positional for exponents
     * -4 to 15, else d.ddde+XX.
     */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0.0) {
            return Math.copySign(1.0, d) < 0 ? "-0.0" : "0.0";
        }
        BigDecimal bd = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        String digits = bd.unscaledValue().abs().toString();
        int exp = digits.length() - 1 - bd.scale();
        String sign = d < 0 ? "-" : "";
        if (exp >= -4 && exp < 16) {
            String plain = bd.abs().toPlainString();
            if (plain.indexOf('.') < 0) {
                plain += ".0";
            }
            return sign + plain;
        }
        String mantissa = digits.length() == 1 ? digits
                : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exp < 0 ? "-" : "+")
                + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
    }

    /** repr() of a complex: complex_repr, with the parts as repr gives them less a ".0". */
    public static String complexRepr(Complex z) {
        String imag = trimPointZero(floatRepr(z.imag));
        if (z.real == 0.0 && Math.copySign(1.0, z.real) > 0) {
            return imag + "j";
        }
        String real = trimPointZero(floatRepr(z.real));
        if (!imag.startsWith("-")) {
            imag = "+" + imag;
        }
        return "(" + real + imag + "j)";
    }

    private static String trimPointZero(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
