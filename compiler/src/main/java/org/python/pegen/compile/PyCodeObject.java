package org.python.pegen.compile;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Singleton;

/**
 * The code object (C: PyCodeObject), as Objects/codeobject.c builds it from
 * a {@link _PyCodeConstructor} (_PyCode_Validate, _PyCode_New, init_code),
 * the localsplus helpers, code_richcompare, and _PyCode_ConstantKey.
 *
 * <p>A plain value: C's bytes fields are byte arrays, and its tuples
 * {@link PyTuple}s. Of quickening, only fixup_getiter is ported: the rest
 * (warmup counters, specialization) doesn't show in co_code. Not ported:
 * interning, monitoring and {@code co_framesize} (which depends on the
 * size of C's _PyInterpreterFrame); none of them shows in what marshal
 * writes.
 */
public final class PyCodeObject {

    /* Kinds of locals (C: _PyLocals_Kind, pycore_code.h). */
    public static final int CO_FAST_ARG_POS = 0x02;  // pos-only, pos-or-kw, varargs
    public static final int CO_FAST_ARG_KW = 0x04;   // kw-only, pos-or-kw, varkwargs
    public static final int CO_FAST_ARG_VAR = 0x08;  // varargs, varkwargs
    public static final int CO_FAST_ARG = CO_FAST_ARG_POS | CO_FAST_ARG_KW | CO_FAST_ARG_VAR;
    public static final int CO_FAST_HIDDEN = 0x10;
    public static final int CO_FAST_LOCAL = 0x20;
    public static final int CO_FAST_CELL = 0x40;
    public static final int CO_FAST_FREE = 0x80;

    /** C: struct _PyCodeConstructor (pycore_code.h). */
    public static final class _PyCodeConstructor {
        /* metadata */
        public String filename;
        public String name;
        public String qualname;
        public int flags;

        /* the code */
        public byte[] code;
        public int firstlineno;
        public byte[] linetable;

        /* used by the code */
        public PyTuple consts;
        public PyTuple names;

        /* mapping frame offsets to information */
        public PyTuple localsplusnames;  // Tuple of strings
        public byte[] localspluskinds;  // Bytes object, one byte per variable

        /* args (within varnames) */
        public int argcount;
        public int posonlyargcount;
        // XXX Replace argcount with posorkwargcount (argcount - posonlyargcount).
        public int kwonlyargcount;

        /* needed to create the frame */
        public int stacksize;

        /* used by the eval loop */
        public byte[] exceptiontable;
    }

    public final int co_argcount;
    public final int co_posonlyargcount;
    public final int co_kwonlyargcount;
    public final int co_stacksize;
    public final int co_flags;
    /** The bytecode: C's co_code as _PyCode_GetCode gives it (no specialization). */
    public final byte[] co_code;
    public final PyTuple co_consts;
    public final PyTuple co_names;
    /**
     * Not final: codegen_leave_annotations_scope replaces it, as C does
     * (before the object is hashed; see hashCode).
     */
    public PyTuple co_localsplusnames;
    public final byte[] co_localspluskinds;
    public final String co_filename;
    public final String co_name;
    public final String co_qualname;
    public final int co_firstlineno;
    public final byte[] co_linetable;
    public final byte[] co_exceptiontable;

    /* derived values */
    public final int co_nlocalsplus;
    public final int co_nlocals;
    public final int co_ncellvars;
    public final int co_nfreevars;

    /** C: _PyCode_New, after _PyCode_Validate (init_code). */
    public PyCodeObject(_PyCodeConstructor con) {
        int nlocalsplus = con.localsplusnames.size();
        int[] counts = get_localsplus_counts(con.localsplusnames, con.localspluskinds);
        if (con.stacksize == 0) {
            con.stacksize = 1;
        }

        co_filename = con.filename;
        co_name = con.name;
        co_qualname = con.qualname;
        co_flags = con.flags;

        co_firstlineno = con.firstlineno;
        co_linetable = con.linetable;

        co_consts = con.consts;
        co_names = con.names;

        co_localsplusnames = con.localsplusnames;
        co_localspluskinds = con.localspluskinds;

        co_argcount = con.argcount;
        co_posonlyargcount = con.posonlyargcount;
        co_kwonlyargcount = con.kwonlyargcount;

        co_stacksize = con.stacksize;

        co_exceptiontable = con.exceptiontable;

        /* derived values */
        co_nlocalsplus = nlocalsplus;
        co_nlocals = counts[0];
        co_ncellvars = counts[1];
        co_nfreevars = counts[2];

        co_code = con.code.clone();
        _PyCode_Quicken(co_code, co_code.length / 2, co_flags);
    }

