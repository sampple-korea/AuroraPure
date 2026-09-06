/* SPDX-License-Identifier: Apache-2.0 */
package android.util;

/** Quiet Android Log compatibility surface for the upstream JVM-compatible protocol core. */
public final class Log {
    public static int v(String tag, String message) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int v(String tag, String message, Throwable error) { return 0; }
    public static int d(String tag, String message, Throwable error) { return 0; }
    public static int i(String tag, String message, Throwable error) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
    public static String getStackTraceString(Throwable error) {
        java.io.StringWriter output = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(output));
        return output.toString();
    }
    private Log() {}
}
