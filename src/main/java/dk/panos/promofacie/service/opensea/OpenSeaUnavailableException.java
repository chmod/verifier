package dk.panos.promofacie.service.opensea;

/** OpenSea could not answer (timeout, rate limit, 5xx, rejected API key). The caller should ask the user to retry. */
public class OpenSeaUnavailableException extends RuntimeException {
    public OpenSeaUnavailableException(String message) {
        super(message);
    }

    public OpenSeaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
