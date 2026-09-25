package com.invoicematch.core.shared.persistence;

import com.invoicematch.core.shared.domain.Quantity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists a positive {@link Quantity} as an {@code integer}.
 */
@Converter(autoApply = true)
public class QuantityConverter implements AttributeConverter<Quantity, Integer> {

    @Override
    public Integer convertToDatabaseColumn(Quantity attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public Quantity convertToEntityAttribute(Integer dbData) {
        return dbData == null ? null : Quantity.of(dbData);
    }
}
