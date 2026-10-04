"""Version gate shared by checkpoint persistence and graph execution adapters.

Legacy workflows retain their own readers; this gate never migrates stored data.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class GraphVersions:
    workflow: str = "ai-review-v2"
    graph: str = "invoice-review-graph-v1"
    serializer: str = "graph-checkpoint-json-v1"
    checkpoint_schema: int = 4

    def require_supported(self) -> None:
        if self != GraphVersions() or type(self.checkpoint_schema) is not int:
            raise ValueError("GRAPH_VERSION_UNSUPPORTED")
