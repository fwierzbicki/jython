package org.python.pegen.compile;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Singleton;

/**
 * The code object (C: PyCodeObject), and _PyCode_ConstantKey from
 * Objects/codeobject.c.
 *
 * <p>There is no assembler yet (Phase F), so _PyAssemble_MakeCodeObject
 * makes a placeholder: the fields codegen itself reads from a nested
 * unit's code object (its names, first line and free variables, for the
 * qualname and codegen_make_closure), and what flowgraph hands the
 * assembler (the optimized instruction sequence, the constants, the stack
 * depth and the number of locals plus cells and free variables). The rest
 * of PyCodeObject's fields come with Phase F.
 */
public final class PyCodeObject {

    public final String co_name;
    public final String co_qualname;
    public final int co_firstlineno;

    /**
     * The free variables, in order: C's co_localsplusnames from
     * PyUnstable_Code_GetFirstFree(co) to co_nlocalsplus, which assemble
     * (compute_localsplus_info) puts last, ordered by their u_freevars index.
     */
    public final List<String> co_freevars;

    /** The constants, in index order (after flowgraph). */
    public final List<Object> co_consts;

    /** Flowgraph's optimized instructions, which assemble will encode. */
    public final InstructionSequence instrs;

    public final int co_stacksize;

    /** The number of locals, cells and free variables (C: co_nlocalsplus). */
    public final int co_nlocalsplus;

    public final int co_flags;

    /**
     * What code_richcompare compares, but for the placeholder: name,
     * argument counts, flags, first line, stack depth, then the optimized
     * instructions with their exception handlers (standing for the
     * bytecode, line table and exception table), the constants' keys,
     * names and local variable names.
     */
    private final List<Object> identity;

    public PyCodeObject(String co_name, String co_qualname, int co_firstlineno,
            List<String> co_freevars, List<Object> identity, List<Object> co_consts,
            InstructionSequence instrs, int co_stacksize, int co_nlocalsplus, int co_flags) {
        this.co_name = co_name;
        this.co_qualname = co_qualname;
        this.co_firstlineno = co_firstlineno;
        this.co_freevars = co_freevars;
        this.identity = identity;
        this.co_consts = co_consts;
        this.instrs = instrs;
        this.co_stacksize = co_stacksize;
        this.co_nlocalsplus = co_nlocalsplus;
        this.co_flags = co_flags;
    }

    /**
     * C: code_richcompare, which the const cache uses (a code object is its
     * own key): equal code objects are one constant.
     */
    @Override
    public boolean equals(Object o) {
        return o == this || o instanceof PyCodeObject
                && identity.equals(((PyCodeObject) o).identity);
    }

    @Override
    public int hashCode() {
        return co_name.hashCode() * 31 + co_firstlineno;
    }

    /** C: co_nfreevars */
    public int co_nfreevars() {
        return co_freevars.size();
    }

    /**
     * A constant key (C: the tuple _PyCode_ConstantKey makes): the
     * constant's type and value in parts, compared item by item, and the
     * constant itself in op, which takes no part in the comparison (C
     * compares it too, but op is equal whenever the parts are).
     *
     * <p>Parts compare with Java's equals, so a float compares by its bits:
     * 0.0 and -0.0 differ (as in C, where the key tags -0.0). A float or
     * complex with a NaN in it is keyed by its identity instead: C compares
     * the values (with ==, under which a NaN is equal to nothing) unless
     * they're the same object.
     */
    public static final class ConstantKey {
        private final Object[] parts;
        /** C: the key's item 1, the constant (replaced for a frozenset). */
        public Object op;

        ConstantKey(Object op, Object... parts) {
            this.op = op;
            this.parts = parts;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ConstantKey && Arrays.equals(parts, ((ConstantKey) o).parts);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(parts);
        }
    }

    /** The type tags in keys (C: Py_TYPE(op)). */
    private enum Type {
        BOOL, BYTES, FLOAT, COMPLEX, TUPLE, FROZENSET, SLICE, ID
    }

    private static boolean negzero(double d) {
        return d == 0.0 && Math.copySign(1.0, d) < 0.0;
    }

