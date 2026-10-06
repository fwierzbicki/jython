package org.python.pegen.compile;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Singleton;

/**
 * A port of the writing half of Python/marshal.c (w_object), for a code
 * object and the constants the compiler makes: marshal.dumps(code), at
 * version 6 (Py_MARSHAL_VERSION in 3.15).
 *
 * <p>Two things C decides from the state of its objects are decided here
 * from the code object:
 * <ul>
 * <li>{@code FLAG_REF}: C sets it on any object with more than one
 * reference (w_ref, _PyObject_IsUniquelyReferenced) or that is an
 * interned str. Here it's set on an object written more than once (by
 * identity), whose later appearances are then {@code TYPE_REF}. The
 * output differs from C's in the flags, but loads the same.
 * <li>Interning (the {@code *_INTERNED} types): C interns the names, the
 * local names, the file name, name and qualname of a code object
 * (intern_strings, init_code), and its str constants that are all
 * {@code [a-zA-Z0-9_]} (intern_constants, should_intern_string); interning
 * is by value, so any str equal to one of those is interned too.
 * </ul>
 */
public final class Marshal {

    public static final int Py_MARSHAL_VERSION = 6;

    private static final int TYPE_NONE = 'N';
    private static final int TYPE_FALSE = 'F';
    private static final int TYPE_TRUE = 'T';
    private static final int TYPE_ELLIPSIS = '.';
    private static final int TYPE_BINARY_FLOAT = 'g';
    private static final int TYPE_BINARY_COMPLEX = 'y';
    private static final int TYPE_LONG = 'l';
    private static final int TYPE_STRING = 's';
    private static final int TYPE_TUPLE = '(';
    private static final int TYPE_CODE = 'c';
    private static final int TYPE_UNICODE = 'u';
    private static final int TYPE_FROZENSET = '>';
    private static final int TYPE_SLICE = ':';
    private static final int TYPE_INTERNED = 't';
    private static final int TYPE_ASCII = 'a';
    private static final int TYPE_ASCII_INTERNED = 'A';
    private static final int TYPE_SHORT_ASCII = 'z';
    private static final int TYPE_SHORT_ASCII_INTERNED = 'Z';
    private static final int TYPE_INT = 'i';
    private static final int TYPE_SMALL_TUPLE = ')';
    private static final int TYPE_REF = 'r';
    private static final int FLAG_REF = 0x80;

    private static final int MAX_MARSHAL_STACK_DEPTH = 2000;

    private static final int PyLong_MARSHAL_SHIFT = 15;
    private static final int PyLong_MARSHAL_MASK = (1 << PyLong_MARSHAL_SHIFT) - 1;

    /** Thrown where C fails with WFERR_NESTEDTOODEEP or WFERR_UNMARSHALLABLE. */
    public static final class MarshalError extends RuntimeException {
        MarshalError(String message) {
            super(message);
        }
    }

    /** C: WFILE. */
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int depth;
    /** Objects written more than once (by identity): they get FLAG_REF. */
    private final Map<Object, Integer> shared;
    /** The ref index of each FLAG_REF object written so far. */
    private final Map<Object, Integer> refs = new IdentityHashMap<>();
    /** The str values C would have interned. */
    private final Set<String> interned;

    private Marshal(Map<Object, Integer> shared, Set<String> interned) {
        this.shared = shared;
        this.interned = interned;
    }

