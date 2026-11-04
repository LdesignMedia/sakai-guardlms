package com.guardlms.sakai;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** A single shared persistent directory serializes reporting and configuration across nodes. */
final class StateStore {
    private final Path directory;
    private final byte[] key;
    private static final SecureRandom RANDOM = new SecureRandom();

    StateStore(Path directory, String encodedKey) throws IOException {
        this.directory = directory;
        this.key = Base64.getDecoder().decode(encodedKey);
        if (key.length != 32) throw new IllegalArgumentException("guardlms.encryptionKey must be a base64 32-byte key");
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    interface Operation<T> { T run(Properties state) throws Exception; }

    synchronized <T> T locked(Operation<T> operation) throws Exception {
        Path lockPath = directory.resolve("state.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
            Properties state = new Properties();
            Path path = directory.resolve("state.properties");
            if (Files.exists(path)) try (InputStream in = Files.newInputStream(path)) { state.load(in); }
            T result = operation.run(state);
            Path temporary = Files.createTempFile(directory, "state-", ".tmp");
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                try (OutputStream out = Files.newOutputStream(temporary)) { state.store(out, "GuardLMS; credential encrypted with AES-256-GCM"); }
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
            return result;
        }
    }

    String encrypt(String plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] result = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(result);
    }

    String decrypt(String encrypted) throws GeneralSecurityException {
        byte[] value = Base64.getDecoder().decode(encrypted);
        if (value.length < 28) throw new GeneralSecurityException("Invalid encrypted credential");
        return new String(cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(value, 12))
            .doFinal(Arrays.copyOfRange(value, 12, value.length)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return cipher;
    }
}
