package org.python.pegen.compile;

/**
 * A tuple constant (C: PyTupleObject), as the compiler makes them: its
 * items are constants. The items can be replaced while it's being built and
 * by the const cache (const_cache_insert), as C does with PyTuple_SET_ITEM.
 */
public final class PyTuple {

    public final Object[] items;

    public PyTuple(Object... items) {
        this.items = items;
    }

    /** C: PyTuple_GET_SIZE */
    public int size() {
        return items.length;
    }
}
