package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.ConstantRenderer;
import java.util.Optional;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.java.EnumJavaType;

/**
 * Hibernate's {@link ConstantRenderer}: the stored value of a basic attribute's mapped type in the
 * {@code MappingMetamodel}, so a mapped conversion decides it and not a re-read of the annotations (R-VND-16). An
 * {@code @Enumerated(STRING)} constant becomes its name, an {@code ORDINAL} one its ordinal, an
 * {@code AttributeConverter} one its converted database value, and a plain attribute its own value. Compiled against
 * Hibernate 6.6 and run on 7.x as well.
 *
 * @implSpec R-VND-16, R-COL-27
 */
final class HibernateConstantRenderer implements ConstantRenderer {

    private final SessionFactoryImplementor factory;

    HibernateConstantRenderer(SessionFactoryImplementor factory) {
        this.factory = factory;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<Object> storedValue(Class<?> entity, String attribute, Object attributeValue) {
        AttributeMapping mapping = factory.getMappingMetamodel().getEntityDescriptor(entity)
                .findAttributeMapping(attribute);
        BasicValuedModelPart basic = mapping == null ? null : mapping.asBasicValuedModelPart();
        if (basic == null) {
            // No such attribute of the entity, or one that is no basic value: the witness names no literal (MQ1508).
            return Optional.empty();
        }
        JdbcMapping jdbc = basic.getJdbcMapping();
        if (jdbc.getMappedJavaType() instanceof EnumJavaType enumerated) {
            // An @Enumerated attribute: its jdbc type says whether the column holds the name or the ordinal, while
            // convertToRelationalValue would hand the enum itself back (D-128).
            Enum value = (Enum) attributeValue;
            return Optional.of(SqlTypes.isCharacterType(jdbc.getJdbcType().getJdbcTypeCode())
                    ? enumerated.toName(value)
                    : enumerated.toInteger(value));
        }
        return Optional.ofNullable(jdbc.convertToRelationalValue(attributeValue));
    }
}
