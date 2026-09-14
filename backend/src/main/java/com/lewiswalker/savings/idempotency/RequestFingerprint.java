package com.lewiswalker.savings.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A digest of what was asked for, so a key reused with different content can be told from
 * a retry. A digest rather than the content because this is stored in Redis.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    public static String of(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                // Length-prefixed, so ("ab", "c") and ("a", "bc") do not collide.
                digest.update(Integer.toString(part == null ? -1 : part.length())
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                if (part != null) {
                    digest.update(part.getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
