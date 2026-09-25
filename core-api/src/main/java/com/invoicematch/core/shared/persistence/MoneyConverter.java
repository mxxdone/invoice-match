package com.invoicematch.core.shared.persistence;

import com.invoicematch.core.shared.domain.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists a non-negative {@link Money} value as a whole-won {@code bigint}.
 */
@Converter(autoApply = true)
public class MoneyConverter implements AttributeConverter<Money, Long> {

    @Override
    public Long convertToDatabaseColumn(Money attribute) {
        return attribute == null ? null : attribute.amount();
    }

    @Override
    public Money convertToEntityAttribute(Long dbData) {
        return dbData == null ? null : Money.of(dbData);
    }
}
