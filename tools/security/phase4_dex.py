#!/usr/bin/env python3
"""Inspect DEX files in an unsigned release APK. Does not sign and does not keep decompiled trees."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import zipfile

DEXDUMP = os.path.expanduser("~/android-sdk/build-tools/35.0.0/dexdump")
JADX = os.environ.get("JADX", "/tmp/jadx/bin/jadx")


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def dex_stats(blob: bytes) -> dict:
    # DEX header: magic, checksum, signature, file_size, header_size, endian, ...
    # string_ids_size at 56, type_ids_size at 64, method_ids_size at 88, class_defs_size at 96.
    if len(blob) < 112 or not blob.startswith(b"dex\n"):
        return {"parse": "FAIL"}
    def u32(off: int) -> int:
        return int.from_bytes(blob[off:off + 4], "little")
    return {
        "magic": blob[:8].decode("ascii", "replace"),
        "fileSize": u32(32),
        "headerSize": u32(36),
        "stringIds": u32(56),
        "typeIds": u32(64),
        "protoIds": u32(72),
        "fieldIds": u32(80),
        "methodIds": u32(88),
        "classDefs": u32(96),
    }


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: phase4_dex.py APK OUT.json")
    apk, out = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        dex_names = sorted(n for n in names if n.endswith(".dex"))
        native = sorted(n for n in names if n.startswith("lib/") and n.endswith(".so"))
        assets = [n for n in names if n.startswith("assets/runtime/")]
        dex = []
        for name in dex_names:
            blob = z.read(name)
            dex.append({"name": name, "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest(), "header": dex_stats(blob)})
        native_rows = []
        for name in native:
            blob = z.read(name)
            native_rows.append({"name": name, "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest()})
    decompile = {"tool": JADX, "status": "UNVERIFIED"}
    if os.path.isfile(JADX):
        dest = "/tmp/phase4-jadx"
        os.makedirs(dest, exist_ok=True)
        proc = subprocess.run(
            [JADX, "--no-res", "-d", dest, apk],
            check=False, capture_output=True, text=True, timeout=600,
        )
        log = (proc.stdout + "\n" + proc.stderr).splitlines()
        errors = [line for line in log if "ERROR" in line or "JadxRuntimeException" in line]
        decompile = {
            "tool": "jadx 1.5.1",
            "exit": proc.returncode,
            "errorLines": errors[:80],
            "errorCount": len(errors),
            "outputKept": False,
        }
    report = {
        "apk": apk,
        "apkBytes": os.path.getsize(apk),
        "apkSha256": sha256_file(apk),
        "entryCount": len(names),
        "dexCount": len(dex),
        "dex": dex,
        "packagedNative": native_rows,
        "runtimeAssets": assets,
        "decompiler": decompile,
    }
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
        f.write("\n")
    print(json.dumps({"dex": len(dex), "classes": sum(d["header"].get("classDefs", 0) for d in dex), "jadxErrors": decompile.get("errorCount"), "jadxExit": decompile.get("exit")}))


if __name__ == "__main__":
    main()
