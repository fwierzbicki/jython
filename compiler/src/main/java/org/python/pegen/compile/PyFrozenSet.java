package org.python.pegen.compile;

import java.util.List;

/**
 * A frozenset constant (C: PyFrozenSetObject), as the compiler makes them
 * from a constant set display. Its items are constants, kept in the order
 * they were added; the order C iterates a set in comes in with flowgraph.
 */
public final class PyFrozenSet {

    public final List<Object> items;

    public PyFrozenSet(List<Object> items) {
        this.items = items;
    }

    /** C: PySet_GET_SIZE */
    public int size() {
        return items.size();
    }
}
