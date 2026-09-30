package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.CaseStateConflictException;
import com.invoicematch.core.invoicecase.domain.DraftRevision;
import com.invoicematch.core.invoicecase.domain.DraftRevisionStatus;
import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.domain.EvidenceBundleNotFoundException;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import com.invoicematch.core.invoicecase.persistence.DraftRevisionRepository;
import com.invoicematch.core.invoicecase.persistence.EvidenceBundleRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceLineRepository;
import com.invoicematch.core.security.Actor;
import com.invoicematch.core.security.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the invoice case flow: the case with its current editable draft
 * and every frozen evidence bundle version.
 */
@Service
@Transactional(readOnly = true)
public class InvoiceCaseQueryService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final char LIKE_ESCAPE = '\\';

    private final InvoiceCaseRepository invoiceCases;
    private final DraftRevisionRepository draftRevisions;
    private final InvoiceLineRepository invoiceLines;
    private final EvidenceBundleRepository evidenceBundles;
    private final EntityManager entityManager;

    public InvoiceCaseQueryService(
            InvoiceCaseRepository invoiceCases,
            DraftRevisionRepository draftRevisions,
            InvoiceLineRepository invoiceLines,
            EvidenceBundleRepository evidenceBundles,
            EntityManager entityManager) {
        this.invoiceCases = invoiceCases;
        this.draftRevisions = draftRevisions;
        this.invoiceLines = invoiceLines;
        this.evidenceBundles = evidenceBundles;
        this.entityManager = entityManager;
    }

    /**
     * One server-paged work list. The row scope is enforced here from the
     * authenticated actor: a SUBMITTER can only ever see their own cases, while
     * APPROVER/OPERATOR see every case. A DTO projection is selected so nothing
     * lazy is loaded and there is no N+1; the page size is bounded.
     *
     * <p>{@code invoiceNumber} and {@code purchaseOrderId} are safe partial
     * searches so a caller does not need the full identifier: the invoice number
     * is matched as a literal substring of the normalized value (uppercased,
     * non-alphanumerics removed) and the purchase order id as a
     * case-insensitive literal substring. The user never gets a wildcard query
     * language: {@code %}, {@code _} and {@code \} are escaped and match a
     * literal character. A filter whose input is blank, or whose invoice number
     * normalizes to nothing, is ignored, which is the pre-existing behaviour.
     * {@code supplierId} and {@code submittedBy} stay exact matches, and every
     * active filter is combined with AND.
     */
    public InvoiceCasePage list(InvoiceCaseSearchCriteria criteria, Actor actor) {
        int size = Math.min(Math.max(criteria.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(criteria.page(), 0);
        boolean crossCaseReader = actor.hasRole(Role.APPROVER) || actor.hasRole(Role.OPERATOR);
        String submitterScope = crossCaseReader ? blankToNull(criteria.submittedBy()) : actor.username();
        String normalizedInvoiceNumber = blankToNull(InvoiceNumberNormalizer.normalize(criteria.invoiceNumber()));
        List<InvoiceCaseStatus> statuses = criteria.status() == null
                ? List.of(InvoiceCaseStatus.values())
                : List.of(criteria.status());

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<InvoiceCaseSummary> query = cb.createQuery(InvoiceCaseSummary.class);
        Root<InvoiceCase> root = query.from(InvoiceCase.class);
        query.select(cb.construct(
                InvoiceCaseSummary.class,
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
        query.where(predicates(cb, root, statuses, criteria, submitterScope, normalizedInvoiceNumber)
                .toArray(Predicate[]::new));
        query.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
        List<InvoiceCaseSummary> items = entityManager
                .createQuery(query)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<InvoiceCase> countRoot = countQuery.from(InvoiceCase.class);
        countQuery.select(cb.count(countRoot));
        countQuery.where(predicates(cb, countRoot, statuses, criteria, submitterScope, normalizedInvoiceNumber)
                .toArray(Predicate[]::new));
        long totalItems = entityManager.createQuery(countQuery).getSingleResult();

        int totalPages = (int) ((totalItems + size - 1) / size);
        return new InvoiceCasePage(items, page, size, totalItems, totalPages, page + 1 < totalPages);
    }

    private static List<Predicate> predicates(
            CriteriaBuilder cb,
            Root<InvoiceCase> root,
            List<InvoiceCaseStatus> statuses,
            InvoiceCaseSearchCriteria criteria,
            String submitterScope,
            String normalizedInvoiceNumber) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(root.get("status").in(statuses));
        if (submitterScope != null) {
            predicates.add(cb.equal(root.get("submittedBy"), submitterScope));
        }
        if (criteria.supplierId() != null && !criteria.supplierId().isBlank()) {
            predicates.add(cb.equal(root.get("supplierId"), criteria.supplierId()));
        }
        String purchaseOrderFragment = criteria.purchaseOrderId() == null
                ? null
                : criteria.purchaseOrderId().trim();
        if (purchaseOrderFragment != null && !purchaseOrderFragment.isEmpty()) {
            predicates.add(cb.like(
                    cb.lower(root.get("purchaseOrderId")),
                    containsPattern(purchaseOrderFragment.toLowerCase(Locale.ROOT)),
                    LIKE_ESCAPE));
        }
        if (normalizedInvoiceNumber != null) {
            predicates.add(cb.like(
                    root.get("normalizedInvoiceNumber"), containsPattern(normalizedInvoiceNumber), LIKE_ESCAPE));
        }
        if (criteria.submittedFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), criteria.submittedFrom()));
        }
        if (criteria.submittedTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("submittedAt"), criteria.submittedTo()));
        }
        return predicates;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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

    public InvoiceCaseDetail get(UUID caseId) {
        InvoiceCase invoiceCase =
                invoiceCases.findById(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
        DraftRevision draft = draftRevisions
                .findByInvoiceCaseIdAndStatus(caseId, DraftRevisionStatus.OPEN)
                .orElse(null);
        List<InvoiceLine> lines = draft == null
                ? List.of()
                : invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(draft.id());
        return InvoiceCaseDetail.from(invoiceCase, draft, lines);
    }

    public List<EvidenceBundleSummary> listEvidenceBundles(UUID caseId) {
        requireCase(caseId);
        return evidenceBundles.findByInvoiceCaseIdOrderByVersionNumberAsc(caseId).stream()
                .map(EvidenceBundleSummary::from)
                .toList();
    }

    public EvidenceBundleDetail getEvidenceBundle(UUID caseId, int versionNumber) {
        requireCase(caseId);
        EvidenceBundle bundle = evidenceBundles
                .findByInvoiceCaseIdAndVersionNumber(caseId, versionNumber)
                .orElseThrow(() -> new EvidenceBundleNotFoundException(caseId, versionNumber));
        return EvidenceBundleDetail.from(bundle);
    }

    /**
     * Loads the case header and its latest frozen evidence bundle for the
     * matching module while holding the invoice case row
     * {@code PESSIMISTIC_WRITE} lock. Taking the case lock before reading the
     * bundle is the same case-then-child ordering every P1-03 writer uses, so a
     * concurrent submit or state transition that changes the case version
     * cannot interleave between the state read and the bundle read. A case
     * without a frozen bundle cannot be matched, which is a state conflict
     * rather than a missing resource.
     */
    @Transactional
    public MatchCaseSnapshot loadForMatching(UUID caseId) {
        InvoiceCase invoiceCase =
                invoiceCases.findByIdForUpdate(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
        EvidenceBundle bundle = evidenceBundles
                .findFirstByInvoiceCaseIdOrderByVersionNumberDesc(caseId)
                .orElseThrow(() -> new CaseStateConflictException(
                        caseId, "no frozen evidence bundle exists to match"));
        return new MatchCaseSnapshot(
                invoiceCase.id().value(),
                invoiceCase.supplier().value(),
                invoiceCase.purchaseOrder().value(),
                invoiceCase.invoiceNumber(),
                invoiceCase.normalizedInvoiceNumber(),
                invoiceCase.status(),
                invoiceCase.version(),
                bundle.id(),
                bundle.versionNumber(),
                bundle.payloadHash(),
                bundle.payload());
    }

    /**
     * Identifiers of other cases that share this supplier and normalized invoice
     * number, for the {@code DUPLICATE_INVOICE_SUSPECTED} exception.
     */
    public List<UUID> findOtherCaseIdsWithBusinessInvoice(
            String supplierId, String normalizedInvoiceNumber, UUID excludingCaseId) {
        return invoiceCases.findOtherCaseIdsByBusinessInvoice(
                supplierId, normalizedInvoiceNumber, excludingCaseId);
    }

    /**
     * Throws {@link InvoiceCaseNotFoundException} when the case does not exist.
     */
    public void requireCase(UUID caseId) {
        if (!invoiceCases.existsById(caseId)) {
            throw new InvoiceCaseNotFoundException(caseId);
        }
    }
}
