package dk.panos.promofacie.controller.model;

/**
 * Optional body of POST /api/wallet/challenge. For EVM ({@code chain} = ROBINHOOD) the address is required,
 * because the challenge is an EIP-4361 (Sign-In with Ethereum) message bound to that address.
 */
public record ChallengeRequest(String chain, String address) {
}
