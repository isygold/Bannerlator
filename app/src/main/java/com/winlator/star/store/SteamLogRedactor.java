package com.winlator.star.store;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scrubs credentials + PII out of EVERY line before it is written to a diagnostic file
 * (steam_debug.txt, steam_session.txt). These files are meant to be shared for support, so
 * they must NEVER contain a Steam username, email, or auth/refresh token — including lines that
 * originate from the bundled JavaSteam library, which we do not control.
 *
 * Two layers:
 *   1. Exact-match on known secrets we register at runtime (the account username + refresh token
 *      from prefs). A Steam username is not pattern-detectable, so exact match is the only reliable
 *      way to strip it — this is the primary guarantee.
 *   2. Pattern match as a backstop for anything we did not register: email addresses, JWTs (Steam
 *      refresh/access tokens ARE JWTs), and very long opaque token blobs.
 *
 * The long-token pattern is deliberately bounded high (>= 88 chars) so it can never clobber a
 * 40-hex depot chunk id or a ~19-digit manifest/gid that the log legitimately needs for debugging.
 */
public final class SteamLogRedactor {

    private SteamLogRedactor() {}

    /** Known-sensitive literals (username, refresh token) registered by SteamRepository. */
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
    private static final Pattern LONG_TOKEN =
            Pattern.compile("[A-Za-z0-9_\\-]{88,}");

    /** Register a value (username / refresh token) to be stripped from every future log line. */
    public static void registerSecret(String s) {
        if (s != null && s.trim().length() >= 3) SECRETS.add(s.trim());
    }

    /** Forget all registered secrets (call on sign-out, when they are being cleared anyway). */
    public static void clearSecrets() {
        SECRETS.clear();
    }

    /** Return {@code msg} with every known secret + email + token pattern replaced. Null-safe. */
    public static String redact(String msg) {
        if (msg == null || msg.isEmpty()) return msg;
        String out = msg;
        for (String s : SECRETS) {
            if (!s.isEmpty() && out.contains(s)) out = out.replace(s, "[redacted]");
        }
        out = EMAIL.matcher(out).replaceAll("[email]");
        out = JWT.matcher(out).replaceAll("[token]");
        out = LONG_TOKEN.matcher(out).replaceAll("[token]");
        return out;
    }
}
