# ai-worker — bounded PDF/XLSX parser (P2-04)

Python 3.12 parser foundation for the `ai-worker` execution unit. It reads a PDF
text layer and XLSX raw structure under hard resource bounds and emits the
deterministic `document-parse-v1` result. Contract and limits are fixed by
[`P2-04`](../project-docs/Plan.md); this README only covers runtime, supported
input scope and how to run it.

It does **not** open a public HTTP endpoint, write to a database, call a
broker, OCR, use AI, or infer invoice fields.

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
isolated child and parent supervisor live entirely in `infrastructure/`.
`application/service.py` owns size/checksum/format verification and all limit
policy; `infrastructure` owns SDK execution and OS/process control.

## Runtime and supported input

* Production runtime: **Linux Python 3.12**. The child applies a hard
  `RLIMIT_AS` memory cap before importing any parser SDK; other hosts fail
  closed with `UNSUPPORTED_HOST`.
* The parent supervisor bounds wall time (including process start and input
  transfer), child output, and reclaims its own process group, pipes and
  threads on success, failure, timeout or interruption.
* PDF: pinned `pypdf`, text layer only. Empty pages are reported with an
  `EMPTY_TEXT_LAYER` warning and are never reported as a scan or OCRed.
* XLSX: a bounded OOXML reader (not openpyxl) chosen to preserve exact numeric
  lexemes and formula expressions, keep cached formula results separate, never
  evaluate formulas or external links, and enforce ZIP/decompression bounds on
  the actual streamed bytes. Structurally invalid workbooks are rejected rather
  than reported as empty. Supported cell types: `n`, `s`, `str`, `inlineStr`,
  `b`, `e`, `d`; shared/inline strings, styles for date detection, workbook
  order and relationships are validated.
* XML is parsed with `defusedxml` with DTDs, entities and external entities all
  forbidden.

## Running

Windows library tests (use the installed Python executable and a D-drive venv;
Linux-only process tests are skipped there):

```powershell
$py = "C:\Users\flash\AppData\Local\Programs\Python\Python312\python.exe"
$venv = "D:\workspace\invoice-match\output\p2-04\venv"      # ignored, on D
& $py -m venv $venv
$env:TEMP = "D:\workspace\invoice-match\output\p2-04\temp"
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
& "$venv\Scripts\python.exe" -m pip install -r requirements-dev.txt
$env:PYTHONPATH = "$PWD\src"
& "$venv\Scripts\python.exe" -m pytest tests
```

Linux (real OS limits): reuse the verification script, which installs the
package as a wheel and runs the full suite plus the installed CLI smoke inside
a small `python:3.12-slim` runtime with a bounded deadline and cleanup. The
Linux venv uses a bounded RAM mount; pip cache and evidence stay on D:

```powershell
pwsh -File ..\scripts\verify-p2-04-linux.ps1
```

The deployable CLI is ``parse`` (isolated production path) and ``version``; it
takes the server-confirmed `--size-bytes`/`--sha256`. The in-process library
entrypoint is for unit tests only and is not exposed as a CLI command.

## Result contract

`schemaVersion: document-parse-v1`, a fixed `parserVersion` embedding engine
and library versions, the document id, source size/SHA-256/media type and
warnings. PDF pages are 1-based with extracted text; XLSX sheets follow
workbook order with 1-based sheet/row/column and cell coordinate, original
value and type. No timestamp or random id is emitted, so identical bytes and
metadata produce identical JSON.
