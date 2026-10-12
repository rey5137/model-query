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
 * {@code @Enumerated(STRING)} constant becomes its name, an {@code ORDINAL} one its ordinal, by its column's jdbc
 * type (a native enum type is stored by name), an
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
        if (jdbc.getValueConverter() == null && jdbc.getMappedJavaType() instanceof EnumJavaType enumerated) {
            // An @Enumerated attribute: its jdbc type says whether the column holds the name or the ordinal, while
            // convertToRelationalValue would hand the enum itself back. An AttributeConverter's value is its own
            // convertToRelationalValue (D-128).
            return enumerated((Enum) attributeValue, enumerated, jdbc.getJdbcType().getJdbcTypeCode());
        }
        return Optional.ofNullable(jdbc.convertToRelationalValue(attributeValue));
    }

    /** {@code value}'s stored form under the jdbc type {@code code}, or empty for a type it does not name. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<Object> enumerated(Enum value, EnumJavaType enumerated, int code) {
        if (code == SqlTypes.ENUM || code == SqlTypes.NAMED_ENUM || SqlTypes.isCharacterType(code)) {
            return Optional.of(enumerated.toName(value));
        }
        if (code == SqlTypes.ORDINAL_ENUM || code == SqlTypes.NAMED_ORDINAL_ENUM || SqlTypes.isIntegral(code)) {
            return Optional.of(enumerated.toInteger(value));
        }
        return Optional.empty();
    }
}
