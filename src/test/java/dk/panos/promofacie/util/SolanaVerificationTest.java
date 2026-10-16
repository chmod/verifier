package dk.panos.promofacie.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SolanaVerificationTest {

    @Test
    void testBase58EncodeDecode() {
        byte[] original = "Hello Solana World!".getBytes(StandardCharsets.UTF_8);
        String encoded = Base58.encode(original);
        byte[] decoded = Base58.decode(encoded);
        assertArrayEquals(original, decoded);

        // Test leading zeroes
        byte[] withZeroes = new byte[]{0, 0, 1, 2, 3, 0, 4};
        String encZeroes = Base58.encode(withZeroes);
        assertArrayEquals(withZeroes, Base58.decode(encZeroes));
    }

    @Test
    void testEd25519SignatureVerification() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();

        byte[] message = "Promofacie Wallet Verification\nDiscord ID: 123456\nNonce: abc123".getBytes(StandardCharsets.UTF_8);

        // Sign message
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(message);
        byte[] signature = signer.sign();

        // Extract raw 32-byte public key from standard X.509 encoded key
        byte[] x509Key = kp.getPublic().getEncoded();
        byte[] rawPubKey = Arrays.copyOfRange(x509Key, 12, 44);
        assertEquals(32, rawPubKey.length);

        // Encode address and signature to Base58 (standard Solana format)
        String solanaAddress = Base58.encode(rawPubKey);
        String sigBase58 = Base58.encode(signature);

        // Verify using reconstructed X509 key
        byte[] decodedPubKey = Base58.decode(solanaAddress);
        byte[] decodedSig = Base58.decode(sigBase58);

        byte[] rfc8410Prefix = new byte[]{0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] fullX509 = new byte[rfc8410Prefix.length + decodedPubKey.length];
        System.arraycopy(rfc8410Prefix, 0, fullX509, 0, rfc8410Prefix.length);
        System.arraycopy(decodedPubKey, 0, fullX509, rfc8410Prefix.length, decodedPubKey.length);

        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(fullX509));

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pubKey);
        verifier.update(message);
        assertTrue(verifier.verify(decodedSig));
    }
}
