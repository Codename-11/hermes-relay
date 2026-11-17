#!/usr/bin/env python3
"""Build a credential-free Windows connector template for the private portal.

Only the fixed installer allowlist, the supplied binary and its manifest enter
this ZIP. Per-download setup.json is added later by the authenticated portal.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
from typing import BinaryIO
import zipfile

ROOT = Path(__file__).resolve().parents[1]
INSTALLER_DIR = ROOT / "desktop" / "connector"
INSTALLER_FILES = ("Install.cmd", "install.ps1", "Status.cmd", "Stop.cmd")
_SOURCE_SHA = re.compile(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})\Z")
_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?\Z")
_CHUNK_SIZE = 1024 * 1024


def archive_entry(name: str) -> zipfile.ZipInfo:
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.create_system = 3
    entry.external_attr = (stat.S_IFREG | 0o644) << 16
    entry.compress_type = zipfile.ZIP_DEFLATED
    return entry


def installer_bytes(name: str) -> bytes:
    # Git checkout line endings vary by platform. Windows PowerShell 5.1 needs
    # a BOM to read the Chinese strings consistently; CMD files use CRLF too.
    text = (INSTALLER_DIR / name).read_text(encoding="utf-8-sig")
    normalized = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")
    return (b"\xef\xbb\xbf" if name.endswith(".ps1") else b"") + normalized.encode("utf-8")


def copy_binary(source: BinaryIO, archive: zipfile.ZipFile) -> str:
    digest = hashlib.sha256()
    with archive.open(archive_entry("hermes-relay.exe"), "w", force_zip64=True) as target:
        while chunk := source.read(_CHUNK_SIZE):
            digest.update(chunk)
            target.write(chunk)
    return digest.hexdigest()


def package_template(binary: Path, output: Path, source_sha: str, version: str) -> dict[str, str]:
    if not _SOURCE_SHA.fullmatch(source_sha):
        raise ValueError("source-sha must be a full 40- or 64-character Git commit SHA")
    if not _VERSION.fullmatch(version):
        raise ValueError("version must be a semantic version")
    if output.exists() or output.is_symlink():
        raise FileExistsError(f"output already exists: {output}")
    # Load the complete fixed allowlist before creating output. No directory
    # traversal, optional setup file or surrounding staging files are copied.
    installers = {name: installer_bytes(name) for name in INSTALLER_FILES}
    temporary: Path | None = None
    try:
        with binary.open("rb") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size == 0:
                raise ValueError("binary must be a nonempty regular file")
            with tempfile.NamedTemporaryFile(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent, delete=False) as stream:
                temporary = Path(stream.name)
                with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                    for name, payload in installers.items():
                        archive.writestr(archive_entry(name), payload)
                    binary_sha = copy_binary(source, archive)
                    manifest = {"sha256": binary_sha, "source_sha": source_sha.lower(), "version": version}
                    archive.writestr(archive_entry("manifest.json"), json.dumps(manifest, indent=2) + "\n")
                stream.flush()
                os.fsync(stream.fileno())
        # Publish atomically without replacing anything, including a concurrent
        # writer or dangling symlink. The temporary file shares the output's
        # directory/filesystem; filesystems without hard links fail closed.
        os.link(temporary, output)
        return manifest
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path, help="compiled Windows connector CLI")
    parser.add_argument("--output", required=True, type=Path, help="new template ZIP; existing files are never overwritten")
    parser.add_argument("--source-sha", required=True, help="full Git SHA used to build the binary and installer")
    parser.add_argument("--version", help="desktop semantic version; defaults to desktop/package.json")
    args = parser.parse_args(argv)
    try:
        version = args.version
        if version is None:
            version = json.loads((ROOT / "desktop" / "package.json").read_text(encoding="utf-8"))["version"]
        if not isinstance(version, str):
            raise ValueError("desktop package version must be a string")
        manifest = package_template(args.binary, args.output, args.source_sha, version)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"Connector template packaging failed: {exc}\n")
    print(f"Created {args.output} (binary SHA-256 {manifest['sha256']}, source {manifest['source_sha']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
