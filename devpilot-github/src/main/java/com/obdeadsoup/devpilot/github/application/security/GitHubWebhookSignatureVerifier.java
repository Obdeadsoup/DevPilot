package com.obdeadsoup.devpilot.github.application.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class GitHubWebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public boolean verify(byte[] rawBody, String signatureHeader, String secret) {
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX) || secret == null || secret.isBlank()) {
            return false;
        }
        String encodedSignature = signatureHeader.substring(PREFIX.length());
        if (encodedSignature.length() != 64) {
            return false;
        }
        try {
            byte[] provided = HexFormat.of().parseHex(encodedSignature);
            byte[] expected = hmac(rawBody, secret);
            return MessageDigest.isEqual(expected, provided);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public String sha256Hex(byte[] rawBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private byte[] hmac(byte[] rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }
}
