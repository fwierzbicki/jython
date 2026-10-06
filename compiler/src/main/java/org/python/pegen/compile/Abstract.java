package org.python.pegen.compile;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Singleton;

/**
 * The operations on constants that flowgraph's constant folding calls
 * (C: PyNumber_*, PyObject_GetItem and PyObject_IsTrue from
 * Objects/abstract.c), over the constant value classes: int (BigInteger,
 * and bool, which is an int), float (Double), complex, str (String),
 * bytes, tuple and frozenset. Each operation follows the type slot it
 * reaches in Objects/ (long_*, float_*, complex_*, unicode_*, bytes_*,
 * tuple_*), C names kept where there is one; only the paths folding can
 * reach are ported.
 *
 * <p>An operation that raises in C returns null here: folding then leaves
 * the expression alone, as C does when it clears the error. Where C
 * returns an operand itself (such as {@code +x}, or {@code s + ''}), so
 * does this: object identity matters to flowgraph's constants index (see
 * {@link #Py_Is}).
 */
final class Abstract {

    private Abstract() {}

    /**
     * A builtin object that isn't a constant but that
     * LOAD_COMMON_CONSTANT loads (an exception type or builtin function).
     * Folding never combines one, so only its truth is needed.
     */
    static final class Builtin {
        final String name;

