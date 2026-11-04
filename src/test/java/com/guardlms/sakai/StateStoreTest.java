package com.guardlms.sakai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    @TempDir Path directory;
    private String key() { byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key); return Base64.getEncoder().encodeToString(key); }

    @Test void credentialsAreAuthenticatedEncryptedAndNeverPersistInPlaintext() throws Exception {
        var store = new StateStore(directory, key());
        String token = "43|sensitive-inventory-key";
        String encrypted = store.encrypt(token);
        assertEquals(token, store.decrypt(encrypted));
        assertNotEquals(encrypted, store.encrypt(token));
        store.locked(state -> { state.setProperty("credential", encrypted); return null; });
        assertFalse(Files.readString(directory.resolve("state.properties")).contains(token));
        byte[] tampered = Base64.getDecoder().decode(encrypted); tampered[15] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> store.decrypt(Base64.getEncoder().encodeToString(tampered)));
        assertThrows(GeneralSecurityException.class, () -> new StateStore(directory, key()).decrypt(encrypted));
    }

    @Test void overlappingMutationsSerializeAndFailedMutationDoesNotReplaceState() throws Exception {
        var store = new StateStore(directory, key());
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                try { store.locked(state -> { state.setProperty("count", "" + (Integer.parseInt(state.getProperty("count", "0")) + 1)); return null; }); }
                catch (Exception e) { throw new RuntimeException(e); }
            }));
            for (var future : futures) future.get();
            assertEquals("12", store.locked(p -> p.getProperty("count")));
            assertThrows(Exception.class, () -> store.locked(p -> { p.clear(); throw new Exception(); }));
            assertEquals("12", store.locked(p -> p.getProperty("count")));
        } finally { executor.shutdownNow(); }
    }

    @Test void onlyCanonicalHttpsEndpointsAreAllowed() {
        for (String url : List.of("http://api.test", "https://user:pass@api.test", "https://api.test/?secret=x", "https://api.test/#fragment", "https://api.test/a/../b")) {
            assertThrows(IllegalArgumentException.class, () -> GuardLmsClient.httpsUrl(url), url);
        }
        assertEquals("https://api.test", GuardLmsClient.httpsUrl("https://api.test/").toString());
        assertEquals("&lt;script&gt;&amp;&quot;&#39;", GuardLmsServlet.escape("<script>&\"'"));
    }
}
