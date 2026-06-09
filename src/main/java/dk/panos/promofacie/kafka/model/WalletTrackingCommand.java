package dk.panos.promofacie.kafka.model;

public record WalletTrackingCommand(Action action, String stakeAddress) {

    public enum Action { ADD, REMOVE }
}
