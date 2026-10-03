package com.invoicematch.core.analysis.api;

import com.invoicematch.core.analysis.application.ClaimOutcome;
import com.invoicematch.core.document.domain.DocumentEvidence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Machine API response shapes. They are deliberately separate per disposition so
 * a BUSY or terminal response never leaks an empty manifest or token field.
 */
public final class AnalysisMachineViews {

    private AnalysisMachineViews() {
    }

    /** The frozen metadata of one manifest document, exposed without any object key. */
    public record DocumentView(
            UUID documentId,
            UUID sourceDraftRevisionId,
            String fileName,
            String mediaType,
            long sizeBytes,
            String checksum) {

        static DocumentView from(DocumentEvidence document) {
            return new DocumentView(
                    document.documentId(),
                    document.sourceDraftRevisionId(),
                    document.fileName(),
                    document.mediaType(),
                    document.sizeBytes(),
                    document.checksum());
        }
    }

    public record ClaimedRunResponse(
            String disposition,
            UUID claimToken,
            Instant leaseUntil,
            UUID invoiceCaseId,
            UUID evidenceBundleId,
            int inputVersion,
            String evidencePayloadHash,
            String workflowVersion,
            List<DocumentView> documents) {

        static ClaimedRunResponse from(ClaimOutcome.Claimed claimed) {
            return new ClaimedRunResponse(
                    "CLAIMED",
                    claimed.claimToken(),
                    claimed.leaseUntil(),
                    claimed.invoiceCaseId(),
                    claimed.evidenceBundleId(),
                    claimed.inputVersion(),
                    claimed.evidencePayloadHash(),
                    claimed.workflowVersion(),
                    claimed.documents().stream().map(DocumentView::from).toList());
        }
    }

    public record BusyRunResponse(String disposition, Instant leaseUntil) {

        static BusyRunResponse from(ClaimOutcome.Busy busy) {
            return new BusyRunResponse("BUSY", busy.leaseUntil());
        }
    }

    public record DispositionResponse(String disposition) {

        public static DispositionResponse alreadyFinished() {
            return new DispositionResponse("ALREADY_FINISHED");
        }

        public static DispositionResponse stale() {
            return new DispositionResponse("STALE");
        }
    }

    public record HeartbeatResponse(Instant leaseUntil) {
    }

    public record ResultDispositionResponse(String disposition, String runStatus) {
    }
}
