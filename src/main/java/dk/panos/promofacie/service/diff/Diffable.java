package dk.panos.promofacie.service.diff;

public interface Diffable<T> {
    DiffResult diff(T other);
}
