package dk.panos.promofacie.config;

import io.quarkus.hibernate.orm.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.inject.Singleton;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.hibernate.type.format.FormatMapper;

@JsonFormat
@PersistenceUnitExtension
@Singleton
public class HibernateJsonFormatMapper implements FormatMapper {

    private final JacksonJsonFormatMapper delegate;

    public HibernateJsonFormatMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.delegate = new JacksonJsonFormatMapper(mapper);
    }


    @Override
    public <T> T fromString(CharSequence charSequence, org.hibernate.type.descriptor.java.JavaType<T> javaType, WrapperOptions wrapperOptions) {
        return delegate.fromString(charSequence, javaType, wrapperOptions);
    }

    @Override
    public <T> String toString(T value, org.hibernate.type.descriptor.java.JavaType<T> javaType, WrapperOptions wrapperOptions) {
        return delegate.toString(value, javaType, wrapperOptions);
    }
}