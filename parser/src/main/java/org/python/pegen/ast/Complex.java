package org.python.pegen.ast;

/** A complex Constant value (Python complex), e.g. from the literal 1j. */
public final class Complex {

    public final double real;
    public final double imag;

    public Complex(double real, double imag) {
        this.real = real;
        this.imag = imag;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Complex)) {
            return false;
        }
        Complex c = (Complex) o;
        return Double.compare(real, c.real) == 0 && Double.compare(imag, c.imag) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(real) * 31 + Double.hashCode(imag);
    }

    @Override
    public String toString() {
        return "complex(" + real + ", " + imag + ")";
    }
}
