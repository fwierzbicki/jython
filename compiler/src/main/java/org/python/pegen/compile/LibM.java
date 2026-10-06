package org.python.pegen.compile;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The C math library functions constant folding reaches (through
 * float_pow and _Py_c_pow), correctly rounded.
 *
 * <p>CPython calls the platform's libm, so its results can differ from one
 * platform to another in the last bit. glibc's are correctly rounded in
 * nearly every case (on a sample of random arguments, all of hypot's and
 * about 99.95% of pow's, exp's and log's), where Java's StrictMath (fdlibm)
 * differs from glibc in 3% to 10% of them. So these compute the correctly
 * rounded value: in BigDecimal, at a precision raised until the rounding
 * to double is certain (Ziv's method). The special cases (zeros,
 * infinities, NaNs) follow C99 Annex F.
 */
final class LibM {

    private LibM() {}

    /** The precisions tried, in decimal digits; past the last, the last result is taken. */
    private static final int FIRST_PRECISION = 40, LAST_PRECISION = 1280;

    /** A computation of a real value at a given precision (in decimal digits). */
    private interface Approx {
        /** The value, with a relative error below 10**-(prec - 3). */
        BigDecimal at(int prec);
    }

    /** The double nearest to the value f approximates. */
    private static double round(Approx f) {
        BigDecimal v = null;
        for (int prec = FIRST_PRECISION; prec <= LAST_PRECISION; prec *= 2) {
            v = f.at(prec);
            if (v.signum() == 0) {
                return 0.0;
            }
            BigDecimal eps = v.abs().round(new MathContext(1)).movePointLeft(prec - 4);
            double lo = v.subtract(eps).doubleValue(), hi = v.add(eps).doubleValue();
            if (Double.doubleToRawLongBits(lo) == Double.doubleToRawLongBits(hi)) {
                return lo;
            }
        }
        // An exact midpoint, or as good as one.
        return v.doubleValue();
    }

    private static MathContext mc(int prec) {
        return new MathContext(prec, RoundingMode.HALF_EVEN);
    }

    /** 2**k exactly. */
    private static BigDecimal pow2(int k) {
        return k >= 0 ? new BigDecimal(BigInteger.ONE.shiftLeft(k))
                : BigDecimal.ONE.divide(new BigDecimal(BigInteger.ONE.shiftLeft(-k)));
    }

    /* ---------------- constants ---------------- */

    /** atanh(1/n) = sum 1/((2k+1) n**(2k+1)), for an integer n > 1. */
    private static BigDecimal atanh_inv(int n, MathContext mc) {
        BigDecimal x = BigDecimal.ONE.divide(BigDecimal.valueOf(n), mc);
        BigDecimal x2 = x.multiply(x, mc);
        BigDecimal term = x, sum = x;
        BigDecimal tiny = BigDecimal.ONE.movePointLeft(mc.getPrecision() + 2);
        for (int k = 3; term.compareTo(tiny) > 0; k += 2) {
            term = term.multiply(x2, mc);
            sum = sum.add(term.divide(BigDecimal.valueOf(k), mc), mc);
        }
        return sum;
    }

    /** atan(1/n) = sum (-1)**k/((2k+1) n**(2k+1)), for an integer n > 1. */
    private static BigDecimal atan_inv(int n, MathContext mc) {
        BigDecimal x = BigDecimal.ONE.divide(BigDecimal.valueOf(n), mc);
        BigDecimal x2 = x.multiply(x, mc);
        BigDecimal term = x, sum = x;
        BigDecimal tiny = BigDecimal.ONE.movePointLeft(mc.getPrecision() + 2);
        for (int k = 3; term.compareTo(tiny) > 0; k += 2) {
            term = term.multiply(x2, mc);
            BigDecimal t = term.divide(BigDecimal.valueOf(k), mc);
            sum = ((k / 2) % 2 == 1) ? sum.subtract(t, mc) : sum.add(t, mc);
        }
        return sum;
    }

    private static BigDecimal ln2(int prec) {
        return atanh_inv(3, mc(prec + 5)).multiply(BigDecimal.valueOf(2));
    }

    /** pi, by Machin's formula. */
    private static BigDecimal pi(int prec) {
        MathContext mc = mc(prec + 5);
        return atan_inv(5, mc).multiply(BigDecimal.valueOf(16))
                .subtract(atan_inv(239, mc).multiply(BigDecimal.valueOf(4)), mc);
    }

    /* ---------------- BigDecimal functions ---------------- */

    /** ln(x) for x > 0, to prec digits. */
    private static BigDecimal ln(BigDecimal x, int prec) {
        MathContext mc = mc(prec + 10);
        // x = m * 2**e with m in [2/3, 4/3)
        int e = x.unscaledValue().bitLength() - (int) Math.ceil(x.scale() * 3.321928094887362);
        BigDecimal m = x.multiply(pow2(-e));
        while (m.compareTo(new BigDecimal("1.3333333333")) >= 0) {
            m = m.divide(BigDecimal.valueOf(2));
            e++;
        }
        while (m.compareTo(new BigDecimal("0.6666666666")) < 0) {
            m = m.multiply(BigDecimal.valueOf(2));
            e--;
        }
        // ln m = 2 atanh((m - 1) / (m + 1)), |s| < 1/7
        BigDecimal s = m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), mc);
        BigDecimal s2 = s.multiply(s, mc);
        BigDecimal term = s, sum = s;
        BigDecimal tiny = BigDecimal.ONE.movePointLeft(mc.getPrecision() + 2);
        for (int k = 3; term.abs().compareTo(tiny) > 0; k += 2) {
            term = term.multiply(s2, mc);
            sum = sum.add(term.divide(BigDecimal.valueOf(k), mc), mc);
        }
        int extra = Integer.toString(Math.abs(e)).length();
        return sum.multiply(BigDecimal.valueOf(2))
                .add(ln2(prec + extra + 5).multiply(BigDecimal.valueOf(e)), mc(prec + 5));
    }

    /** exp(z), to prec digits; null if it is far outside the double range. */
    private static BigDecimal exp(BigDecimal z, int prec) {
        if (z.compareTo(BigDecimal.valueOf(800)) > 0 || z.compareTo(BigDecimal.valueOf(-800)) < 0) {
            return null;
        }
        MathContext mc = mc(prec + 15);
        BigDecimal ln2 = ln2(prec + 20);
        // z = k ln2 + r, |r| <= ln2 / 2; exp(r) = exp(r / 2**10) ** (2**10)
        int k = z.divide(ln2, mc).setScale(0, RoundingMode.HALF_EVEN).intValueExact();
        BigDecimal r = z.subtract(ln2.multiply(BigDecimal.valueOf(k)), mc);
        r = r.divide(BigDecimal.valueOf(1024), mc);
        BigDecimal term = BigDecimal.ONE, sum = BigDecimal.ONE;
        BigDecimal tiny = BigDecimal.ONE.movePointLeft(mc.getPrecision() + 2);
        for (int n = 1; term.abs().compareTo(tiny) > 0; n++) {
            term = term.multiply(r, mc).divide(BigDecimal.valueOf(n), mc);
            sum = sum.add(term, mc);
        }
        for (int i = 0; i < 10; i++) {
            sum = sum.multiply(sum, mc);
        }
        return sum.multiply(pow2(k), mc(prec + 5));
    }

    /** sqrt(x) for x >= 0, to prec digits (Newton's method). */
    private static BigDecimal sqrt(BigDecimal x, int prec) {
        if (x.signum() == 0) {
            return x;
        }
        MathContext mc = mc(prec + 10);
        double d = x.doubleValue();
        BigDecimal g;
        if (d > 0.0 && !Double.isInfinite(d)) {
            g = new BigDecimal(Math.sqrt(d));
        }
        else {
            // out of double's range: 10**(half the digits before the point)
            g = BigDecimal.ONE.movePointRight((x.precision() - x.scale()) / 2);
        }
        BigDecimal two = BigDecimal.valueOf(2);
        for (int i = 0; i < 200; i++) {
            BigDecimal next = g.add(x.divide(g, mc), mc).divide(two, mc);
            if (next.compareTo(g) == 0) {
                break;
            }
            g = next;
        }
        return g;
    }

    /** atan(z) for 0 <= z <= 1, to prec digits. */
    private static BigDecimal atan(BigDecimal z, int prec) {
        MathContext mc = mc(prec + 10);
        // atan(z) = 2 atan(z / (1 + sqrt(1 + z**2))), twice: z <= tan(pi/16)
        for (int i = 0; i < 2; i++) {
            z = z.divide(BigDecimal.ONE.add(sqrt(BigDecimal.ONE.add(z.multiply(z, mc)), prec + 10)),
                    mc);
        }
        BigDecimal z2 = z.multiply(z, mc);
        BigDecimal term = z, sum = z;
        BigDecimal tiny = z.abs().movePointLeft(mc.getPrecision() + 2);
        for (int k = 3; term.abs().compareTo(tiny) > 0; k += 2) {
            term = term.multiply(z2, mc).negate();
            sum = sum.add(term.divide(BigDecimal.valueOf(k), mc), mc);
        }
        return sum.multiply(BigDecimal.valueOf(4));
    }

    /** sin(r) or cos(r) by Taylor series, |r| <= pi/4. */
    private static BigDecimal sincos_series(BigDecimal r, boolean cos, MathContext mc) {
        BigDecimal r2 = r.multiply(r, mc);
        BigDecimal term = cos ? BigDecimal.ONE : r;
        BigDecimal sum = term;
        BigDecimal tiny = sum.abs().movePointLeft(mc.getPrecision() + 2);
        for (int n = cos ? 2 : 3; term.abs().compareTo(tiny) > 0; n += 2) {
            term = term.multiply(r2, mc).divide(BigDecimal.valueOf((long) n * (n - 1)), mc)
                    .negate();
            sum = sum.add(term, mc);
        }
        return sum;
    }

    /** sin(t) (cos false) or cos(t) (cos true) of a finite t, to prec digits. */
    private static BigDecimal sincos(double t, boolean cos, int prec) {
        BigDecimal x = new BigDecimal(t);
        // Enough digits of pi that t - n pi/2 keeps prec digits: |t| < 10**309,
        // and |t - n pi/2| > 10**-20 for every double t.
        int digits = prec + 30 + Math.max(0, x.precision() - x.scale());
        MathContext mc = mc(digits);
        BigDecimal halfpi = pi(digits).divide(BigDecimal.valueOf(2), mc);
        BigInteger n = x.divide(halfpi, mc).setScale(0, RoundingMode.HALF_EVEN).toBigInteger();
        BigDecimal r = x.subtract(halfpi.multiply(new BigDecimal(n)), mc);
        int q = n.mod(BigInteger.valueOf(4)).intValue();
        MathContext smc = mc(prec + 10);
        // sin(r + q pi/2) and cos(r + q pi/2)
        boolean useCos = cos ^ (q % 2 == 1);
        BigDecimal v = sincos_series(r, useCos, smc);
        boolean negate = cos ? (q == 1 || q == 2) : (q == 2 || q == 3);
        return negate ? v.negate() : v;
    }

    /* ---------------- the C functions ---------------- */

    private static boolean isfinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    /** C: exp. */
    static double exp(double z) {
        if (Double.isNaN(z)) {
            return z;
        }
        if (Double.isInfinite(z)) {
            return z > 0 ? z : 0.0;
        }
        if (z == 0.0) {
            return 1.0;
        }
        if (z > 709.8) {
            return Double.POSITIVE_INFINITY;
        }
        if (z < -745.2) {
            // exp(z) is below half the smallest subnormal (2**-1075 = exp(-745.13...))
            return 0.0;
        }
        final BigDecimal x = new BigDecimal(z);
        return round(prec -> exp(x, prec));
    }

    /** C: log. */
    static double log(double x) {
        if (Double.isNaN(x) || x == Double.POSITIVE_INFINITY) {
            return x;
        }
        if (x == 0.0) {
            return Double.NEGATIVE_INFINITY;
        }
        if (x < 0.0) {
            return Double.NaN;
        }
        if (x == 1.0) {
            return 0.0;
        }
        final BigDecimal v = new BigDecimal(x);
        return round(prec -> ln(v, prec));
    }

    /** C: pow. */
    static double pow(double x, double y) {
        if (!(x > 0.0 && isfinite(x) && isfinite(y)) || y == 0.0 || x == 1.0) {
            if (x < 0.0 && isfinite(x) && isfinite(y) && y == Math.rint(y)) {
                double r = pow(-x, y);
                return Math.abs(y) % 2.0 == 1.0 ? -r : r;
            }
            // C99's special cases, which fdlibm follows
            return StrictMath.pow(x, y);
        }
        final BigDecimal bx = new BigDecimal(x), by = new BigDecimal(y);
        // Out of range: |y ln x| > 800
        double approx = y * Math.log(x);
        if (approx > 800) {
            return Double.POSITIVE_INFINITY;
        }
        if (approx < -800) {
            return 0.0;
        }
        return round(prec -> {
            int extra = 5 + Math.max(0, by.precision() - by.scale());
            BigDecimal z = by.multiply(ln(bx, prec + extra + 5));
            BigDecimal r = exp(z, prec);
            return r == null ? BigDecimal.ZERO : r;
        });
    }

    /** C: sin. */
    static double sin(double t) {
        if (!isfinite(t)) {
            return Double.NaN;
        }
        if (t == 0.0) {
            return t;
        }
        return round(prec -> sincos(t, false, prec));
    }

    /** C: cos. */
    static double cos(double t) {
        if (!isfinite(t)) {
            return Double.NaN;
        }
        if (t == 0.0) {
            return 1.0;
        }
        return round(prec -> sincos(t, true, prec));
    }

    /** C: hypot, exactly rounded through integer arithmetic. */
    static double hypot(double x, double y) {
        if (Double.isInfinite(x) || Double.isInfinite(y)) {
            return Double.POSITIVE_INFINITY;
        }
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.NaN;
        }
        final BigDecimal s = new BigDecimal(x).pow(2).add(new BigDecimal(y).pow(2));
        if (s.signum() == 0) {
            return 0.0;
        }
        return round(prec -> sqrt(s, prec));
    }

    /** C: atan2 (C99 Annex F.9.1.4). */
    static double atan2(double y, double x) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.NaN;
        }
        boolean xneg = Math.copySign(1.0, x) < 0;
        if (y == 0.0) {
            if (xneg) {
                return Math.copySign(Math.PI, y);
            }
            return y;  // +-0
        }
        if (x == 0.0) {
            return Math.copySign(Math.PI / 2, y);
        }
        if (Double.isInfinite(y)) {
            if (Double.isInfinite(x)) {
                return Math.copySign(xneg ? 2.356194490192345 : 0.7853981633974483, y);
            }
            return Math.copySign(Math.PI / 2, y);
        }
        if (Double.isInfinite(x)) {
            return xneg ? Math.copySign(Math.PI, y) : Math.copySign(0.0, y);
        }
        final BigDecimal ay = new BigDecimal(Math.abs(y)), ax = new BigDecimal(Math.abs(x));
        final boolean steep = ay.compareTo(ax) > 0;
        double r = round(prec -> {
            MathContext mc = mc(prec + 10);
            BigDecimal t = steep ? atan(ax.divide(ay, mc), prec)
                    : atan(ay.divide(ax, mc), prec);
            BigDecimal halfpi = pi(prec + 10).divide(BigDecimal.valueOf(2), mc);
            BigDecimal a = steep ? halfpi.subtract(t, mc) : t;
            return xneg ? pi(prec + 10).subtract(a, mc) : a;
        });
        return Math.copySign(r, y);
    }
}
