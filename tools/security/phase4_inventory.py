#!/usr/bin/env python3
"""Read-only Phase 4 inventory of the bundled rootfs and native launchers.

Does not execute archive members. Private-key bytes and shadow password fields
are not copied into the output.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tarfile
from collections import Counter, defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ROOTFS = os.path.join(ROOT, "app", "src", "main", "assets", "runtime", "rootfs.tar.xz")
BUNDLED = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "wangzan101", "mika", "cap", "sandbox", "BundledRuntime.kt"
)
JNILIBS = os.path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a")
OUT = os.path.join(ROOT, "artifacts", "phase4")

URL_RE = re.compile(rb"https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]{1,200}")
SHEBANG_RE = re.compile(rb"^#!([^\n\x00]{1,120})")
HOOK_MARKERS = (
    "/etc/init.d/",
    "/etc/inittab",
    "/etc/profile",
    "/etc/profile.d/",
    "/etc/rc",
    "/etc/apk/commit_hooks/",
    ".post-install",
    ".pre-install",
    ".pre-upgrade",
    ".post-upgrade",
    ".trigger",
    "/etc/apk/repositories",
)
TEXT_CAP = 256 * 1024
PRIVATE_MARK = b"-----BEGIN "
PRIVATE_KINDS = (b"PRIVATE KEY", b"OPENSSH PRIVATE KEY", b"RSA PRIVATE KEY", b"EC PRIVATE KEY")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def kind_of(member: tarfile.TarInfo) -> str:
    if member.isdir():
        return "dir"
    if member.issym():
        return "symlink"
    if member.islnk():
        return "hardlink"
    if member.isfifo():
        return "fifo"
    if member.ischr():
        return "char"
    if member.isblk():
        return "block"
    if member.isreg():
        return "file"
    return "other"


def bundled_constants() -> dict:
    text = open(BUNDLED, encoding="utf-8").read()
    out = {}
    for name in ("VERSION", "SHA256", "BYTES", "FILE_BYTES", "ENTRIES"):
        m = re.search(rf'const val {name} = "?([^"\n]+)"?', text)
        if not m:
            raise SystemExit(f"missing BundledRuntime.{name}")
        raw = m.group(1).removesuffix("L")
        out[name] = int(raw) if raw.isdigit() else raw
    return out


def elf_notes(blob: bytes, path: str) -> dict | None:
    if not blob.startswith(b"\x7fELF"):
        return None
    note = {"elf": True, "class": "64" if blob[4] == 2 else "32", "machine": int.from_bytes(blob[18:20], "little")}
    # Static header only. readelf does not execute the file.
    try:
        proc = subprocess.run(
            ["readelf", "-d", "-W", path],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired):
        note["dynamic"] = "UNVERIFIED"
        return note
    if proc.returncode != 0:
        note["dynamic"] = "none"
        return note
    needed, rpath = [], []
    for line in proc.stdout.splitlines():
        if "(NEEDED)" in line:
            needed.append(line.split("[", 1)[-1].rstrip("]"))
        elif "(RPATH)" in line or "(RUNPATH)" in line:
            rpath.append(line.split("[", 1)[-1].rstrip("]"))
    note["needed"] = needed
    note["rpath"] = rpath
    return note


def scan_text(path: str, data: bytes) -> dict:
    sample = data[:TEXT_CAP]
    info = {"text_truncated": len(data) > TEXT_CAP}
    if any(kind in sample[:4096] for kind in PRIVATE_KINDS) or (
        PRIVATE_MARK in sample[:4096] and b"PRIVATE" in sample[:4096]
    ):
        info["private_key_marker"] = True
    if path.rstrip("/") in ("etc/shadow", "etc/gshadow", "./etc/shadow", "./etc/gshadow"):
        fields = []
        locked = True
        for line in sample.splitlines():
            parts = line.split(b":")
            if len(parts) < 2:
                continue
            pw = parts[1]
            fields.append(parts[0].decode("utf-8", "replace"))
            if pw not in (b"*", b"!", b"!!", b""):
                locked = False
        info["shadow_accounts"] = fields
        info["shadow_all_locked"] = locked
        return info
    if b"\x00" in sample[:8192]:
        info["binary"] = True
        return info
    m = SHEBANG_RE.match(sample)
    if m:
        info["shebang"] = m.group(1).decode("utf-8", "replace")
    hosts = []
    for match in URL_RE.findall(sample):
        try:
            url = match.decode("ascii")
        except UnicodeDecodeError:
            continue
        host = url.split("://", 1)[-1].split("/", 1)[0].split(":", 1)[0].lower()
        if host:
            hosts.append((host, url[:180]))
    info["urls"] = hosts
    return info


def inventory_rootfs(path: str = ROOTFS, expected: dict | None = None) -> dict:
    expected = expected or bundled_constants()
    archive_sha = sha256_file(path)
    archive_size = os.path.getsize(path)
    entries = []
    file_bytes = 0
    hosts = defaultdict(list)
    suspicious = []
    hooks = []
    executables = []
    shebangs = []
    private_markers = []
    elf_count = 0
    # Materialize ELF files only long enough for readelf, under /tmp, then delete.
    tmp_dir = "/tmp/phase4-elf"
    os.makedirs(tmp_dir, exist_ok=True)

    with tarfile.open(path, "r:xz") as tar:
        for member in tar:
            mode = stat.S_IMODE(member.mode)
            item = {
                "path": member.name,
                "type": kind_of(member),
                "mode": oct(mode),
                "uid": member.uid,
                "gid": member.gid,
                "size": member.size,
                "link": member.linkname or None,
            }
            data = b""
            if member.isreg():
                extracted = tar.extractfile(member)
                data = extracted.read() if extracted is not None else b""
                item["sha256"] = sha256_bytes(data)
                file_bytes += len(data)
                if len(data) != member.size:
                    item["size_mismatch"] = len(data)
                    suspicious.append({"path": member.name, "reason": "size_mismatch"})
            entries.append(item)
            norm = member.name[2:] if member.name.startswith("./") else member.name
            world_writable = bool(mode & stat.S_IWOTH) and kind_of(member) in ("file", "dir")
            setuid = bool(mode & stat.S_ISUID)
            setgid = bool(mode & stat.S_ISGID)
            if setuid or setgid or world_writable:
                suspicious.append({
                    "path": member.name,
                    "reason": ",".join(
                        bit for bit, on in (("setuid", setuid), ("setgid", setgid), ("world_writable", world_writable)) if on
                    ),
                    "mode": oct(mode),
                })
            if any(mark in "/" + norm for mark in HOOK_MARKERS) or norm.endswith((".post-install", ".trigger")):
                hooks.append(member.name)
            if member.isreg() and (mode & 0o111 or data.startswith(b"\x7fELF") or data.startswith(b"#!")):
                executables.append(member.name)
            if member.isreg() and data:
                text = scan_text(norm, data)
                if text.get("shebang"):
                    shebangs.append({"path": member.name, "shebang": text["shebang"]})
                if text.get("private_key_marker"):
                    private_markers.append(member.name)
                    suspicious.append({"path": member.name, "reason": "private_key_marker"})
                if "shadow_accounts" in text:
                    item["shadow_accounts"] = text["shadow_accounts"]
                    item["shadow_all_locked"] = text["shadow_all_locked"]
                for host, url in text.get("urls", []):
                    bucket = hosts[host]
                    if len(bucket) < 3:
                        bucket.append({"path": member.name, "url": url})
                if data.startswith(b"\x7fELF"):
                    elf_count += 1
                    safe = hashlib.sha256(member.name.encode()).hexdigest()[:16]
                    tmp = os.path.join(tmp_dir, safe)
                    with open(tmp, "wb") as handle:
                        handle.write(data)
                    os.chmod(tmp, 0o400)
                    note = elf_notes(data, tmp)
                    os.remove(tmp)
                    if note:
                        item["elf"] = {k: note[k] for k in ("class", "machine", "needed", "rpath", "dynamic") if k in note and note[k]}

    host_summary = [
        {"host": host, "examples": hosts[host], "example_cap": 3}
        for host in sorted(hosts)
    ]
    return {
        "archive": path,
        "archive_sha256": archive_sha,
        "archive_bytes": archive_size,
        "bundled": expected,
        "archive_sha_matches_bundled": archive_sha == expected["SHA256"],
        "archive_bytes_match_bundled": archive_size == expected["BYTES"],
        "entry_count": len(entries),
        "entry_count_matches_bundled": len(entries) == expected["ENTRIES"],
        "regular_file_bytes": file_bytes,
        "regular_file_bytes_match_bundled": file_bytes == expected["FILE_BYTES"],
        "elf_count": elf_count,
        "executable_paths": executables,
        "shebangs": shebangs,
        "startup_hook_paths": hooks,
        "private_key_marker_paths": private_markers,
        "suspicious": suspicious,
        "url_hosts": host_summary,
        "entries": entries,
        "content_behavior": "UNVERIFIED: members were hashed and scanned as bytes. No member was executed.",
    }


def inventory_native() -> dict:
    libs = []
    for name in sorted(os.listdir(JNILIBS)):
        path = os.path.join(JNILIBS, name)
        if not os.path.isfile(path):
            continue
        data_sha = sha256_file(path)
        desc = subprocess.run(["file", "-b", path], check=False, capture_output=True, text=True).stdout.strip()
        read = subprocess.run(["readelf", "-d", "-W", path], check=False, capture_output=True, text=True)
        needed = []
        for line in read.stdout.splitlines():
            if "(NEEDED)" in line:
                needed.append(line.split("[", 1)[-1].rstrip("]"))
        libs.append({
            "name": name,
            "bytes": os.path.getsize(path),
            "sha256": data_sha,
            "file": desc,
            "needed": needed,
        })
    return {
        "directory": "app/src/main/jniLibs/arm64-v8a",
        "libs": libs,
        "rebuild": "UNVERIFIED: NDK 28.2.13676358 was not present, so build-native.sh --check was not run. Source lock hashes are recorded separately and were not re-downloaded in this step.",
    }


def self_test() -> None:
    import io
    import tempfile
    secret = b"SUPER-SECRET-KEY-MATERIAL-DO-NOT-LEAK"
    payload = b"-----BEGIN PRIVATE KEY-----\n" + secret + b"\n-----END PRIVATE KEY-----\n"
    tmp = tempfile.NamedTemporaryFile(suffix=".tar.xz", delete=False)
    tmp.close()
    try:
        with tarfile.open(tmp.name, "w:xz") as tar:
            data = b"hello"
            info = tarfile.TarInfo("bin/tool")
            info.size = len(data)
            info.mode = 0o4755
            tar.addfile(info, io.BytesIO(data))
            key = tarfile.TarInfo("etc/secret.pem")
            key.size = len(payload)
            key.mode = 0o600
            tar.addfile(key, io.BytesIO(payload))
            d = tarfile.TarInfo("tmp")
            d.type = tarfile.DIRTYPE
            d.mode = 0o1777
            tar.addfile(d)
        got = inventory_rootfs(tmp.name, expected={
            "VERSION": "test", "SHA256": sha256_file(tmp.name), "BYTES": os.path.getsize(tmp.name),
            "FILE_BYTES": len(data) + len(payload), "ENTRIES": 3,
        })
        blob = json.dumps(got)
        if secret.decode() in blob:
            raise SystemExit("private key material was written into the inventory")
        reasons = {row["reason"] for row in got["suspicious"]}
        if "setuid" not in reasons or "world_writable" not in reasons:
            raise SystemExit(f"missing flags: {reasons}")
        if got["private_key_marker_paths"] != ["etc/secret.pem"]:
            raise SystemExit(f"private marker paths: {got['private_key_marker_paths']}")
        if not got["archive_sha_matches_bundled"] or got["entry_count"] != 3:
            raise SystemExit("self-test counts failed")
    finally:
        os.remove(tmp.name)
    print("self-test ok")


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        self_test()
        return
    os.makedirs(OUT, exist_ok=True)
    native = inventory_native()
    rootfs = inventory_rootfs()
    # Full entry list is the machine-readable inventory. Summary omits per-entry bodies.
    summary = {k: v for k, v in rootfs.items() if k != "entries"}
    summary["executable_count"] = len(rootfs["executable_paths"])
    with open(os.path.join(OUT, "rootfs-inventory.json"), "w", encoding="utf-8") as f:
        json.dump(rootfs, f, indent=1, sort_keys=True)
        f.write("\n")
    with open(os.path.join(OUT, "rootfs-summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, sort_keys=True)
        f.write("\n")
    with open(os.path.join(OUT, "native-libs.json"), "w", encoding="utf-8") as f:
        json.dump(native, f, indent=2, sort_keys=True)
        f.write("\n")
    print(json.dumps({
        "entries": rootfs["entry_count"],
        "sha_ok": rootfs["archive_sha_matches_bundled"],
        "bytes_ok": rootfs["archive_bytes_match_bundled"],
        "file_bytes_ok": rootfs["regular_file_bytes_match_bundled"],
        "elf": rootfs["elf_count"],
        "suspicious": len(rootfs["suspicious"]),
        "hosts": len(rootfs["url_hosts"]),
        "hooks": len(rootfs["startup_hook_paths"]),
        "native": [lib["name"] for lib in native["libs"]],
    }, sort_keys=True))


if __name__ == "__main__":
    sys.exit(main())