    /**
     * C: _PyCode_ConstantKey. None, Ellipsis, int, str and code objects are
     * their own key (a code object by identity); other constants get a
     * {@link ConstantKey}.
     */
    public static Object _PyCode_ConstantKey(Object op) {
        /* Py_None and Py_Ellipsis are singletons. */
        if (op == Singleton.None || op == Singleton.Ellipsis
                || op instanceof BigInteger
                || op instanceof String
                /* code_richcompare() uses _PyCode_ConstantKey() internally */
                || op instanceof PyCodeObject) {
            /* Objects of these types are always different from object of other
             * type and from tuples. */
            return op;
        }
        else if (op == Singleton.True || op == Singleton.False) {
            /* Make booleans different from integers 0 and 1. */
            return new ConstantKey(op, Type.BOOL, op);
        }
        else if (op instanceof Bytes) {
            /* Avoid BytesWarning from comparing bytes with strings. */
            return new ConstantKey(op, Type.BYTES, op);
        }
        else if (op instanceof Double && Double.isNaN((Double) op)
                || op instanceof Complex
                        && (Double.isNaN(((Complex) op).real) || Double.isNaN(((Complex) op).imag))) {
            // Equal only to itself (see ConstantKey).
            Type type = op instanceof Double ? Type.FLOAT : Type.COMPLEX;
            return new ConstantKey(op, type, new Identity(op));
        }
        else if (op instanceof Double) {
            double d = (Double) op;
            /* all we need is to make the tuple different in either the 0.0
             * or -0.0 case from all others, just to avoid the "coercion".
             */
            if (negzero(d))
                return new ConstantKey(op, Type.FLOAT, op, Singleton.None);
            else
                return new ConstantKey(op, Type.FLOAT, op);
        }
        else if (op instanceof Complex) {
            Complex z = (Complex) op;
            /* For the complex case we must make complex(x, 0.)
               different from complex(x, -0.) and complex(0., y)
               different from complex(-0., y), for any x and y.
               All four complex zeros must be distinguished.*/
            boolean real_negzero = negzero(z.real);
            boolean imag_negzero = negzero(z.imag);
            Object[] value = {z.real, z.imag};
            /* use True, False and None singleton as tags for the real and imag
             * sign, to make tuples different */
            if (real_negzero && imag_negzero) {
                return new ConstantKey(op, Type.COMPLEX, Arrays.asList(value), Singleton.True);
            }
            else if (imag_negzero) {
                return new ConstantKey(op, Type.COMPLEX, Arrays.asList(value), Singleton.False);
            }
            else if (real_negzero) {
                return new ConstantKey(op, Type.COMPLEX, Arrays.asList(value), Singleton.None);
            }
            else {
                return new ConstantKey(op, Type.COMPLEX, Arrays.asList(value));
            }
        }
        else if (op instanceof PyTuple) {
            Object[] items = ((PyTuple) op).items;
            Object[] tuple = new Object[items.length];
            for (int i = 0; i < items.length; i++) {
                tuple[i] = _PyCode_ConstantKey(items[i]);
            }
            return new ConstantKey(op, Type.TUPLE, Arrays.asList(tuple));
        }
        else if (op instanceof PyFrozenSet) {
            Set<Object> set = new HashSet<>();
            for (Object item : ((PyFrozenSet) op).items) {
                set.add(_PyCode_ConstantKey(item));
            }
            return new ConstantKey(op, Type.FROZENSET, set);
        }
        else if (op instanceof PySlice) {
            PySlice slice = (PySlice) op;
            Object start_key = _PyCode_ConstantKey(slice.start);
            Object stop_key = _PyCode_ConstantKey(slice.stop);
            Object step_key = _PyCode_ConstantKey(slice.step);
            return new ConstantKey(op, Type.SLICE, start_key, stop_key, step_key);
        }
        else {
            /* for other types, use the object identifier as a unique identifier
             * to ensure that they are seen as unequal. */
            return new ConstantKey(op, Type.ID, new Identity(op));
        }
    }

    /** C: PyLong_FromVoidPtr(op), an object's identity. */
    private static final class Identity {
        private final Object op;

        Identity(Object op) {
            this.op = op;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Identity && ((Identity) o).op == op;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(op);
        }
    }
}
