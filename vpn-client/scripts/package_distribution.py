#!/usr/bin/env python3
"""Create an APK + external GPL/third-party notice + matching source share ZIP."""

from __future__ import annotations

import argparse
import hashlib
import shutil
import tempfile
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]

EXCLUDED_DIRS = {".git", ".gradle", "build", "distribution", "native-source", ".idea"}
EXCLUDED_SUFFIXES = {".apk", ".aab", ".aar", ".ap_", ".class", ".dex", ".jks", ".keystore"}
EXCLUDED_NAMES = {"local.properties", "keystore.properties", "secrets.properties", ".env"}


def include_source(path: Path) -> bool:
    rel = path.relative_to(PROJECT)
    if any(part in EXCLUDED_DIRS for part in rel.parts):
        return False
    if path.name in EXCLUDED_NAMES or path.suffix.lower() in EXCLUDED_SUFFIXES:
        return False
    if path.name.endswith(".zip"):
        return False
    return path.is_file()


def zip_tree(source: Path, archive: Path) -> None:
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for path in sorted(source.rglob("*")):
            if include_source(path):
                out.write(path, path.relative_to(source).as_posix())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True, help="Installable APK to include")
    parser.add_argument("--out", type=Path, required=True, help="Output share-bundle ZIP")
    args = parser.parse_args()

    apk = args.apk if args.apk.is_absolute() else (PROJECT / args.apk).resolve()
    output = args.out if args.out.is_absolute() else (PROJECT / args.out).resolve()
    if not apk.is_file():
        parser.error(f"APK does not exist: {apk}")
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="gatelink-vpn-bundle-") as tmp:
        tmp_path = Path(tmp)
        bundle = tmp_path / "GateLinkVPN"
        bundle.mkdir()
        shutil.copy2(apk, bundle / "GateLinkVPN.apk")
        shutil.copy2(PROJECT / "LICENSE", bundle / "LICENSE.txt")
        for name in ("INSTALL.txt", "SOURCE_INFO.txt", "THIRD_PARTY_NOTICES.txt"):
            shutil.copy2(PROJECT / name, bundle / name)

        licenses_dir = bundle / "LICENSES"
        licenses_dir.mkdir()
        for notice in sorted((PROJECT / "licenses").glob("*.txt")):
            shutil.copy2(notice, licenses_dir / notice.name)

        zip_tree(PROJECT, bundle / "SOURCE_CODE.zip")
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in sorted(bundle.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(bundle).as_posix())

    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f"Bundle: {output}")
    print(f"Size: {output.stat().st_size} bytes")
    print(f"SHA-256: {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
