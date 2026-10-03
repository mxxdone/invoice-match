"""Application layer: validation, orchestration and limit/failure policy.

Depends only on the domain and on ports. It must not import pypdf, defusedxml or
any concrete infrastructure adapter.
"""
