#!/usr/bin/env python3
"""Turn Gradle resolution JSON into CycloneDX and SPDX. Does not resolve or download."""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "artifacts", "phase4")


def load(name: str) -> dict:
    path = os.path.join(OUT, name)
    if not os.path.isfile(path):
        raise SystemExit(f"missing {path}; run phase4-resolve.init.gradle.kts first")
    return json.load(open(path, encoding="utf-8"))


def purl(comp: dict) -> str:
    group = comp["group"]
    name = comp["name"]
    version = comp["version"]
    return f"pkg:maven/{group}/{name}@{version}"


def main() -> None:
    app = load("resolved-app-releaseRuntimeClasspath.json")
    core = load("resolved-core-runtimeClasspath.json")
    seen = {}
    for source, doc in (("releaseRuntimeClasspath", app), ("runtimeClasspath", core)):
        for comp in doc["components"]:
            key = (comp["group"], comp["name"], comp["version"], comp.get("classifier", ""), comp["sha256"])
            row = seen.setdefault(key, {
                "group": comp["group"],
                "name": comp["name"],
                "version": comp["version"],
                "classifier": comp.get("classifier", ""),
                "sha256": comp["sha256"],
                "bytes": comp["bytes"],
                "fileName": comp["fileName"],
                "nativeLibraries": comp.get("nativeLibraries") or [],
                "configurations": [],
            })
            if source not in row["configurations"]:
                row["configurations"].append(source)
    components = sorted(seen.values(), key=lambda r: (r["group"], r["name"], r["version"]))
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    bom_components = []
    for comp in components:
        purl_s = purl(comp)
        bom_components.append({
            "type": "library",
            "bom-ref": purl_s,
            "group": comp["group"],
            "name": comp["name"],
            "version": comp["version"],
            "purl": purl_s,
            "hashes": [{"alg": "SHA-256", "content": comp["sha256"]}],
            "properties": [
                {"name": "mika:bytes", "value": str(comp["bytes"])},
                {"name": "mika:configurations", "value": ",".join(comp["configurations"])},
                {"name": "mika:nativeLibraryCount", "value": str(len(comp["nativeLibraries"]))},
            ],
        })
    cyclonedx = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "timestamp": stamp,
            "component": {
                "type": "application",
                "name": "jp.studioyebisu.mika",
                "version": "0.1.36-jp.1",
            },
            "properties": [
                {"name": "mika:graph", "value": "app releaseRuntimeClasspath plus core runtimeClasspath"},
                {"name": "mika:nativeCoverage", "value": "AAR/JAR .so entries are properties of those components; jniLibs and rootfs are outside this Maven graph"},
            ],
        },
        "components": bom_components,
    }
    spdx_packages = [{
        "SPDXID": "SPDXRef-APPLICATION",
        "name": "jp.studioyebisu.mika",
        "versionInfo": "0.1.36-jp.1",
        "downloadLocation": "NOASSERTION",
        "filesAnalyzed": False,
    }]
    relationships = []
    for i, comp in enumerate(components, start=1):
        sid = f"SPDXRef-Package-{i}"
        spdx_packages.append({
            "SPDXID": sid,
            "name": f"{comp['group']}:{comp['name']}",
            "versionInfo": comp["version"],
            "downloadLocation": "NOASSERTION",
            "filesAnalyzed": False,
            "checksums": [{"algorithm": "SHA256", "checksumValue": comp["sha256"]}],
            "externalRefs": [{
                "referenceCategory": "PACKAGE-MANAGER",
                "referenceType": "purl",
                "referenceLocator": purl(comp),
            }],
        })
        relationships.append({
            "spdxElementId": "SPDXRef-APPLICATION",
            "relatedSpdxElement": sid,
            "relationshipType": "DEPENDS_ON",
        })
    spdx = {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": "mika-release-runtime",
        "documentNamespace": f"https://github.com/Grok-Benkei/Mika-JP/phase4/{stamp}",
        "creationInfo": {
            "created": stamp,
            "creators": ["Tool: tools/security/phase4_sbom.py"],
        },
        "packages": spdx_packages,
        "relationships": relationships,
    }
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "sbom.cdx.json"), "w", encoding="utf-8") as f:
        json.dump(cyclonedx, f, indent=2)
        f.write("\n")
    with open(os.path.join(OUT, "sbom.spdx.json"), "w", encoding="utf-8") as f:
        json.dump(spdx, f, indent=2)
        f.write("\n")
    native = [c for c in components if c["nativeLibraries"]]
    print(json.dumps({
        "components": len(components),
        "withNative": [(c["group"] + ":" + c["name"], len(c["nativeLibraries"])) for c in native],
    }))


if __name__ == "__main__":
    sys.exit(main())
