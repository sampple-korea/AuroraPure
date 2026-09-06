/* SPDX-License-Identifier: Apache-2.0 */
package android.util;

import java.nio.charset.StandardCharsets;

/** Android Base64 API backed by the JDK implementation. */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;

    private Base64() {}

    public static byte[] decode(String value, int flags) {
        return decoder(flags).decode(value);
    }

    public static byte[] decode(byte[] value, int flags) {
        return decoder(flags).decode(value);
    }

    public static String encodeToString(byte[] value, int flags) {
        java.util.Base64.Encoder encoder = encoder(flags);
        if ((flags & NO_PADDING) != 0) encoder = encoder.withoutPadding();
        return encoder.encodeToString(value);
    }

    public static byte[] encode(byte[] value, int flags) {
        return encodeToString(value, flags).getBytes(StandardCharsets.US_ASCII);
    }

    private static java.util.Base64.Decoder decoder(int flags) {
        return (flags & URL_SAFE) != 0
            ? java.util.Base64.getUrlDecoder()
            : java.util.Base64.getMimeDecoder();
    }

    private static java.util.Base64.Encoder encoder(int flags) {
        return (flags & URL_SAFE) != 0
            ? java.util.Base64.getUrlEncoder()
            : java.util.Base64.getEncoder();
    }
}
