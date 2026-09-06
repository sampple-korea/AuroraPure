/* SPDX-License-Identifier: Apache-2.0 */
package android.os;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-persisting Parcel shim. Aurora Pure CLI never parcels GPlayApi objects; the methods only
 * satisfy the Android-shaped bytecode carried by the upstream AAR on a standard JVM.
 */
public final class Parcel {
    public String readString() { throw unsupported(); }
    public int readInt() { throw unsupported(); }
    public long readLong() { throw unsupported(); }
    public float readFloat() { throw unsupported(); }
    public double readDouble() { throw unsupported(); }
    public byte readByte() { throw unsupported(); }
    public void writeString(String value) { throw unsupported(); }
    public void writeInt(int value) { throw unsupported(); }
    public void writeLong(long value) { throw unsupported(); }
    public void writeFloat(float value) { throw unsupported(); }
    public void writeDouble(double value) { throw unsupported(); }
    public void writeByte(byte value) { throw unsupported(); }
    public void writeStringList(List<String> value) { throw unsupported(); }
    public ArrayList<String> createStringArrayList() { throw unsupported(); }
    public <T extends Parcelable> void writeParcelable(T value, int flags) { throw unsupported(); }
    public <T> T readParcelable(ClassLoader loader) { throw unsupported(); }
    public <T extends Parcelable> void writeTypedList(List<T> value) { throw unsupported(); }
    public <T> ArrayList<T> createTypedArrayList(Parcelable.Creator<T> creator) { throw unsupported(); }
    public void writeList(List<?> value) { throw unsupported(); }
    public ArrayList<?> readArrayList(ClassLoader loader) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Parcel is unavailable in Aurora Pure CLI");
    }
}