    /** C: marshal.dumps(co): the bytes of a code object. */
    public static byte[] dumps(PyCodeObject co) {
        Map<Object, Integer> counts = new IdentityHashMap<>();
        Set<String> interned = new HashSet<>();
        count(co, counts, interned);
        Map<Object, Integer> shared = new IdentityHashMap<>();
        for (Map.Entry<Object, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                shared.put(e.getKey(), e.getValue());
            }
        }
        Marshal m = new Marshal(shared, interned);
        m.w_object(co);
        return m.out.toByteArray();
    }

    /**
     * Counts each object's appearances (by identity) in what w_object
     * writes, and collects the str values C interns. Iterative: lambdas
     * nest deeper than the Java stack would take recursion here.
     */
    private static void count(Object top, Map<Object, Integer> counts, Set<String> interned) {
        List<Object> work = new ArrayList<>();
        work.add(top);
        while (!work.isEmpty()) {
            Object v = work.remove(work.size() - 1);
            if (v == null || v instanceof Singleton) {
                continue;
            }
            Integer n = counts.get(v);
            counts.put(v, n == null ? 1 : n + 1);
            if (n != null) {
                continue;  // written as TYPE_REF: its contents aren't written again
            }
            if (v instanceof PyTuple) {
                for (Object x : ((PyTuple) v).items) {
                    work.add(x);
                }
            } else if (v instanceof PyFrozenSet) {
                for (Object x : ((PyFrozenSet) v).items) {
                    work.add(x);
                }
            } else if (v instanceof PySlice) {
                PySlice s = (PySlice) v;
                work.add(s.start);
                work.add(s.stop);
                work.add(s.step);
            } else if (v instanceof PyCodeObject) {
                PyCodeObject co = (PyCodeObject) v;
                for (Object x : co.co_names.items) {
                    interned.add((String) x);
                }
                for (Object x : co.co_localsplusnames.items) {
                    interned.add((String) x);
                }
                interned.add(co.co_filename);
                interned.add(co.co_name);
                interned.add(co.co_qualname);
                intern_constants(co.co_consts, interned);
                work.add(co.co_consts);
                work.add(co.co_names);
                work.add(co.co_localsplusnames);
                work.add(co.co_filename);
                work.add(co.co_name);
                work.add(co.co_qualname);
            }
        }
    }

    /** C: intern_constants (Objects/codeobject.c), the default build. */
    private static void intern_constants(PyTuple tuple, Set<String> interned) {
        for (Object v : tuple.items) {
            if (v instanceof String) {
                if (should_intern_string((String) v)) {
                    interned.add((String) v);
                }
            } else if (v instanceof PyTuple) {
                intern_constants((PyTuple) v, interned);
            } else if (v instanceof PyFrozenSet) {
                intern_constants(new PyTuple(((PyFrozenSet) v).items.toArray()), interned);
            }
        }
    }

    /** C: should_intern_string: s matches [a-zA-Z0-9_]. */
    private static boolean should_intern_string(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private void w_byte(int c) {
        out.write(c);
    }

    private void w_string(byte[] s) {
        out.write(s, 0, s.length);
    }

    private void w_short(int x) {
        w_byte(x & 0xff);
        w_byte((x >> 8) & 0xff);
    }

    private void w_long(int x) {
        w_byte(x & 0xff);
        w_byte((x >> 8) & 0xff);
        w_byte((x >> 16) & 0xff);
        w_byte((x >> 24) & 0xff);
    }

    private void w_pstring(byte[] s) {
        w_long(s.length);
        w_string(s);
    }

    private void w_short_pstring(byte[] s) {
        w_byte(s.length);
        w_string(s);
    }

    private void w_object(Object v) {
        depth++;

        if (depth > MAX_MARSHAL_STACK_DEPTH) {
            throw new MarshalError("object too deeply nested to marshal");
        }
        else if (v == Singleton.None) {
            w_byte(TYPE_NONE);
        }
        else if (v == Singleton.Ellipsis) {
            w_byte(TYPE_ELLIPSIS);
        }
        else if (v == Singleton.False) {
            w_byte(TYPE_FALSE);
        }
        else if (v == Singleton.True) {
            w_byte(TYPE_TRUE);
        }
        else {
            int[] flag = {0};
            if (!w_ref(v, flag)) {
                w_complex_object(v, flag[0]);
            }
        }

        depth--;
    }

    /** C: w_ref: true if v was written as a TYPE_REF; else flag gets FLAG_REF if it's shared. */
    private boolean w_ref(Object v, int[] flag) {
        if (!shared.containsKey(v)) {
            return false;
        }
        Integer w = refs.get(v);
        if (w != null) {
            /* write the reference index to the stream */
            w_byte(TYPE_REF);
            w_long(w);
            return true;
        }
        refs.put(v, refs.size());
        flag[0] |= FLAG_REF;
        return false;
    }

    private void W_TYPE(int t, int flag) {
        w_byte(t | flag);
    }

    private void w_complex_object(Object v, int flag) {
        if (v instanceof BigInteger) {
            BigInteger x = (BigInteger) v;
            if (x.bitLength() <= 31) {
                W_TYPE(TYPE_INT, flag);
                w_long(x.intValue());
            }
            else {
                w_PyLong(x, flag);
            }
        }
        else if (v instanceof Double) {
            W_TYPE(TYPE_BINARY_FLOAT, flag);
            w_float_bin((Double) v);
        }
        else if (v instanceof Complex) {
            W_TYPE(TYPE_BINARY_COMPLEX, flag);
            w_float_bin(((Complex) v).real);
            w_float_bin(((Complex) v).imag);
        }
        else if (v instanceof Bytes) {
            W_TYPE(TYPE_STRING, flag);
            w_pstring(((Bytes) v).toArray());
        }
        else if (v instanceof String) {
            String s = (String) v;
            boolean is_interned = interned.contains(s);
            if (isAscii(s)) {
                byte[] data = s.getBytes(StandardCharsets.ISO_8859_1);
                boolean is_short = s.length() < 256;
                if (is_short) {
                    W_TYPE(is_interned ? TYPE_SHORT_ASCII_INTERNED : TYPE_SHORT_ASCII, flag);
                    w_short_pstring(data);
                }
                else {
                    W_TYPE(is_interned ? TYPE_ASCII_INTERNED : TYPE_ASCII, flag);
                    w_pstring(data);
                }
            }
            else {
                W_TYPE(is_interned ? TYPE_INTERNED : TYPE_UNICODE, flag);
                w_pstring(utf8_surrogatepass(s));
            }
        }
        else if (v instanceof PyTuple) {
            Object[] items = ((PyTuple) v).items;
            int n = items.length;
            if (n < 256) {
                W_TYPE(TYPE_SMALL_TUPLE, flag);
                w_byte(n);
            }
            else {
                W_TYPE(TYPE_TUPLE, flag);
                w_long(n);
            }
            for (Object item : items) {
                w_object(item);
            }
        }
        else if (v instanceof PyFrozenSet) {
            List<Object> items = ((PyFrozenSet) v).items;
            W_TYPE(TYPE_FROZENSET, flag);
            w_long(items.size());
            // bpo-37596: To support reproducible builds, sets and frozensets need
            // to have their elements serialized in a consistent order (even when
            // they have been scrambled by hash randomization). To ensure this, we
            // use an order equivalent to sorted(v, key=marshal.dumps):
            List<Object[]> pairs = new ArrayList<>();
            for (Object value : items) {
                pairs.add(new Object[] {dumpsConstant(value), value});
            }
            pairs.sort((a, b) -> compareBytes((byte[]) a[0], (byte[]) b[0]));
            for (Object[] pair : pairs) {
                w_object(pair[1]);
            }
        }
        else if (v instanceof PyCodeObject) {
            PyCodeObject co = (PyCodeObject) v;
            W_TYPE(TYPE_CODE, flag);
            w_long(co.co_argcount);
            w_long(co.co_posonlyargcount);
            w_long(co.co_kwonlyargcount);
            w_long(co.co_stacksize);
            w_long(co.co_flags);
            W_TYPE(TYPE_STRING, 0);
            w_pstring(co.co_code);  // C: w_object(co_code), a bytes object
            w_object(co.co_consts);
            w_object(co.co_names);
            w_object(co.co_localsplusnames);
            W_TYPE(TYPE_STRING, 0);
            w_pstring(co.co_localspluskinds);
            w_object(co.co_filename);
            w_object(co.co_name);
            w_object(co.co_qualname);
            w_long(co.co_firstlineno);
            W_TYPE(TYPE_STRING, 0);
            w_pstring(co.co_linetable);
            W_TYPE(TYPE_STRING, 0);
            w_pstring(co.co_exceptiontable);
        }
        else if (v instanceof PySlice) {
            PySlice slice = (PySlice) v;
            W_TYPE(TYPE_SLICE, flag);
            w_object(slice.start);
            w_object(slice.stop);
            w_object(slice.step);
        }
        else {
            throw new MarshalError("unmarshallable object " + v.getClass().getSimpleName());
        }
    }

    /**
     * C: w_PyLong, an int too large for TYPE_INT: its absolute value in
     * base 2**15 digits, least significant first, after the digit count
     * (negative for a negative int).
     */
    private void w_PyLong(BigInteger x, int flag) {
        W_TYPE(TYPE_LONG, flag);
        BigInteger d = x.abs();
        List<Integer> digits = new ArrayList<>();
        do {
            digits.add(d.intValue() & PyLong_MARSHAL_MASK);
            d = d.shiftRight(PyLong_MARSHAL_SHIFT);
        } while (d.signum() != 0);
        w_long(x.signum() < 0 ? -digits.size() : digits.size());
        for (int digit : digits) {
            w_short(digit);
        }
    }

    /** C: w_float_bin, PyFloat_Pack8 little-endian. */
    private void w_float_bin(double v) {
        long bits = Double.doubleToRawLongBits(v);
        for (int i = 0; i < 8; i++) {
            w_byte((int) (bits >> (8 * i)) & 0xff);
        }
    }

    /** C: _PyMarshal_WriteObjectToString(value, version, allow_code) for a frozenset item. */
    private static byte[] dumpsConstant(Object value) {
        Map<Object, Integer> counts = new IdentityHashMap<>();
        Set<String> interned = new HashSet<>();
        count(value, counts, interned);
        Map<Object, Integer> shared = new IdentityHashMap<>();
        for (Map.Entry<Object, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                shared.put(e.getKey(), e.getValue());
            }
        }
        Marshal m = new Marshal(shared, interned);
        m.w_object(value);
        return m.out.toByteArray();
    }

    /** bytes comparison (unsigned, then by length), as PyList_Sort orders the dumps. */
    private static int compareBytes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(a[i] & 0xff, b[i] & 0xff);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /** PyUnicode_AsEncodedString(v, "utf8", "surrogatepass"), per UTF-16 code unit pair. */
    private static byte[] utf8_surrogatepass(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c < 0x80) {
                b.write(c);
            } else if (c < 0x800) {
                b.write(0xc0 | (c >> 6));
                b.write(0x80 | (c & 0x3f));
            } else if (c < 0x10000) {
                // a lone surrogate too (surrogatepass)
                b.write(0xe0 | (c >> 12));
                b.write(0x80 | ((c >> 6) & 0x3f));
                b.write(0x80 | (c & 0x3f));
            } else {
                b.write(0xf0 | (c >> 18));
                b.write(0x80 | ((c >> 12) & 0x3f));
                b.write(0x80 | ((c >> 6) & 0x3f));
                b.write(0x80 | (c & 0x3f));
            }
        }
        return b.toByteArray();
    }
}
