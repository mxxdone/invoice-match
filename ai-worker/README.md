# ai-worker — bounded PDF/XLSX parser (P2-04)

Python 3.12 parser foundation for the `ai-worker` execution unit. It reads a PDF
text layer and XLSX raw structure under hard resource bounds and emits the
deterministic `document-parse-v1` result. It does **not** open a public HTTP
endpoint, write to a database, call a broker, OCR, use AI, or infer invoice
fields; those follow in later tickets.

## Layout and layer responsibilities

```
src/ai_worker/
  domain/          pure result + error types (no SDK/application/API imports)
  application/     ports, limits policy, parse orchestration, wire mapping
  infrastructure/  pypdf / safe-OOXML / hash / format / process adapters
  api/             internal CLI input/output only
  composition.py   composition root: wires concrete adapters to ports
```

The application layer never imports `pypdf`, `defusedxml`, `zipfile` or the
process/OS adapters; it receives them through `application/ports.py`. The
isolated child process and the parent supervisor live entirely in
`infrastructure/`.

### Engine choice

The XLSX reader is a small purpose-built OOXML reader rather than `openpyxl`.
`openpyxl` converts numbers to Python `int`/`float` (losing the original
lexeme), resolves styles eagerly, and hides the ZIP/decompression bounds this
ticket must enforce on the actual streamed bytes. The custom reader:

* preserves the exact numeric lexeme (e.g. `12345678901234567890`,
  `0.30000000000000004`),
* keeps formulas as `=...` expressions and reports the cached result separately,
* never evaluates formulas or follows external links,
* enforces ZIP, sheet, row/column and cell bounds while decompressing, and
* parses XML with `defusedxml`, rejecting DTDs and entity expansion.

Tradeoff: it implements only the OOXML subset needed for reading cell structure
(shared/inline strings, `t="n|s|str|b|e|inlineStr"`, styles for date detection).
It is not a general XLSX writer or formula engine.

## Limits (raising any is a contract change)

| Limit | Default |
|---|---|
| Input file | 10 MiB |
| PDF pages | 100 |
| XLSX sheets | 20 |
| Rows / columns per sheet | 10,000 / 256 |
| Non-empty cells (whole document) | 100,000 |
| ZIP entries | 1,000 |
| ZIP decompressed total / per entry | 50 MiB / 10 MiB |
| ZIP compression ratio | 100:1 (checked at/above 1 MiB) |
| Extracted text/value UTF-8 | 1 MiB |
| Final result JSON | 4 MiB |
| Wall time per document | 20 s |
| Parser child address space | 512 MiB |

Limit violations raise a stable error code; successful results are never
truncated.

## Running (Windows library tests)

```powershell
py -3.12 -m venv .venv          # or an existing D-drive venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
$env:PYTHONPATH = "$PWD\src"
.\.venv\Scripts\python.exe -m pytest tests
```

Windows runs the library tests and CLI library smoke. The Linux process tests
(`test_process_isolation.py`) are skipped there on purpose: Windows library
tests are **not** proof of Linux OS limits.

## Running (Linux production runtime)

Use a small official Python 3.12 image; the process tests prove wall-timeout,
memory, output and cleanup behaviour for real.

```bash
docker run --rm -v "$PWD":/w -w /w python:3.12-slim \
  sh -c 'pip install --disable-pip-version-check -r requirements-dev.txt &&
         PYTHONPATH=src python -m pytest tests -v'
```

CLI smoke:

```bash
PYTHONPATH=src python -m ai_worker.api.cli version
PYTHONPATH=src python -m ai_worker.api.cli parse-in-process doc.pdf \
  --document-id doc --media-type application/pdf
# isolated production path (Linux only; fails closed elsewhere)
PYTHONPATH=src python -m ai_worker.api.cli parse doc.pdf \
  --document-id doc --media-type application/pdf
```

`python -m ai_worker.api.cli parse` fails closed with `UNSUPPORTED_HOST` on any
host that cannot enforce the OS memory limit.

## Result contract

`schemaVersion: document-parse-v1`, a fixed `parserVersion` that embeds the
engine/library versions, the document id, source size/SHA-256/media type, and
warnings. PDF pages are 1-based and carry extracted text; a page with no text
gets an `EMPTY_TEXT_LAYER` warning and is **not** reported as a scan or OCRed.
XLSX sheets follow workbook order and carry 1-based sheet/row/column and the
cell coordinate, original value and type. No timestamp or random id is emitted.
