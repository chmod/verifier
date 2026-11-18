package dk.panos.promofacie.db;

/** Thrown when a verified wallet is already linked to a different Discord account. Wallets are never moved silently. */
public class WalletAlreadyLinkedException extends RuntimeException {
    public WalletAlreadyLinkedException(String address) {
        super("Wallet " + address + " is already linked to another Discord account");
    }
}
