package org.python.pegen.ast;

import java.util.Arrays;

/** A bytes Constant value (Python bytes): an immutable byte string with value equality. */
public final class Bytes {

    private final byte[] data;

    public Bytes(byte[] data) {
        this.data = data.clone();
    }

    public int length() {
        return data.length;
    }

    public byte[] toArray() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bytes && Arrays.equals(data, ((Bytes) o).data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "bytes" + Arrays.toString(data);
    }
}
