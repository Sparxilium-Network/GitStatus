package com.sparxilium.gitstatus.provider.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class GitHubAppAuthTest {

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    @Test
    void signsVerifiableJwtWithPkcs1AndPkcs8Keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        byte[] pkcs8 = pair.getPrivate().getEncoded();
        // a 2048-bit PKCS#8 RSA key is a fixed 26-byte header followed by the PKCS#1 structure
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);

        for (String pem : new String[] {pem("PRIVATE KEY", pkcs8), pem("RSA PRIVATE KEY", pkcs1)}) {
            String jwt = new GitHubAppAuth("12345", pem).createJwt(Instant.ofEpochSecond(1_000_000));
            String[] parts = jwt.split("\\.");
            assertEquals(3, parts.length);

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            assertEquals("{\"iat\":999940,\"exp\":1000540,\"iss\":\"12345\"}", payload);

            Signature verify = Signature.getInstance("SHA256withRSA");
            verify.initVerify(pair.getPublic());
            verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertTrue(verify.verify(Base64.getUrlDecoder().decode(parts[2])));
        }
    }
}
