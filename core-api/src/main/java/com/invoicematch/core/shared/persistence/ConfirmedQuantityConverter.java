package com.invoicematch.core.shared.persistence;

import com.invoicematch.core.shared.domain.ConfirmedQuantity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists a non-negative {@link ConfirmedQuantity} as an {@code integer}.
 */
@Converter(autoApply = true)
public class ConfirmedQuantityConverter implements AttributeConverter<ConfirmedQuantity, Integer> {

    @Override
    public Integer convertToDatabaseColumn(ConfirmedQuantity attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public ConfirmedQuantity convertToEntityAttribute(Integer dbData) {
        return dbData == null ? null : ConfirmedQuantity.of(dbData);
    }
}
