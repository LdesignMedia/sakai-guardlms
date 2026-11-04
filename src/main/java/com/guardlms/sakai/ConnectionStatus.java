package com.guardlms.sakai;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/** Connection health mirrors local_guardlms: a stored key is not proof it still works. */
final class ConnectionStatus {
    private ConnectionStatus() { }

    static void accepted(Properties state, int components, Instant at) {
        state.setProperty("lastSuccess", at.toString());
        state.setProperty("components", Integer.toString(components));
        state.remove("lastError");
        state.remove("failures");
        state.remove("authRejectedAt");
        state.setProperty("nextAttempt", Long.toString(at.getEpochSecond() + 86400));
    }

    static void failed(Properties state, Exception error, Instant at) {
        long failures = Math.min(Long.parseLong(state.getProperty("failures", "0")) + 1, 10);
        state.setProperty("failures", Long.toString(failures));
        if (error instanceof GuardLmsClient.HttpStatusException http) {
            state.setProperty("lastError", http.getMessage());
            if (http.status() == 401 || http.status() == 403) {
                // Preserve the first refusal, and keep the encrypted key so a later accepted push can recover.
                state.putIfAbsent("authRejectedAt", at.toString());
            }
        } else {
            state.setProperty("lastError", "Connection failed; check HTTPS trust, credentials and site URL");
        }
        state.setProperty("nextAttempt", Long.toString(at.getEpochSecond() + Math.min(3600, 60L << (failures - 1))));
    }

    static boolean requiresReconnect(Properties state, Instant at) {
        if (state.containsKey("authRejectedAt")) return true;
        String expires = state.getProperty("expiresAt", "");
        if (expires.isBlank()) return false;
        try { return !Instant.parse(expires).isAfter(at); }
        catch (DateTimeParseException e) { return true; }
    }

    static String label(Properties state, boolean connected, Instant at) {
        if (!connected) return "Not connected";
        return requiresReconnect(state, at) ? "Reconnect required" : "Connected";
    }
}
