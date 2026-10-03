from __future__ import annotations

import hashlib


class Sha256Hasher:
    def digest(self, data: bytes) -> str:
        return hashlib.sha256(data).hexdigest()
