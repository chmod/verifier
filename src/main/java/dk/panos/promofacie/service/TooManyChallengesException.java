package dk.panos.promofacie.service;

/** Thrown when a user requests more challenges than {@link NonceStore} allows within one TTL window. */
public class TooManyChallengesException extends RuntimeException {
    public TooManyChallengesException() {
        super("Too many verification challenges requested");
    }
}
