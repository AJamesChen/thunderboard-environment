#!/usr/bin/env python3
"""Create a synthetic, non-executable package for parser tests only."""
import hashlib
from pathlib import Path
import struct
import sys
import zipfile
import zlib


def tag(identifier, payload):
    return struct.pack("<II", identifier, len(payload)) + payload


image = tag(0x03A617EB, struct.pack("<II", 0x03000000, 0))
image += tag(0xF40A0AF4, struct.pack("<III16s", 0x20, 2, 0, b"BG22ENV-BRD4184A"))
image += tag(0xFD0303FD, struct.pack("<I", 0x6000) + b"\x00" * 16)
image += struct.pack("<II", 0xFC0404FC, 4)
image += struct.pack("<I", zlib.crc32(image))
manifest = (f"format=1\nboard=BRD4184A-A02\nversion=2\nsize={len(image)}\n"
            f"sha256={hashlib.sha256(image).hexdigest()}\n")
with zipfile.ZipFile(Path(sys.argv[1]), "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("manifest.properties", manifest)
    archive.writestr("application.gbl", image)
