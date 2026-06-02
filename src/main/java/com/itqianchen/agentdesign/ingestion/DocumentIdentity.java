package com.itqianchen.agentdesign.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class DocumentIdentity {

    private static final String SHA_256 = "SHA-256";
    private static final int HASH_BUFFER_SIZE = 8192;

    public String idForPath(String normalizedPath) {
        return sha256Hex(normalizedPath.getBytes(StandardCharsets.UTF_8));
    }

    public String hashText(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public String hashBytes(byte[] bytes) {
        return sha256Hex(bytes);
    }

    public String hashFile(Path path) throws IOException {
        MessageDigest digest = newSha256Digest();
        byte[] buffer = new byte[HASH_BUFFER_SIZE];

        try (InputStream inputStream = Files.newInputStream(path)) {
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256Digest().digest(bytes));
    }

    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
