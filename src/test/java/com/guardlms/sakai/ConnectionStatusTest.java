package com.guardlms.sakai;

import java.io.IOException;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionStatusTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    @Test void rejectedCredentialsRequireReconnectWithoutDeletingTheKeyAndKeepTheFirstRefusal() {
        for (int status : new int[]{401, 403}) {
            Properties state = new Properties();
            state.setProperty("credential", "encrypted-credential");
            state.setProperty("expiresAt", NOW.plusSeconds(86400).toString());
            ConnectionStatus.failed(state, new GuardLmsClient.HttpStatusException(status), NOW);
            ConnectionStatus.failed(state, new GuardLmsClient.HttpStatusException(status), NOW.plusSeconds(180));
            assertEquals("Reconnect required", ConnectionStatus.label(state, true, NOW));
            assertEquals(NOW.toString(), state.getProperty("authRejectedAt"));
            assertEquals("encrypted-credential", state.getProperty("credential"));
            assertEquals("GuardLMS HTTP " + status, state.getProperty("lastError"));
        }
    }

    @Test void acceptedPushRecoversARefusedKeyAndResetsRetries() {
        Properties state = new Properties();
        state.setProperty("credential", "encrypted-credential");
        ConnectionStatus.failed(state, new GuardLmsClient.HttpStatusException(401), NOW);
        ConnectionStatus.failed(state, new IOException("Upstream secret must not appear in settings"), NOW.plusSeconds(60));
        assertEquals(NOW.toString(), state.getProperty("authRejectedAt"));
        assertFalse(state.getProperty("lastError").contains("secret"));
        ConnectionStatus.accepted(state, 105, NOW.plusSeconds(120));
        assertEquals("Connected", ConnectionStatus.label(state, true, NOW.plusSeconds(120)));
        assertNull(state.getProperty("authRejectedAt"));
        assertNull(state.getProperty("lastError"));
        assertNull(state.getProperty("failures"));
        assertEquals("105", state.getProperty("components"));
        assertEquals("encrypted-credential", state.getProperty("credential"));
        assertEquals(NOW.plusSeconds(120 + 86400).getEpochSecond(), Long.parseLong(state.getProperty("nextAttempt")));
    }

    @Test void networkAndServiceFailuresKeepTheConnectionAndUseBoundedBackoff() {
        Properties state = new Properties();
        state.setProperty("credential", "encrypted-credential");
        ConnectionStatus.failed(state, new GuardLmsClient.HttpStatusException(503), NOW);
        assertEquals("Connected", ConnectionStatus.label(state, true, NOW));
        assertEquals(NOW.getEpochSecond() + 60, Long.parseLong(state.getProperty("nextAttempt")));
        for (int i = 0; i < 20; i++) ConnectionStatus.failed(state, new IOException("timeout"), NOW);
        assertEquals(NOW.getEpochSecond() + 3600, Long.parseLong(state.getProperty("nextAttempt")));
        assertNull(state.getProperty("authRejectedAt"));
    }

    @Test void expiredOrUnreadableExpiryRequiresReconnectButDisconnectedStateWins() {
        Properties state = new Properties();
        state.setProperty("expiresAt", NOW.toString());
        assertEquals("Reconnect required", ConnectionStatus.label(state, true, NOW));
        assertEquals("Not connected", ConnectionStatus.label(state, false, NOW));
        state.setProperty("expiresAt", "invalid");
        assertEquals("Reconnect required", ConnectionStatus.label(state, true, NOW));
        state.setProperty("expiresAt", NOW.plusSeconds(1).toString());
        assertEquals("Connected", ConnectionStatus.label(state, true, NOW));
    }
}