    /* GET_ITER's oparg after quickening (C: pycore_opcode_utils.h). */
    static final int GET_ITER_YIELD_FROM = 1;
    static final int GET_ITER_YIELD_FROM_NO_CHECK = 2;
    static final int GET_ITER_YIELD_FROM_CORO_CHECK = 3;

    /** C: fixup_getiter (Python/specialize.c), instruction at code unit i. */
    private static void fixup_getiter(byte[] instructions, int i, int flags) {
        // Compiler can't know if types.coroutine() will be called,
        // so fix up here
        if (instructions[2 * i + 1] != 0) {
            if ((flags & (Compile.CO_COROUTINE | Compile.CO_ITERABLE_COROUTINE)) != 0) {
                instructions[2 * i + 1] = GET_ITER_YIELD_FROM_NO_CHECK;
            }
            else {
                instructions[2 * i + 1] = GET_ITER_YIELD_FROM_CORO_CHECK;
            }
        }
    }

    /**
     * C: _PyCode_Quicken (Python/specialize.c), only what shows in co_code:
     * fixup_getiter. The warmup counters it writes into the caches are
     * zeroed again by _PyCode_GetCode.
     */
    private static void _PyCode_Quicken(byte[] instructions, int size, int flags) {
        /* The last code unit cannot have a cache, so we don't need to check it */
        for (int i = 0; i < size-1; i++) {
            int opcode = instructions[2 * i] & 0xff;
            if (opcode == Opcode.GET_ITER) {
                fixup_getiter(instructions, i, flags);
            }
            i += Opcode._PyOpcode_Caches[opcode];
        }
    }

    /** C: _PyCode_Validate (the checks that can fail on the compiler's output). */
    public static void _PyCode_Validate(_PyCodeConstructor con) {
        /* Check argument types */
        if (con.argcount < con.posonlyargcount || con.posonlyargcount < 0 ||
            con.kwonlyargcount < 0 ||
            con.stacksize < 0 || con.flags < 0 ||
            con.code == null ||
            con.consts == null ||
            con.names == null ||
            con.localsplusnames == null ||
            con.localspluskinds == null ||
            con.localsplusnames.size() != con.localspluskinds.length ||
            con.name == null ||
            con.qualname == null ||
            con.filename == null ||
            con.linetable == null ||
            con.exceptiontable == null
            ) {
            throw new IllegalStateException("bad argument to internal function");
        }

        /* Make sure that code is indexable with an int, this is
           a long running assumption in ceval.c and many parts of
           the interpreter. */
        if (con.code.length % 2 != 0) {
            throw new IllegalStateException("code: co_code is malformed");
        }

        /* Ensure that the co_varnames has enough names to cover the arg counts.
         * Note that totalargs = nlocals - nplainlocals.  We check nplainlocals
         * here to avoid the possibility of overflow (however remote). */
        int nlocals = get_localsplus_counts(con.localsplusnames, con.localspluskinds)[0];
        int nplainlocals = nlocals -
                           con.argcount -
                           con.kwonlyargcount -
                           ((con.flags & Compile.CO_VARARGS) != 0 ? 1 : 0) -
                           ((con.flags & Compile.CO_VARKEYWORDS) != 0 ? 1 : 0);
        if (nplainlocals < 0) {
            throw new IllegalStateException("code: co_varnames is too small");
        }
    }

    /** C: _Py_set_localsplus_info */
    static void _Py_set_localsplus_info(int offset, String name, int kind, PyTuple names,
            byte[] kinds) {
        names.items[offset] = name;
        kinds[offset] = (byte) kind;
    }

    /** C: _PyLocals_GetKind */
    public static int _PyLocals_GetKind(byte[] kinds, int i) {
        return kinds[i] & 0xff;
    }

    /** C: get_localsplus_counts: {nlocals, ncellvars, nfreevars}. */
    private static int[] get_localsplus_counts(PyTuple names, byte[] kinds) {
        int nlocals = 0;
        int ncellvars = 0;
        int nfreevars = 0;
        int nlocalsplus = names.size();
        for (int i = 0; i < nlocalsplus; i++) {
            int kind = _PyLocals_GetKind(kinds, i);
            if ((kind & CO_FAST_LOCAL) != 0) {
                nlocals += 1;
                if ((kind & CO_FAST_CELL) != 0) {
                    ncellvars += 1;
                }
            }
            else if ((kind & CO_FAST_CELL) != 0) {
                ncellvars += 1;
            }
            else if ((kind & CO_FAST_FREE) != 0) {
                nfreevars += 1;
            }
        }
        return new int[] {nlocals, ncellvars, nfreevars};
    }

    /** C: get_localsplus_names */
    private List<String> get_localsplus_names(int kind, int num) {
        List<String> names = new ArrayList<>(num);
        for (int offset = 0; offset < co_nlocalsplus; offset++) {
            int k = _PyLocals_GetKind(co_localspluskinds, offset);
            if ((k & kind) == 0) {
                continue;
            }
            names.add((String) co_localsplusnames.items[offset]);
        }
        assert names.size() == num;
        return names;
    }

