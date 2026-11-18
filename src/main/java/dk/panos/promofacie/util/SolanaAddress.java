package dk.panos.promofacie.util;

/** A Solana address is the Base58 encoding of a 32-byte Ed25519 public key. Case-sensitive: never normalize it. */
public final class SolanaAddress {

    private SolanaAddress() {
    }

    public static boolean isValid(String address) {
        if (address == null || address.length() < 32 || address.length() > 44) return false;
        try {
            return Base58.decode(address).length == 32;
        } catch (Exception e) {
            return false;
        }
    }
}
