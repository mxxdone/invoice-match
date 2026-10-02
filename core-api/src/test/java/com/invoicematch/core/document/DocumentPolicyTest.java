package com.invoicematch.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.document.application.DocumentFailure;
import com.invoicematch.core.document.application.DocumentPolicy;
import org.junit.jupiter.api.Test;

/** Pure policy for the authorized-original download contract. */
class DocumentPolicyTest {

    @Test
    void onlyAnAbsentDispositionDefaultsToAttachment() {
        assertThat(DocumentPolicy.disposition(null)).isEqualTo(DocumentPolicy.ATTACHMENT);
        assertThat(DocumentPolicy.disposition("attachment")).isEqualTo(DocumentPolicy.ATTACHMENT);
        assertThat(DocumentPolicy.disposition("inline")).isEqualTo(DocumentPolicy.INLINE);
        for (String invalid : java.util.List.of("", " ", "  ", "popup", "INLINE", "Attachment")) {
            assertThatThrownBy(() -> DocumentPolicy.disposition(invalid))
                    .as("disposition=[%s]", invalid)
                    .isInstanceOf(DocumentFailure.class)
                    .satisfies(e -> assertThat(((DocumentFailure) e).status()).isEqualTo(400));
        }
    }

    @Test
    void inlineIsOnlySupportedForPdf() {
        DocumentPolicy.requireInlineSupported(DocumentPolicy.INLINE, DocumentPolicy.PDF);
        DocumentPolicy.requireInlineSupported(DocumentPolicy.ATTACHMENT, DocumentPolicy.XLSX);
        assertThatThrownBy(() -> DocumentPolicy.requireInlineSupported(DocumentPolicy.INLINE, DocumentPolicy.XLSX))
                .isInstanceOf(DocumentFailure.class)
                .satisfies(e -> assertThat(((DocumentFailure) e).status()).isEqualTo(400));
    }
}
