"""Fixed parser identity.

The values here are part of the ``document-parse-v1`` contract: the same input
bytes, metadata and parser identity must produce the same result. Do not derive
these from the clock, randomness or the runtime environment.
"""

SCHEMA_VERSION = "document-parse-v1"

# Engine/library versions are part of the contract and intentionally frozen.
PARSER_VERSION = (
    "document-parse-v1;"
    "pdf=pypdf-6.19.0;"
    "xml=defusedxml-0.7.1;"
    "ooxml=ai-worker-safe-ooxml-1"
)
