package org.python.pegen.compile;

/**
 * A slice constant (C: PySliceObject), as codegen makes for a subscript
 * whose bounds are all constants. start, stop and step are constants, None
 * where omitted.
 */
public final class PySlice {

    public final Object start;
    public final Object stop;
    public final Object step;

    public PySlice(Object start, Object stop, Object step) {
        this.start = start;
        this.stop = stop;
        this.step = step;
    }
}
