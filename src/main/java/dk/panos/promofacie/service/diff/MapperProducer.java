package dk.panos.promofacie.service.diff;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.mapstruct.factory.Mappers;

@ApplicationScoped
public class MapperProducer {

    @Produces
    @ApplicationScoped
    public EntityToSnapshotMapper entityToSnapshotMapper() {
        return Mappers.getMapper(EntityToSnapshotMapper.class);
    }

    @Produces
    @ApplicationScoped
    public PayloadToSnapshotMapper payloadToSnapshotMapper() {
        return Mappers.getMapper(PayloadToSnapshotMapper.class);
    }
}
