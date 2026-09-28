#!/usr/bin/env python3
"""Fuegt classes.dex zur von aapt2 erzeugten APK hinzu und richtet unkomprimierte
Eintraege (resources.arsc) auf 4 Byte aus, wie zipalign es tut."""
import sys
import zipfile

src, dex, out = sys.argv[1:4]
entries = []
with zipfile.ZipFile(src) as zin:
    for info in zin.infolist():
        entries.append((info.filename, info.compress_type, zin.read(info.filename)))
with open(dex, "rb") as f:
    entries.append(("classes.dex", zipfile.ZIP_DEFLATED, f.read()))

with zipfile.ZipFile(out, "w") as zout:
    for name, ctype, data in entries:
        zi = zipfile.ZipInfo(name, (2008, 1, 1, 0, 0, 0))
        zi.compress_type = ctype
        zi.external_attr = 0o644 << 16
        if ctype == zipfile.ZIP_STORED:
            header = 30 + len(name.encode("utf-8"))
            zi.extra = b"\0" * ((-(zout.fp.tell() + header)) % 4)
        zout.writestr(zi, data)
