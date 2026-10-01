package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.shared.domain.DomainValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Repository;

/**
 * Custom Criteria query module for the invoice case work list. It owns the
 * {@link EntityManager}, the builder/typesafe projection, every predicate and
 * the {@code LIKE} escaping, so the application service keeps only access-scope
 * and normalization policy.
 *
 * <p>The result window is computed as a {@code long} offset and rejected with
 * {@link DomainValidationException} when it exceeds
 * {@link Integer#MAX_VALUE}; a page whose offset still fits is narrowed only
 * after the check. No page can overflow the JDBC first-result or the
 * {@code hasNext} arithmetic.
 */
@Repository
public class InvoiceCaseListQueryStore {

    private static final char LIKE_ESCAPE = '\\';

    private final EntityManager entityManager;

    public InvoiceCaseListQueryStore(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public InvoiceCaseListSlice query(InvoiceCaseListQuery query) {
        long offset = query.page() * (long) query.size();
        if (offset > Integer.MAX_VALUE) {
            throw new DomainValidationException("page is out of range for the requested size");
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<InvoiceCaseListRow> listQuery = cb.createQuery(InvoiceCaseListRow.class);
        Root<InvoiceCase> root = listQuery.from(InvoiceCase.class);
        listQuery.select(cb.construct(
                InvoiceCaseListRow.class,
                root.get("id"),
                root.get("supplierId"),
                root.get("purchaseOrderId"),
                root.get("invoiceNumber"),
                root.get("submittedBy"),
                root.get("status"),
                root.get("version"),
                root.get("createdAt"),
                root.get("updatedAt"),
                root.get("submittedAt")));
        listQuery.where(predicates(cb, root, query).toArray(Predicate[]::new));
        listQuery.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
        List<InvoiceCaseListRow> rows = entityManager
                .createQuery(listQuery)
                .setFirstResult((int) offset)
                .setMaxResults(query.size())
                .getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<InvoiceCase> countRoot = countQuery.from(InvoiceCase.class);
        countQuery.select(cb.count(countRoot));
        countQuery.where(predicates(cb, countRoot, query).toArray(Predicate[]::new));
        long totalItems = entityManager.createQuery(countQuery).getSingleResult();

        return new InvoiceCaseListSlice(rows, totalItems);
    }

    private static List<Predicate> predicates(CriteriaBuilder cb, Root<InvoiceCase> root, InvoiceCaseListQuery query) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(root.get("status").in(query.statuses()));
        if (query.submitterScope() != null) {
            predicates.add(cb.equal(root.get("submittedBy"), query.submitterScope()));
        }
        if (query.supplierId() != null && !query.supplierId().isBlank()) {
            predicates.add(cb.equal(root.get("supplierId"), query.supplierId()));
        }
        String purchaseOrderFragment = query.purchaseOrderId() == null
                ? null
                : query.purchaseOrderId().trim();
        if (purchaseOrderFragment != null && !purchaseOrderFragment.isEmpty()) {
            predicates.add(cb.like(
                    cb.lower(root.get("purchaseOrderId")),
                    containsPattern(purchaseOrderFragment.toLowerCase(Locale.ROOT)),
                    LIKE_ESCAPE));
        }
        if (query.normalizedInvoiceNumber() != null) {
            predicates.add(cb.like(
                    root.get("normalizedInvoiceNumber"),
                    containsPattern(query.normalizedInvoiceNumber()),
                    LIKE_ESCAPE));
        }
        if (query.submittedFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), query.submittedFrom()));
        }
        if (query.submittedTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("submittedAt"), query.submittedTo()));
        }
        return predicates;
    }

    /**
     * Builds a bound {@code LIKE} pattern for a contains match. The literal is
     * escaped first so the caller's {@code %}, {@code _} and {@code \} are
     * matched as ordinary characters instead of turning into wildcards or
     * escape sequences.
     */
    private static String containsPattern(String literal) {
        return "%" + escapeLike(literal) + "%";
    }

    private static String escapeLike(String literal) {
        StringBuilder escaped = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** Raw rows plus the unbounded total needed to compute page metadata. */
    public record InvoiceCaseListSlice(List<InvoiceCaseListRow> rows, long totalItems) {

        public InvoiceCaseListSlice {
            rows = List.copyOf(rows);
        }
    }
}
