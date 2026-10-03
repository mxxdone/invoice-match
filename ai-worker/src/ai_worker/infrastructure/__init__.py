"""Infrastructure adapters: SDK, file, process and OS boundaries.

Adapters implement application ports. This is the only layer allowed to import
pypdf, defusedxml, zipfile, resource or subprocess.
"""
