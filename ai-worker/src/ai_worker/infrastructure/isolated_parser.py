from ai_worker.application.execution import Document
from ai_worker.application.limits import DEFAULT_LIMITS
from ai_worker.infrastructure.process_supervisor import ProcessSupervisor


class IsolatedParser:
    def parse(self, document: Document, data: bytes) -> dict:
        header = {"documentId": document.document_id, "mediaType": document.media_type,
                  "sizeBytes": document.size, "sha256": document.checksum,
                  "limits": DEFAULT_LIMITS.to_wire()}
        return ProcessSupervisor().run(header, data, DEFAULT_LIMITS)
