/* SPDX-License-Identifier: Apache-2.0 */
package android.os;

/** Minimal JVM compatibility surface for loading GPlayApi's parcelized data models. */
public interface Parcelable {
    int describeContents();
    void writeToParcel(Parcel destination, int flags);

    interface Creator<T> {
        T createFromParcel(Parcel source);
        T[] newArray(int size);
    }
}
