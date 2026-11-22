#!/usr/bin/env python3
"""Binary files are stored in git as base64 text under binaries/ (so they can be pushed with
text-only tooling). `unpack` restores them to their real paths (checking sha256); `pack` refreshes
binaries/ from the real files. Usage: python3 tools/binaries.py unpack|pack"""
import base64, hashlib, json, os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR = os.path.join(ROOT, "binaries")
MANIFEST = os.path.join(DIR, "manifest.json")
FILES = [
    "gradle/wrapper/gradle-wrapper.jar",
    "app/src/main/res/font/plex_mono_medium.ttf",
    "app/src/main/res/font/plex_mono_regular.ttf",
    "app/src/main/res/font/unifraktur.ttf",
    "recovery/lib/asm-9.8.jar",
    "recovery/lib/asm-tree-9.8.jar",
    "recovery/splinklez-1.1.0-classes.jar",
]

def b64path(p):
    return os.path.join(DIR, p.replace("/", "__") + ".b64")

def pack():
    os.makedirs(DIR, exist_ok=True)
    man = {}
    for p in FILES:
        data = open(os.path.join(ROOT, p), "rb").read()
        man[p] = hashlib.sha256(data).hexdigest()
        enc = base64.encodebytes(data).decode()  # 76-char lines
        open(b64path(p), "w").write(enc)
    json.dump(man, open(MANIFEST, "w"), indent=2, sort_keys=True)
    open(MANIFEST, "a").write("\n")
    print(f"packed {len(FILES)} files")

def unpack():
    man = json.load(open(MANIFEST))
    for p, sha in man.items():
        data = base64.b64decode(open(b64path(p)).read())
        if hashlib.sha256(data).hexdigest() != sha:
            sys.exit(f"sha256 mismatch for {p}")
        dst = os.path.join(ROOT, p)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, "wb").write(data)
    print(f"unpacked {len(man)} files")

{"pack": pack, "unpack": unpack}[sys.argv[1] if len(sys.argv) > 1 else "unpack"]()
