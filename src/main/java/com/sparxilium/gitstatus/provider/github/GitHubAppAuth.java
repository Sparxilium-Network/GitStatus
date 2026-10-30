package com.sparxilium.gitstatus.provider.github;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/** GitHub App JWT (RS256) signing. Key material never leaves this class. */
public final class GitHubAppAuth {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final String appId;
    private final PrivateKey key;

    public GitHubAppAuth(String appId, String pem) {
        this.appId = appId;
        this.key = parsePrivateKey(pem);
    }

    /** JWT valid for 9 minutes, back-dated 60 s for clock drift as GitHub recommends. */
    public String createJwt(Instant now) {
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"iat\":" + (now.getEpochSecond() - 60)
                + ",\"exp\":" + (now.getEpochSecond() + 540)
                + ",\"iss\":\"" + appId.replaceAll("[^0-9A-Za-z._-]", "") + "\"}");
        String signingInput = header + "." + payload;
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64URL.encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
    }

    private static String b64(String json) {
        return B64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Accepts PKCS#1 ("BEGIN RSA PRIVATE KEY", as GitHub issues) or PKCS#8 ("BEGIN PRIVATE KEY"). */
    static PrivateKey parsePrivateKey(String pem) {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem.replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("GitHub App private key is not valid PEM");
        }
        if (pkcs1) {
            der = wrapPkcs1(der);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("GitHub App private key could not be parsed");
        }
    }

    /** PrivateKeyInfo ::= SEQUENCE { INTEGER 0, AlgorithmIdentifier rsaEncryption, OCTET STRING pkcs1 } */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] octet = tlv(0x04, pkcs1);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(version);
        body.writeBytes(algorithm);
        body.writeBytes(octet);
        return tlv(0x30, body.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len <= 0xff) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xffff) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