        Builtin(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** C: the interpreter's common_consts, indexed by LOAD_COMMON_CONSTANT's oparg. */
    static final Object[] common_consts = {
        new Builtin("AssertionError"),
        new Builtin("NotImplementedError"),
        new Builtin("tuple"),
        new Builtin("all"),
        new Builtin("any"),
        new Builtin("list"),
        new Builtin("set"),
        Singleton.None,
        "",
        Singleton.True,
        Singleton.False,
        BigInteger.valueOf(-1),
    };

    /* _PY_NSMALLNEGINTS and _PY_NSMALLPOSINTS (pycore_runtime_structs.h) */
    private static final int NSMALLNEGINTS = 5;
    private static final int NSMALLPOSINTS = 1025;

    /**
     * Whether C has one object for every constant equal to o: the small
     * ints, the empty str, bytes and tuple, the one-character Latin-1 str
     * and the one-byte bytes.
     */
    private static boolean is_shared(Object o) {
        if (o instanceof BigInteger) {
            BigInteger v = (BigInteger) o;
            return v.bitLength() < 32 && v.intValue() >= -NSMALLNEGINTS
                    && v.intValue() < NSMALLPOSINTS;
        }
        if (o instanceof String) {
            String s = (String) o;
            return s.isEmpty() || (s.length() == 1 && s.charAt(0) < 256);
        }
        if (o instanceof Bytes) {
            return ((Bytes) o).length() <= 1;
        }
        if (o instanceof PyTuple) {
            return ((PyTuple) o).size() == 0;
        }
        return false;
    }

    /**
     * C's {@code a == b} on two constants (Py_Is): the same object, which
     * for the constants C shares (see {@link #is_shared}) means an equal
     * one.
     */
    static boolean Py_Is(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (!is_shared(a) || !is_shared(b) || a.getClass() != b.getClass()) {
            return false;
        }
        // Equal shared constants (two empty tuples are the empty tuple).
        return a instanceof PyTuple || a.equals(b);
    }

    /** A hash consistent with {@link #Py_Is}. */
    static int Py_Is_hash(Object o) {
        if (!is_shared(o)) {
            return System.identityHashCode(o);
        }
        return o instanceof PyTuple ? 0 : o.hashCode();
    }

    /** A new int object (C makes one for every result outside the small ints). */
    private static BigInteger newLong(BigInteger v) {
        return new BigInteger(v.toByteArray());
    }

    private static Double newFloat(double d) {
        return Double.valueOf(d);
    }

    /* ---------------- type checks and int access ---------------- */

    private static boolean PyBool_Check(Object o) {
        return o == Singleton.True || o == Singleton.False;
    }

    /** C: PyLong_Check (bool is a subclass of int). */
    static boolean PyLong_Check(Object o) {
        return o instanceof BigInteger || PyBool_Check(o);
    }

    private static boolean PyFloat_Check(Object o) {
        return o instanceof Double;
    }

    private static boolean PyComplex_Check(Object o) {
        return o instanceof Complex;
    }

    /** The value of an int (or bool). */
    private static BigInteger asBig(Object o) {
        if (o == Singleton.True) {
            return BigInteger.ONE;
        }
        if (o == Singleton.False) {
            return BigInteger.ZERO;
        }
        return (BigInteger) o;
    }

    static boolean _PyLong_IsZero(Object v) {
        return asBig(v).signum() == 0;
    }

    static boolean _PyLong_IsPositive(Object v) {
        return asBig(v).signum() > 0;
    }

    /** C: _PyLong_NumBits: the number of bits in abs(v). */
    static long _PyLong_NumBits(Object v) {
        return asBig(v).abs().bitLength();
    }

    /** C: PyLong_AsLong; null where C raises OverflowError. */
    static Long PyLong_AsLong(Object v) {
        BigInteger b = asBig(v);
        if (b.bitLength() > 63) {
            return null;
        }
        return b.longValue();
    }

    /** C: PyLong_AsSize_t; null where C raises (negative, or too big). */
    static Long PyLong_AsSize_t(Object v) {
        BigInteger b = asBig(v);
        if (b.signum() < 0 || b.bitLength() > 63) {
            // Values in [2**63, 2**64) are valid size_t, but every caller
            // here compares the result with a small limit.
            return b.signum() < 0 ? null : Long.MAX_VALUE;
        }
        return b.longValue();
    }

    /** C: PyUnicode_GET_LENGTH, in code points. */
    static int PyUnicode_GET_LENGTH(String s) {
        return s.codePointCount(0, s.length());
    }

    /** C: PyNumber_AsSsize_t(o, exc) for an index; null where C raises. */
    private static Long PyNumber_AsSsize_t(Object o) {
        if (!PyLong_Check(o)) {
            return null;  // TypeError
        }
        BigInteger b = asBig(o);
        if (b.bitLength() > 63) {
            return null;  // OverflowError or IndexError
        }
        return b.longValue();
    }

    /* ---------------- tuples and frozensets ---------------- */

    static PyTuple PyTuple_New(Object[] items) {
        return new PyTuple(items);
    }

    /**
     * C: PyFrozenSet_New(iterable) over a tuple: the items, without the
     * ones equal to an earlier item (set_add keeps the first).
     */
    static PyFrozenSet PyFrozenSet_New(PyTuple iterable) {
        List<Object> items = new ArrayList<>();
        for (Object item : iterable.items) {
            boolean found = false;
            for (Object o : items) {
                if (Py_Is(o, item) || py_eq(o, item)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                items.add(item);
            }
        }
        return new PyFrozenSet(items);
    }

    /** Python's == between two constants (as set lookup uses it). */
    private static boolean py_eq(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            return number_eq(a, b);
        }
        if (a instanceof String && b instanceof String) {
            return a.equals(b);
        }
        if (a instanceof Bytes && b instanceof Bytes) {
            return a.equals(b);
        }
        if (a instanceof PyTuple && b instanceof PyTuple) {
            Object[] x = ((PyTuple) a).items, y = ((PyTuple) b).items;
            if (x.length != y.length) {
                return false;
            }
            for (int i = 0; i < x.length; i++) {
                if (!(Py_Is(x[i], y[i]) || py_eq(x[i], y[i]))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof PyFrozenSet && b instanceof PyFrozenSet) {
            List<Object> x = ((PyFrozenSet) a).items, y = ((PyFrozenSet) b).items;
            if (x.size() != y.size()) {
                return false;
            }
            for (Object i : x) {
                boolean found = false;
                for (Object j : y) {
                    if (Py_Is(i, j) || py_eq(i, j)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof PySlice && b instanceof PySlice) {
            PySlice x = (PySlice) a, y = (PySlice) b;
            return py_eq(x.start, y.start) && py_eq(x.stop, y.stop) && py_eq(x.step, y.step);
        }
        if (a instanceof PyCodeObject && b instanceof PyCodeObject) {
            return a.equals(b);
        }
        return a == b;
    }

    private static boolean isNumber(Object o) {
        return PyLong_Check(o) || PyFloat_Check(o) || PyComplex_Check(o);
    }

    /** Exact numeric equality across int, float and complex. */
    private static boolean number_eq(Object a, Object b) {
        double ai = 0.0, bi = 0.0;
        Object ar = a, br = b;
        if (a instanceof Complex) {
            ai = ((Complex) a).imag;
            ar = ((Complex) a).real;
        }
        if (b instanceof Complex) {
            bi = ((Complex) b).imag;
            br = ((Complex) b).real;
        }
        if (ai != bi) {
            return false;
        }
        return real_eq(ar, br);
    }

    private static boolean real_eq(Object a, Object b) {
        if (a instanceof Double && b instanceof Double) {
            return (double) (Double) a == (double) (Double) b;
        }
        if (a instanceof Double || b instanceof Double) {
            double d = (Double) (a instanceof Double ? a : b);
            BigInteger i = asBig(a instanceof Double ? b : a);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return false;
            }
            return new BigDecimal(d).compareTo(new BigDecimal(i)) == 0;
        }
        return asBig(a).equals(asBig(b));
    }

    /* ---------------- int and float helpers ---------------- */

    /**
     * C: _Py_convert_int_to_double (PyLong_AsDouble), correctly rounded;
     * null where C raises OverflowError.
     */
    private static Double PyLong_AsDouble(Object v) {
        double d = asBig(v).doubleValue();
        if (Double.isInfinite(d)) {
            return null;
        }
        return d;
    }

    /** C: CONVERT_TO_DOUBLE: a float's or an int's value; null if it raises. */
    private static Double CONVERT_TO_DOUBLE(Object v) {
        if (v instanceof Double) {
            return (Double) v;
        }
        return PyLong_AsDouble(v);
    }

    /**
     * The double nearest to the rational a / b (b != 0), rounding half to
     * even, as C's long_true_divide computes it; null on overflow.
     */
    private static Double rational_to_double(BigInteger a, BigInteger b) {
        boolean negate = (a.signum() < 0) ^ (b.signum() < 0);
        BigInteger A = a.abs(), B = b.abs();
        if (A.signum() == 0) {
            return negate ? -0.0 : 0.0;
        }
        // e = floor(log2(A / B))
        int e = A.bitLength() - B.bitLength();
        if (e >= 0 ? A.compareTo(B.shiftLeft(e)) < 0 : A.shiftLeft(-e).compareTo(B) < 0) {
            e--;
        }
        if (e > 1023) {
            return null;
        }
        // The weight of the result's last bit (53 bits, fewer if subnormal).
        int lsb = Math.max(e - 52, -1074);
        BigInteger num = lsb < 0 ? A.shiftLeft(-lsb) : A;
        BigInteger den = lsb > 0 ? B.shiftLeft(lsb) : B;
        BigInteger[] qr = num.divideAndRemainder(den);
        BigInteger q = qr[0];
        int cmp = qr[1].shiftLeft(1).compareTo(den);
        if (cmp > 0 || (cmp == 0 && q.testBit(0))) {
            q = q.add(BigInteger.ONE);
        }
        double r = Math.scalb(q.doubleValue(), lsb);
        if (Double.isInfinite(r)) {
            return null;
        }
        return negate ? -r : r;
    }

    /** C: long_true_divide. */
    private static Object long_true_divide(Object v, Object w) {
        BigInteger b = asBig(w);
        if (b.signum() == 0) {
            return null;  // ZeroDivisionError
        }
        Double r = rational_to_double(asBig(v), b);
        return r == null ? null : newFloat(r);
    }

    /** C: l_divmod's floor quotient and remainder; null if w is zero. */
    private static BigInteger[] l_divmod(BigInteger v, BigInteger w) {
        if (w.signum() == 0) {
            return null;
        }
        BigInteger[] qr = v.divideAndRemainder(w);
        if (qr[1].signum() != 0 && (qr[1].signum() != w.signum())) {
            qr[1] = qr[1].add(w);
            qr[0] = qr[0].subtract(BigInteger.ONE);
        }
        return qr;
    }

    /** C: _float_div_mod. */
    private static double[] _float_div_mod(double vx, double wx) {
        double div, mod, floordiv;
        mod = vx % wx;  // fmod
        /* fmod is typically exact, so vx-mod is *mathematically* an
           exact multiple of wx.  But this is fp arithmetic, and fp
           vx - mod is an approximation; the result is that div may
           not be an exact integral value after the division, although
           it will always be very close to one.
        */
        div = (vx - mod) / wx;
        if (mod != 0.0) {
            /* ensure the remainder has the same sign as the denominator */
            if ((wx < 0) != (mod < 0)) {
                mod += wx;
                div -= 1.0;
            }
        }
        else {
            /* the remainder is zero, and in the presence of signed zeroes
               fmod returns different results across platforms; ensure
               it has the same sign as the denominator. */
            mod = Math.copySign(0.0, wx);
        }
        /* snap quotient to nearest integral value */
        if (div != 0.0) {
            floordiv = Math.floor(div);
            if (div - floordiv > 0.5) {
                floordiv += 1.0;
            }
        }
        else {
            /* div is zero - get the same sign as the true quotient */
            floordiv = Math.copySign(0.0, vx / wx); /* zero w/ sign of vx/wx */
        }
        return new double[] {floordiv, mod};
    }

    private static boolean DOUBLE_IS_ODD_INTEGER(double x) {
        return Math.abs(x) % 2.0 == 1.0;
    }

    /** C: float_pow (third argument None). */
    private static Object float_pow(Object v, Object w) {
        double iv, iw, ix;
        boolean negate_result = false;

        Double cv = CONVERT_TO_DOUBLE(v);
        Double cw = CONVERT_TO_DOUBLE(w);
        if (cv == null || cw == null) {
            return null;
        }
        iv = cv;
        iw = cw;

        /* Sort out special cases here instead of relying on pow() */
        if (iw == 0) {              /* v**0 is 1, even 0**0 */
            return newFloat(1.0);
        }
        if (Double.isNaN(iv)) {        /* nan**w = nan, unless w == 0 */
            return newFloat(iv);
        }
        if (Double.isNaN(iw)) {        /* v**nan = nan, unless v == 1; 1**nan = 1 */
            return newFloat(iv == 1.0 ? 1.0 : iw);
        }
        if (Double.isInfinite(iw)) {
            /* v**inf is: 0.0 if abs(v) < 1; 1.0 if abs(v) == 1; inf if
             *     abs(v) > 1 (including case where v infinite)
             *
             * v**-inf is: inf if abs(v) < 1; 1.0 if abs(v) == 1; 0.0 if
             *     abs(v) > 1 (including case where v infinite)
             */
            iv = Math.abs(iv);
            if (iv == 1.0)
                return newFloat(1.0);
            else if ((iw > 0.0) == (iv > 1.0))
                return newFloat(Math.abs(iw)); /* return inf */
            else
                return newFloat(0.0);
        }
        if (Double.isInfinite(iv)) {
            /* (+-inf)**w is: inf for w positive, 0 for w negative; in
             *     both cases, we need to add the appropriate sign if w is
             *     an odd integer.
             */
            boolean iw_is_odd = DOUBLE_IS_ODD_INTEGER(iw);
            if (iw > 0.0)
                return newFloat(iw_is_odd ? iv : Math.abs(iv));
            else
                return newFloat(iw_is_odd ? Math.copySign(0.0, iv) : 0.0);
        }
        if (iv == 0.0) {  /* 0**w is: 0 for w positive, 1 for w zero
                             (already dealt with above), and an error
                             if w is negative. */
            boolean iw_is_odd = DOUBLE_IS_ODD_INTEGER(iw);
            if (iw < 0.0) {
                return null;  // ZeroDivisionError: zero to a negative power
            }
            /* use correct sign if iw is odd */
            return newFloat(iw_is_odd ? iv : 0.0);
        }

        if (iv < 0.0) {
            /* Whether this is an error is a mess, and bumps into libm
             * bugs so we have to figure it out ourselves.
             */
            if (iw != Math.floor(iw)) {
                /* Negative numbers raised to fractional powers
                 * become complex.
                 */
                return complex_pow(v, w);
            }
            /* iw is an exact integer, albeit perhaps a very large
             * one.  Replace iv by its absolute value and remember
             * to negate the pow result if iw is odd.
             */
            iv = -iv;
            negate_result = DOUBLE_IS_ODD_INTEGER(iw);
        }

        if (iv == 1.0) { /* 1**w is 1, even 1**inf and 1**nan */
            return newFloat(negate_result ? -1.0 : 1.0);
        }

        /* Now iv and iw are finite, iw is nonzero, and iv is
         * positive and not equal to 1.0.  We finally allow
         * the platform pow to step in and do the rest.
         */
        ix = pow(iv, iw);
        if (Double.isInfinite(ix)) {
            return null;  // OverflowError (_Py_ADJUST_ERANGE1)
        }
        if (negate_result)
            ix = -ix;
        return newFloat(ix);
    }

    /**
     * C's pow(x, y) for finite x > 0, x != 1, finite y != 0, correctly
     * rounded (see {@link LibM}): for an integral y up to 64 in size,
     * exactly, through integer arithmetic; otherwise by LibM.pow.
     */
    private static double pow(double x, double y) {
        if (y == Math.rint(y) && Math.abs(y) <= 64) {
            int n = (int) Math.abs(y);
            // x = m * 2**e exactly, so x**n = m**n * 2**(e*n)
            long bits = Double.doubleToRawLongBits(x);
            int biased = (int) ((bits >> 52) & 0x7ff);
            long m = bits & 0xfffffffffffffL;
            int e;
            if (biased == 0) {
                e = -1074;
            }
            else {
                m |= 1L << 52;
                e = biased - 1075;
            }
            BigInteger num = BigInteger.valueOf(m).pow(n);
            BigInteger den = BigInteger.ONE;
            long shift = (long) e * n;
            if (shift >= 0) {
                num = num.shiftLeft((int) shift);
            }
            else {
                den = den.shiftLeft((int) -shift);
            }
            Double r = y > 0 ? rational_to_double(num, den) : rational_to_double(den, num);
            return r == null ? Double.POSITIVE_INFINITY : r;
        }
        return LibM.pow(x, y);
    }

    /* ---------------- complex ---------------- */

    /** C: TO_COMPLEX / real_to_complex; null if converting raises. */
    private static Complex to_complex(Object o) {
        if (o instanceof Complex) {
            return (Complex) o;
        }
        Double d = CONVERT_TO_DOUBLE(o);
        return d == null ? null : new Complex(d, 0.0);
    }

    static Complex _Py_c_prod(Complex z, Complex w) {
        double a = z.real, b = z.imag, c = w.real, d = w.imag;
        double ac = a*c, bd = b*d, ad = a*d, bc = b*c;
        double rr = ac - bd, ri = ad + bc;

        /* Recover infinities that computed as nan+nanj.  See e.g. the C11,
           Annex G.5.1, routine _Cmultd(). */
        if (Double.isNaN(rr) && Double.isNaN(ri)) {
            boolean recalc = false;

            if (Double.isInfinite(a) || Double.isInfinite(b)) {  /* z is infinite */
                /* "Box" the infinity and change nans in the other factor to 0 */
                a = Math.copySign(Double.isInfinite(a) ? 1.0 : 0.0, a);
                b = Math.copySign(Double.isInfinite(b) ? 1.0 : 0.0, b);
                if (Double.isNaN(c)) {
                    c = Math.copySign(0.0, c);
                }
                if (Double.isNaN(d)) {
                    d = Math.copySign(0.0, d);
                }
                recalc = true;
            }
            if (Double.isInfinite(c) || Double.isInfinite(d)) {  /* w is infinite */
                /* "Box" the infinity and change nans in the other factor to 0 */
                c = Math.copySign(Double.isInfinite(c) ? 1.0 : 0.0, c);
                d = Math.copySign(Double.isInfinite(d) ? 1.0 : 0.0, d);
                if (Double.isNaN(a)) {
                    a = Math.copySign(0.0, a);
                }
                if (Double.isNaN(b)) {
                    b = Math.copySign(0.0, b);
                }
                recalc = true;
            }
            if (!recalc && (Double.isInfinite(ac) || Double.isInfinite(bd)
                    || Double.isInfinite(ad) || Double.isInfinite(bc))) {
                /* Recover infinities from overflow by changing nans to 0 */
                if (Double.isNaN(a)) {
                    a = Math.copySign(0.0, a);
                }
                if (Double.isNaN(b)) {
                    b = Math.copySign(0.0, b);
                }
                if (Double.isNaN(c)) {
                    c = Math.copySign(0.0, c);
                }
                if (Double.isNaN(d)) {
                    d = Math.copySign(0.0, d);
                }
                recalc = true;
            }
            if (recalc) {
                rr = Double.POSITIVE_INFINITY*(a*c - b*d);
                ri = Double.POSITIVE_INFINITY*(a*d + b*c);
            }
        }

        return new Complex(rr, ri);
    }

    /** C: _Py_c_quot; null where C sets errno to EDOM. */
    static Complex _Py_c_quot(Complex a, Complex b) {
        double rr, ri;
        final double abs_breal = b.real < 0 ? -b.real : b.real;
        final double abs_bimag = b.imag < 0 ? -b.imag : b.imag;

        if (abs_breal >= abs_bimag) {
            /* divide tops and bottom by b.real */
            if (abs_breal == 0.0) {
                return null;
            }
            else {
                final double ratio = b.imag / b.real;
                final double denom = b.real + b.imag * ratio;
                rr = (a.real + a.imag * ratio) / denom;
                ri = (a.imag - a.real * ratio) / denom;
            }
        }
        else if (abs_bimag >= abs_breal) {
            /* divide tops and bottom by b.imag */
            final double ratio = b.real / b.imag;
            final double denom = b.real * ratio + b.imag;
            rr = (a.real * ratio + a.imag) / denom;
            ri = (a.imag * ratio - a.real) / denom;
        }
        else {
            /* At least one of b.real or b.imag is a NaN */
            rr = ri = Double.NaN;
        }

        /* Recover infinities and zeros that computed as nan+nanj.  See e.g.
           the C11, Annex G.5.2, routine _Cdivd(). */
        if (Double.isNaN(rr) && Double.isNaN(ri)) {
            if ((Double.isInfinite(a.real) || Double.isInfinite(a.imag))
                && isfinite(b.real) && isfinite(b.imag))
            {
                final double x = Math.copySign(Double.isInfinite(a.real) ? 1.0 : 0.0, a.real);
                final double y = Math.copySign(Double.isInfinite(a.imag) ? 1.0 : 0.0, a.imag);
                rr = Double.POSITIVE_INFINITY * (x*b.real + y*b.imag);
                ri = Double.POSITIVE_INFINITY * (y*b.real - x*b.imag);
            }
            else if ((Double.isInfinite(abs_breal) || Double.isInfinite(abs_bimag))
                     && isfinite(a.real) && isfinite(a.imag))
            {
                final double x = Math.copySign(Double.isInfinite(b.real) ? 1.0 : 0.0, b.real);
                final double y = Math.copySign(Double.isInfinite(b.imag) ? 1.0 : 0.0, b.imag);
                rr = 0.0 * (a.real*x + a.imag*y);
                ri = 0.0 * (a.imag*x - a.real*y);
            }
        }

        return new Complex(rr, ri);
    }

    /** C: _Py_cr_quot; null where C sets errno to EDOM. */
    private static Complex _Py_cr_quot(Complex a, double b) {
        if (b != 0.0) {
            return new Complex(a.real / b, a.imag / b);
        }
        return null;
    }

    /** C: _Py_rc_quot; null where C sets errno to EDOM. */
    private static Complex _Py_rc_quot(double a, Complex b) {
        double rr, ri;
        final double abs_breal = b.real < 0 ? -b.real : b.real;
        final double abs_bimag = b.imag < 0 ? -b.imag : b.imag;

        if (abs_breal >= abs_bimag) {
            if (abs_breal == 0.0) {
                return null;
            }
            else {
                final double ratio = b.imag / b.real;
                final double denom = b.real + b.imag * ratio;
                rr = a / denom;
                ri = (-a * ratio) / denom;
            }
        }
        else if (abs_bimag >= abs_breal) {
            final double ratio = b.real / b.imag;
            final double denom = b.real * ratio + b.imag;
            rr = (a * ratio) / denom;
            ri = (-a) / denom;
        }
        else {
            rr = ri = Double.NaN;
        }

        if (Double.isNaN(rr) && Double.isNaN(ri) && isfinite(a)
            && (Double.isInfinite(abs_breal) || Double.isInfinite(abs_bimag)))
        {
            final double x = Math.copySign(Double.isInfinite(b.real) ? 1.0 : 0.0, b.real);
            final double y = Math.copySign(Double.isInfinite(b.imag) ? 1.0 : 0.0, b.imag);
            rr = 0.0 * (a*x);
            ri = 0.0 * (-a*y);
        }

        return new Complex(rr, ri);
    }

    private static boolean isfinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static final int EDOM = 1, ERANGE = 2;

    /** C: _Py_c_pow, with C's errno in errno[0]. */
    private static Complex _Py_c_pow(Complex a, Complex b, int[] errno) {
        double rr, ri;
        double vabs, len, at, phase;
        if (b.real == 0. && b.imag == 0.) {
            rr = 1.;
            ri = 0.;
        }
        else if (a.real == 0. && a.imag == 0.) {
            if (b.imag != 0. || b.real < 0.)
                errno[0] = EDOM;
            rr = 0.;
            ri = 0.;
        }
        else {
            // The libm calls set errno as glibc's do: ERANGE when a finite
            // argument overflows (or underflows to 0), EDOM for the sine or
            // cosine of an infinity.
            vabs = LibM.hypot(a.real, a.imag);
            libm_range(vabs, isfinite(a.real) && isfinite(a.imag), errno);
            len = LibM.pow(vabs, b.real);
            libm_range(len, isfinite(vabs) && isfinite(b.real), errno);
            at = LibM.atan2(a.imag, a.real);
            phase = at*b.real;
            if (b.imag != 0.0) {
                double e = LibM.exp(-at*b.imag);
                libm_range(e, isfinite(-at*b.imag), errno);
                len *= e;
                phase += b.imag*LibM.log(vabs);
            }
            if (Double.isInfinite(phase)) {
                errno[0] = EDOM;
            }
            rr = len*LibM.cos(phase);
            ri = len*LibM.sin(phase);

            _Py_ADJUST_ERANGE2(rr, ri, errno);
        }
        return new Complex(rr, ri);
    }

    /** The errno a libm function sets: ERANGE on overflow or underflow to 0. */
    private static void libm_range(double result, boolean finiteArgs, int[] errno) {
        if (finiteArgs && (Double.isInfinite(result) || result == 0.0)) {
            errno[0] = ERANGE;
        }
    }

    /** C: _Py_ADJUST_ERANGE2, on the errno a computation left. */
    private static void _Py_ADJUST_ERANGE2(double x, double y, int[] errno) {
        if (Double.isInfinite(x) || Double.isInfinite(y)) {
            if (errno[0] == 0) {
                errno[0] = ERANGE;
            }
        }
        else if (errno[0] == ERANGE) {
            errno[0] = 0;
        }
    }

    private static final Complex c_1 = new Complex(1., 0.);

    private static Complex c_powu(Complex x, long n) {
        Complex r, p;
        long mask = 1;
        r = c_1;
        p = x;
        while (mask > 0 && n >= mask) {
            if ((n & mask) != 0)
                r = _Py_c_prod(r, p);
            mask <<= 1;
            p = _Py_c_prod(p, p);
        }
        return r;
    }

    /** C: c_powi; null where C sets errno to EDOM. */
    private static Complex c_powi(Complex x, long n) {
        if (n > 0)
            return c_powu(x, n);
        else
            return _Py_c_quot(c_1, c_powu(x, -n));
    }

    /** C: complex_pow (third argument None). */
    private static Object complex_pow(Object v, Object w) {
        Complex a = to_complex(v);
        Complex b = to_complex(w);
        if (a == null || b == null) {
            return null;
        }
        Complex p;
        int[] errno = {0};
        // Check whether the exponent has a small integer value, and if so use
        // a faster and more accurate algorithm.
        if (b.imag == 0.0 && b.real == Math.floor(b.real) && Math.abs(b.real) <= 100.0) {
            p = c_powi(a, (long) b.real);
            if (p == null) {
                errno[0] = EDOM;
            }
            else {
                _Py_ADJUST_ERANGE2(p.real, p.imag, errno);
            }
        }
        else {
            p = _Py_c_pow(a, b, errno);
        }

        if (errno[0] != 0) {
            return null;  // ZeroDivisionError or OverflowError
        }
        return p;
    }

    /* ---------------- sequences ---------------- */

    private static int[] codepoints(String s) {
        return s.codePoints().toArray();
    }

    private static String fromCodepoints(int[] cps, int start, int len) {
        return new String(cps, start, len);
    }

    /** C: sequence_repeat (the count an int or bool); null where C raises. */
    private static Object sequence_repeat(Object seq, Object n) {
        if (!PyLong_Check(n)) {
            return null;  // can't multiply sequence by non-int
        }
        Long count = PyNumber_AsSsize_t(n);
        if (count == null) {
            return null;  // OverflowError
        }
        long c = Math.max(count, 0);
        if (seq instanceof String) {
            String s = (String) seq;
            /* unicode_repeat */
            if (s.isEmpty() || c == 0) {
                return "";
            }
            if (c == 1) {
                return s;
            }
            long total = (long) s.length() * c;
            if (total > Integer.MAX_VALUE) {
                return null;
            }
            StringBuilder sb = new StringBuilder((int) total);
            for (long i = 0; i < c; i++) {
                sb.append(s);
            }
            return sb.toString();
        }
        if (seq instanceof Bytes) {
            Bytes b = (Bytes) seq;
            /* bytes_repeat */
            if (c == 1) {
                return b;
            }
            long total = (long) b.length() * c;
            if (total > Integer.MAX_VALUE) {
                return null;
            }
            byte[] src = b.toArray();
            byte[] out = new byte[(int) total];
            for (int i = 0; i < out.length; i += src.length) {
                System.arraycopy(src, 0, out, i, src.length);
            }
            return new Bytes(out);
        }
        PyTuple t = (PyTuple) seq;
        /* tuple_repeat */
        if (t.size() == 0 || c == 1) {
            return t;
        }
        long total = (long) t.size() * c;
        if (total > Integer.MAX_VALUE) {
            return null;
        }
        Object[] out = new Object[(int) total];
        for (int i = 0; i < out.length; i += t.size()) {
            System.arraycopy(t.items, 0, out, i, t.size());
        }
        return new PyTuple(out);
    }

    private static boolean isSequence(Object o) {
        return o instanceof String || o instanceof Bytes || o instanceof PyTuple;
    }

    /** C: sq_concat of str, bytes and tuple; null where C raises. */
    private static Object sequence_concat(Object v, Object w) {
        if (v instanceof String && w instanceof String) {
            /* PyUnicode_Concat */
            String a = (String) v, b = (String) w;
            if (a.isEmpty()) {
                return b;
            }
            if (b.isEmpty()) {
                return a;
            }
            return a + b;
        }
        if (v instanceof Bytes && w instanceof Bytes) {
            /* bytes_concat */
            Bytes a = (Bytes) v, b = (Bytes) w;
            if (a.length() == 0) {
                return b;
            }
            if (b.length() == 0) {
                return a;
            }
            byte[] x = a.toArray(), y = b.toArray();
            byte[] out = Arrays.copyOf(x, x.length + y.length);
            System.arraycopy(y, 0, out, x.length, y.length);
            return new Bytes(out);
        }
        if (v instanceof PyTuple && w instanceof PyTuple) {
            /* tuple_concat */
            PyTuple a = (PyTuple) v, b = (PyTuple) w;
            if (a.size() == 0) {
                return b;
            }
            if (b.size() == 0) {
                return a;
            }
            Object[] out = Arrays.copyOf(a.items, a.size() + b.size());
            System.arraycopy(b.items, 0, out, a.size(), b.size());
            return new PyTuple(out);
        }
        return null;  // TypeError
    }

    /* ---------------- PyNumber_* ---------------- */

    /** The numeric kind of a constant: 1 int (or bool), 2 float, 3 complex, 0 other. */
    private static int kind(Object o) {
        if (PyLong_Check(o)) {
            return 1;
        }
        if (o instanceof Double) {
            return 2;
        }
        if (o instanceof Complex) {
            return 3;
        }
        return 0;
    }

    private static final int ADD = 0, SUB = 1, MUL = 2, TRUEDIV = 3, FLOORDIV = 4, MOD = 5,
            POW = 6;

    /** The arithmetic binary operations on numbers; null where C raises. */
    private static Object number_binop(Object v, Object w, int op) {
        int k = Math.max(kind(v), kind(w));
        if (kind(v) == 0 || kind(w) == 0) {
            return null;
        }
        if (k == 1) {
            BigInteger a = asBig(v), b = asBig(w);
            switch (op) {
                case ADD:
                    return newLong(a.add(b));
                case SUB:
                    return newLong(a.subtract(b));
                case MUL:
                    return newLong(a.multiply(b));
                case TRUEDIV:
                    return long_true_divide(v, w);
                case FLOORDIV: {
                    BigInteger[] qr = l_divmod(a, b);
                    return qr == null ? null : newLong(qr[0]);
                }
                case MOD: {
                    BigInteger[] qr = l_divmod(a, b);
                    return qr == null ? null : newLong(qr[1]);
                }
                case POW:
                    if (b.signum() < 0) {
                        /* if exponent is negative and there's no modulus:
                           return a float. */
                        return float_pow(v, w);
                    }
                    if (b.bitLength() > 31) {
                        // C computes it (or runs out of memory); folding's
                        // limits keep such a power from getting here.
                        return null;
                    }
                    return newLong(a.pow(b.intValue()));
            }
        }
        if (k == 2) {
            if (op == POW) {
                return float_pow(v, w);
            }
            Double cv = CONVERT_TO_DOUBLE(v), cw = CONVERT_TO_DOUBLE(w);
            if (cv == null || cw == null) {
                return null;
            }
            double a = cv, b = cw;
            switch (op) {
                case ADD:
                    return newFloat(a + b);
                case SUB:
                    return newFloat(a - b);
                case MUL:
                    return newFloat(a * b);
                case TRUEDIV:
                    if (b == 0.0) {
                        return null;  // ZeroDivisionError
                    }
                    return newFloat(a / b);
                case FLOORDIV:
                    if (b == 0.0) {
                        return null;
                    }
                    return newFloat(_float_div_mod(a, b)[0]);
                case MOD: {
                    /* float_rem */
                    if (b == 0.0) {
                        return null;
                    }
                    double mod = a % b;
                    if (mod != 0.0) {
                        /* ensure the remainder has the same sign as the denominator */
                        if ((b < 0) != (mod < 0)) {
                            mod += b;
                        }
                    }
                    else {
                        /* the remainder is zero, and in the presence of signed zeroes
                           fmod returns different results across platforms; ensure
                           it has the same sign as the denominator. */
                        mod = Math.copySign(0.0, b);
                    }
                    return newFloat(mod);
                }
            }
        }
        // complex
        if (op == POW) {
            return complex_pow(v, w);
        }
        if (op == FLOORDIV || op == MOD) {
            return null;  // TypeError
        }
        /* COMPLEX_BINOP */
        if (w instanceof Complex) {
            Complex b = (Complex) w;
            if (v instanceof Complex) {
                Complex a = (Complex) v;
                switch (op) {
                    case ADD:
                        return new Complex(a.real + b.real, a.imag + b.imag);
                    case SUB:
                        return new Complex(a.real - b.real, a.imag - b.imag);
                    case MUL:
                        return _Py_c_prod(a, b);
                    default:
                        return _Py_c_quot(a, b);
                }
            }
            Double ar = CONVERT_TO_DOUBLE(v);
            if (ar == null) {
                return null;
            }
            double a = ar;
            switch (op) {
                case ADD:
                    return new Complex(b.real + a, b.imag);
                case SUB:
                    return new Complex(a - b.real, -b.imag);
                case MUL:
                    return new Complex(b.real * a, b.imag * a);
                default:
                    return _Py_rc_quot(a, b);
            }
        }
        Complex a = (Complex) v;
        Double br = CONVERT_TO_DOUBLE(w);
        if (br == null) {
            return null;
        }
        double b = br;
        switch (op) {
            case ADD:
                return new Complex(a.real + b, a.imag);
            case SUB:
                return new Complex(a.real - b, a.imag);
            case MUL:
                return new Complex(a.real * b, a.imag * b);
            default:
                return _Py_cr_quot(a, b);
        }
    }

    static Object PyNumber_Add(Object v, Object w) {
        if (kind(v) != 0 && kind(w) != 0) {
            return number_binop(v, w, ADD);
        }
        if (isSequence(v)) {
            return sequence_concat(v, w);
        }
        return null;
    }

    static Object PyNumber_Subtract(Object v, Object w) {
        return number_binop(v, w, SUB);
    }

    static Object PyNumber_Multiply(Object v, Object w) {
        if (kind(v) != 0 && kind(w) != 0) {
            return number_binop(v, w, MUL);
        }
        if (isSequence(v)) {
            return sequence_repeat(v, w);
        }
        if (isSequence(w)) {
            return sequence_repeat(w, v);
        }
        return null;
    }

    static Object PyNumber_TrueDivide(Object v, Object w) {
        return number_binop(v, w, TRUEDIV);
    }

    static Object PyNumber_FloorDivide(Object v, Object w) {
        return number_binop(v, w, FLOORDIV);
    }

    static Object PyNumber_Remainder(Object v, Object w) {
        return number_binop(v, w, MOD);
    }

    static Object PyNumber_Power(Object v, Object w) {
        return number_binop(v, w, POW);
    }

    static Object PyNumber_Lshift(Object v, Object w) {
        if (!PyLong_Check(v) || !PyLong_Check(w)) {
            return null;
        }
        BigInteger a = asBig(v), b = asBig(w);
        if (b.signum() < 0) {
            return null;  // ValueError: negative shift count
        }
        if (a.signum() == 0) {
            return BigInteger.ZERO;
        }
        if (b.bitLength() > 31) {
            return null;  // MemoryError or OverflowError
        }
        return newLong(a.shiftLeft(b.intValue()));
    }

    static Object PyNumber_Rshift(Object v, Object w) {
        if (!PyLong_Check(v) || !PyLong_Check(w)) {
            return null;
        }
        BigInteger a = asBig(v), b = asBig(w);
        if (b.signum() < 0) {
            return null;  // ValueError: negative shift count
        }
        if (b.bitLength() > 31) {
            return a.signum() < 0 ? BigInteger.valueOf(-1) : BigInteger.ZERO;
        }
        return newLong(a.shiftRight(b.intValue()));
    }

    /** &, | and ^: on two bools (bool_and, ...) a bool, else on ints an int. */
    private static Object bitwise(Object v, Object w, char op) {
        if (!PyLong_Check(v) || !PyLong_Check(w)) {
            return null;
        }
        if (PyBool_Check(v) && PyBool_Check(w)) {
            boolean a = v == Singleton.True, b = w == Singleton.True;
            boolean r = op == '&' ? a & b : op == '|' ? a | b : a ^ b;
            return r ? Singleton.True : Singleton.False;
        }
        BigInteger a = asBig(v), b = asBig(w);
        return newLong(op == '&' ? a.and(b) : op == '|' ? a.or(b) : a.xor(b));
    }

    static Object PyNumber_Or(Object v, Object w) {
        return bitwise(v, w, '|');
    }

    static Object PyNumber_Xor(Object v, Object w) {
        return bitwise(v, w, '^');
    }

    static Object PyNumber_And(Object v, Object w) {
        return bitwise(v, w, '&');
    }

    static Object PyNumber_Negative(Object o) {
        if (PyLong_Check(o)) {
            return newLong(asBig(o).negate());
        }
        if (o instanceof Double) {
            return newFloat(-(Double) o);
        }
        if (o instanceof Complex) {
            Complex z = (Complex) o;
            return new Complex(-z.real, -z.imag);
        }
        return null;
    }

    static Object PyNumber_Positive(Object o) {
        if (o instanceof BigInteger || o instanceof Double || o instanceof Complex) {
            /* long_long, float_float, complex_pos: an exact int, float or
               complex is returned itself */
            return o;
        }
        if (PyBool_Check(o)) {
            return asBig(o);
        }
        return null;
    }

    static Object PyNumber_Invert(Object o) {
        if (PyLong_Check(o)) {
            return newLong(asBig(o).not());
        }
        return null;
    }

    /** C: PyObject_IsTrue (never fails on a constant). */
    static int PyObject_IsTrue(Object o) {
        if (o == Singleton.True) {
            return 1;
        }
        if (o == Singleton.False || o == Singleton.None) {
            return 0;
        }
        if (o instanceof BigInteger) {
            return ((BigInteger) o).signum() != 0 ? 1 : 0;
        }
        if (o instanceof Double) {
            return (Double) o != 0.0 ? 1 : 0;
        }
        if (o instanceof Complex) {
            Complex z = (Complex) o;
            return z.real != 0.0 || z.imag != 0.0 ? 1 : 0;
        }
        if (o instanceof String) {
            return ((String) o).isEmpty() ? 0 : 1;
        }
        if (o instanceof Bytes) {
            return ((Bytes) o).length() == 0 ? 0 : 1;
        }
        if (o instanceof PyTuple) {
            return ((PyTuple) o).size() == 0 ? 0 : 1;
        }
        if (o instanceof PyFrozenSet) {
            return ((PyFrozenSet) o).size() == 0 ? 0 : 1;
        }
        return 1;
    }

    /* ---------------- subscripts ---------------- */

    private static final long PY_SSIZE_T_MAX = Long.MAX_VALUE;
    private static final long PY_SSIZE_T_MIN = Long.MIN_VALUE;

    /** C: _PyEval_SliceIndex: an index clipped to Py_ssize_t; null if not an index. */
    private static Long _PyEval_SliceIndex(Object v, long dflt) {
        if (v == Singleton.None) {
            return dflt;
        }
        if (!PyLong_Check(v)) {
            return null;  // TypeError
        }
        BigInteger b = asBig(v);
        if (b.bitLength() > 63) {
            return b.signum() < 0 ? PY_SSIZE_T_MIN : PY_SSIZE_T_MAX;
        }
        return b.longValue();
    }

    /**
     * C: PySlice_Unpack and PySlice_AdjustIndices: {start, step,
     * slicelength} for a sequence of the given length; null where C raises.
     */
    private static long[] slice_indices(PySlice slice, long length) {
        long start, stop, step;
        if (slice.step == Singleton.None) {
            step = 1;
        }
        else {
            Long s = _PyEval_SliceIndex(slice.step, 1);
            if (s == null) {
                return null;
            }
            step = s;
            if (step == 0) {
                return null;  // ValueError: slice step cannot be zero
            }
            /* Here step might be -PY_SSIZE_T_MAX-1; in this case we replace it
             * with -PY_SSIZE_T_MAX.  This doesn't affect the semantics, and it
             * guards against later undefined behaviour resulting from code that
             * does "step = -step" as part of a slice reversal.
             */
            if (step < -PY_SSIZE_T_MAX)
                step = -PY_SSIZE_T_MAX;
        }
        Long s = _PyEval_SliceIndex(slice.start, step < 0 ? PY_SSIZE_T_MAX : 0);
        Long e = _PyEval_SliceIndex(slice.stop, step < 0 ? PY_SSIZE_T_MIN : PY_SSIZE_T_MAX);
        if (s == null || e == null) {
            return null;
        }
        start = s;
        stop = e;

        /* PySlice_AdjustIndices */
        if (start < 0) {
            start += length;
            if (start < 0) {
                start = (step < 0) ? -1 : 0;
            }
        }
        else if (start >= length) {
            start = (step < 0) ? length - 1 : length;
        }

        if (stop < 0) {
            stop += length;
            if (stop < 0) {
                stop = (step < 0) ? -1 : 0;
            }
        }
        else if (stop >= length) {
            stop = (step < 0) ? length - 1 : length;
        }

        long slicelength;
        if (step < 0) {
            if (stop < start) {
                slicelength = (start - stop - 1) / (-step) + 1;
            }
            else {
                slicelength = 0;
            }
        }
        else {
            if (start < stop) {
                slicelength = (stop - start - 1) / step + 1;
            }
            else {
                slicelength = 0;
            }
        }
        return new long[] {start, step, slicelength};
    }

    /** An index into a sequence of the given length, adjusted; -1 if out of range or not an index. */
    private static int item_index(Object key, int length) {
        Long i = PyNumber_AsSsize_t(key);
        if (i == null) {
            return -1;
        }
        long k = i;
        if (k < 0) {
            k += length;
        }
        if (k < 0 || k >= length) {
            return -1;  // IndexError
        }
        return (int) k;
    }

    /** C: PyObject_GetItem on a str, bytes or tuple constant; null where C raises. */
    static Object PyObject_GetItem(Object o, Object key) {
        if (o instanceof String) {
            int[] cps = codepoints((String) o);
            if (key instanceof PySlice) {
                long[] sl = slice_indices((PySlice) key, cps.length);
                if (sl == null) {
                    return null;
                }
                int start = (int) sl[0], step = (int) Math.max(Math.min(sl[1],
                        Integer.MAX_VALUE), Integer.MIN_VALUE);
                int slicelength = (int) sl[2];
                /* unicode_subscript */
                if (slicelength <= 0) {
                    return "";
                }
                else if (start == 0 && step == 1 && slicelength == cps.length) {
                    return o;
                }
                else if (step == 1) {
                    return fromCodepoints(cps, start, slicelength);
                }
                int[] out = new int[slicelength];
                for (int cur = start, i = 0; i < slicelength; cur += step, i++) {
                    out[i] = cps[cur];
                }
                return fromCodepoints(out, 0, slicelength);
            }
            if (!PyLong_Check(key)) {
                return null;
            }
            int i = item_index(key, cps.length);
            return i < 0 ? null : fromCodepoints(cps, i, 1);
        }
        if (o instanceof Bytes) {
            byte[] data = ((Bytes) o).toArray();
            if (key instanceof PySlice) {
                long[] sl = slice_indices((PySlice) key, data.length);
                if (sl == null) {
                    return null;
                }
                int start = (int) sl[0], slicelength = (int) sl[2];
                long step = sl[1];
                /* bytes_subscript */
                if (slicelength <= 0) {
                    return new Bytes(new byte[0]);
                }
                else if (start == 0 && step == 1 && slicelength == data.length) {
                    return o;
                }
                byte[] out = new byte[slicelength];
                long cur = start;
                for (int i = 0; i < slicelength; cur += step, i++) {
                    out[i] = data[(int) cur];
                }
                return new Bytes(out);
            }
            if (!PyLong_Check(key)) {
                return null;
            }
            int i = item_index(key, data.length);
            return i < 0 ? null : BigInteger.valueOf(data[i] & 0xff);
        }
        if (o instanceof PyTuple) {
            Object[] items = ((PyTuple) o).items;
            if (key instanceof PySlice) {
                long[] sl = slice_indices((PySlice) key, items.length);
                if (sl == null) {
                    return null;
                }
                int start = (int) sl[0], slicelength = (int) sl[2];
                long step = sl[1];
                /* tuple_subscript */
                if (slicelength <= 0) {
                    return new PyTuple();
                }
                else if (start == 0 && step == 1 && slicelength == items.length) {
                    return o;
                }
                Object[] out = new Object[slicelength];
                long cur = start;
                for (int i = 0; i < slicelength; cur += step, i++) {
                    out[i] = items[(int) cur];
                }
                return new PyTuple(out);
            }
            if (!PyLong_Check(key)) {
                return null;
            }
            int i = item_index(key, items.length);
            return i < 0 ? null : items[i];
        }
        return null;
    }
}
