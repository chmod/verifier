package dk.panos.promofacie.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical form for EVM addresses: lowercase hex, as the Robinhood indexer reports them. Hex addresses are
 * case-insensitive (the mixed case of EIP-55 is only a checksum), but the {@code wallet.address} lookups and its
 * unique constraint are not, so every EVM address is normalized before it is stored or looked up.
 * <p>
 * Anything that isn't a 0x-prefixed 40-hex-digit address is returned unchanged: Solana (Base58, case-sensitive,
 * can't start with "0x" because Base58 has no '0') and Cardano (bech32, already lowercase) must never be altered.
 */
public final class EvmAddress {

    private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    private EvmAddress() {
    }

    public static boolean isEvm(String address) {
        return address != null && EVM_ADDRESS.matcher(address.trim()).matches();
    }

    /** Lowercases EVM addresses (trimmed); returns every other value unchanged, including null. */
    public static String normalize(String address) {
        return isEvm(address) ? address.trim().toLowerCase(Locale.ROOT) : address;
    }
}