    /** C: _PyCode_GetVarnames, the co_varnames attribute. */
    public List<String> co_varnames() {
        return get_localsplus_names(CO_FAST_LOCAL, co_nlocals);
    }

    /** C: _PyCode_GetCellvars, the co_cellvars attribute. */
    public List<String> co_cellvars() {
        return get_localsplus_names(CO_FAST_CELL, co_ncellvars);
    }

    /** C: _PyCode_GetFreevars, the co_freevars attribute. */
    public List<String> co_freevars() {
        return get_localsplus_names(CO_FAST_FREE, co_nfreevars);
    }

    /** C: PyUnstable_Code_GetFirstFree */
    public int PyUnstable_Code_GetFirstFree() {
        return co_nlocalsplus - co_nfreevars;
    }

    /** C: _Py_GetBaseCodeUnit's opcode (no specialization here, so the code unit). */
    private int code_unit_op(int i) {
        return co_code[2 * i] & 0xff;
    }

    /**
     * C: code_richcompare, which the const cache uses (a code object is its
     * own key): equal code objects are one constant.
     */
    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof PyCodeObject)) {
            return false;
        }
        PyCodeObject co = this;
        PyCodeObject cp = (PyCodeObject) other;

        if (!co.co_name.equals(cp.co_name)) return false;
        if (co.co_argcount != cp.co_argcount) return false;
        if (co.co_posonlyargcount != cp.co_posonlyargcount) return false;
        if (co.co_kwonlyargcount != cp.co_kwonlyargcount) return false;
        if (co.co_flags != cp.co_flags) return false;
        if (co.co_firstlineno != cp.co_firstlineno) return false;
        if (co.co_code.length != cp.co_code.length) return false;
        int size = co.co_code.length / 2;
        for (int i = 0; i < size; i++) {
            // Base code units, opcode and arg (C: the 16-bit cache view).
            if (co.co_code[2 * i] != cp.co_code[2 * i]
                    || co.co_code[2 * i + 1] != cp.co_code[2 * i + 1]) {
                return false;
            }
            i += Opcode._PyOpcode_Caches[co.code_unit_op(i)];
        }

        /* compare constants */
        if (!_PyCode_ConstantKey(co.co_consts).equals(_PyCode_ConstantKey(cp.co_consts))) {
            return false;
        }

        if (!Arrays.equals(co.co_names.items, cp.co_names.items)) return false;
        if (!Arrays.equals(co.co_localsplusnames.items, cp.co_localsplusnames.items)) {
            return false;
        }
        if (!Arrays.equals(co.co_linetable, cp.co_linetable)) return false;
        return Arrays.equals(co.co_exceptiontable, cp.co_exceptiontable);
    }

    /** code_hash's result, computed once (see hashCode). */
    private int hash;
    private boolean hashed;

    /**
     * C: code_hash. Its result is kept: C computes it again each time, but
     * nested code objects make that recursive, and lambdas nested a
     * thousand deep make it slow. A code object doesn't change once hashed
     * (codegen_leave_annotations_scope replaces co_localsplusnames before
     * the const cache sees the object).
     */
    @Override
    public int hashCode() {
        if (hashed) {
            return hash;
        }
        int uhash = 20221211;
        uhash = scramble(uhash, co_name.hashCode());
        uhash = scramble(uhash, _PyCode_ConstantKey(co_consts).hashCode());
        uhash = scramble(uhash, Arrays.hashCode(co_names.items));
        uhash = scramble(uhash, Arrays.hashCode(co_localsplusnames.items));
        uhash = scramble(uhash, Arrays.hashCode(co_linetable));
        uhash = scramble(uhash, Arrays.hashCode(co_exceptiontable));
        uhash = scramble(uhash, co_argcount);
        uhash = scramble(uhash, co_posonlyargcount);
        uhash = scramble(uhash, co_kwonlyargcount);
        uhash = scramble(uhash, co_flags);
        uhash = scramble(uhash, co_firstlineno);
        int size = co_code.length / 2;
        uhash = scramble(uhash, size);
        for (int i = 0; i < size; i++) {
            uhash = scramble(uhash, co_code[2 * i] & 0xff);
            uhash = scramble(uhash, co_code[2 * i + 1] & 0xff);
            i += Opcode._PyOpcode_Caches[code_unit_op(i)];
        }
        hash = uhash;
        hashed = true;
        return hash;
    }

    /** C: SCRAMBLE_IN (PyHASH_MULTIPLIER is 1000003). */
    private static int scramble(int uhash, int h) {
        return (uhash ^ h) * 1000003;
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
