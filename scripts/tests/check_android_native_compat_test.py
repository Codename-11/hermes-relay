from __future__ import annotations

import importlib.util
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "check-android-native-compat.py"
SPEC = importlib.util.spec_from_file_location("check_android_native_compat", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
native_compat = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = native_compat
SPEC.loader.exec_module(native_compat)


def elf_with_ort_symbol(*, defined: bool, version: str) -> bytes:
    """Build a minimal ELF64 containing a versioned OrtGetApiBase dynsym."""
    symbol_name = b"OrtGetApiBase"
    version_name = version.encode("ascii")
    dependency_name = b"libonnxruntime.so"
    strings = b"\0" + symbol_name + b"\0" + version_name + b"\0" + dependency_name + b"\0"
    symbol_offset = 1
    version_offset = symbol_offset + len(symbol_name) + 1
    dependency_offset = version_offset + len(version_name) + 1

    dynsym = b"\0" * 24 + struct.pack(
        "<IBBHQQ",
        symbol_offset,
        0x12,
        0,
        1 if defined else 0,
        0,
        0,
    )
    versym = struct.pack("<HH", 0, 2)
    if defined:
        version_section_type = 0x6FFFFFFD
        version_data = struct.pack("<HHHHIII", 1, 0, 2, 1, 0, 20, 0)
        version_data += struct.pack("<II", version_offset, 0)
    else:
        version_section_type = 0x6FFFFFFE
        version_data = struct.pack("<HHIII", 1, 1, dependency_offset, 16, 0)
        version_data += struct.pack("<IHHII", 0, 0, 2, version_offset, 0)

    section_blobs = [b"", strings, dynsym, versym, version_data]
    offsets: list[int] = []
    image = bytearray(b"\0" * 64)
    for blob in section_blobs:
        while len(image) % 8:
            image.append(0)
        offsets.append(len(image))
        image.extend(blob)

    while len(image) % 8:
        image.append(0)
    section_header_offset = len(image)
    section_headers = [
        (0, 0, 0, 0, offsets[0], 0, 0, 0, 0, 0),
        (0, 3, 0, 0, offsets[1], len(strings), 0, 0, 1, 0),
        (0, 11, 0, 0, offsets[2], len(dynsym), 1, 0, 8, 24),
        (0, 0x6FFFFFFF, 0, 0, offsets[3], len(versym), 2, 0, 2, 2),
        (0, version_section_type, 0, 0, offsets[4], len(version_data), 1, 0, 4, 0),
    ]
    for header in section_headers:
        image.extend(struct.pack("<IIQQQQIIQQ", *header))

    ident = b"\x7fELF" + bytes((2, 1, 1, 0)) + b"\0" * 8
    header = struct.pack(
        "<16sHHIQQQIHHHHHH",
        ident,
        3,
        183,
        1,
        0,
        0,
        section_header_offset,
        0,
        64,
        0,
        0,
        64,
        len(section_headers),
        0,
    )
    image[:64] = header
    return bytes(image)


def write_artifact(path: Path, *, runtime_version: str, abis: set[str]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for abi in abis:
            prefix = f"lib/{abi}/"
            archive.writestr(
                prefix + native_compat.RUNTIME_LIBRARY,
                elf_with_ort_symbol(defined=True, version=runtime_version),
            )
            for consumer in (
                native_compat.REQUIRED_CONSUMER,
                native_compat.ORT_JAVA_CONSUMER,
            ):
                archive.writestr(
                    prefix + consumer,
                    elf_with_ort_symbol(defined=False, version="VERS_1.27.0"),
                )


class AndroidNativeCompatTest(unittest.TestCase):
    def test_parses_gnu_definition_and_requirement_versions(self) -> None:
        provider = native_compat.read_versioned_symbols(
            elf_with_ort_symbol(defined=True, version="VERS_1.27.0")
        )
        consumer = native_compat.read_versioned_symbols(
            elf_with_ort_symbol(defined=False, version="VERS_1.27.0")
        )

        self.assertEqual(
            [native_compat.VersionedSymbol(defined=True, version="VERS_1.27.0")],
            provider[native_compat.ORT_ENTRY_POINT],
        )
        self.assertEqual(
            [native_compat.VersionedSymbol(defined=False, version="VERS_1.27.0")],
            consumer[native_compat.ORT_ENTRY_POINT],
        )

    def test_accepts_aligned_runtime_and_both_consumers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "aligned.apk"
            write_artifact(
                artifact,
                runtime_version="VERS_1.27.0",
                abis=native_compat.SUPPORTED_ABIS,
            )
            self.assertEqual([], native_compat.check_artifact(artifact))

    def test_rejects_runtime_symbol_version_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "mismatch.apk"
            write_artifact(
                artifact,
                runtime_version="VERS_1.29.0",
                abis=native_compat.SUPPORTED_ABIS,
            )
            failures = native_compat.check_artifact(artifact)
            self.assertTrue(any("requires OrtGetApiBase@VERS_1.27.0" in item for item in failures))
            self.assertTrue(any(native_compat.ORT_JAVA_CONSUMER in item for item in failures))

    def test_rejects_missing_supported_abi(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "missing-abi.apk"
            write_artifact(
                artifact,
                runtime_version="VERS_1.27.0",
                abis={"arm64-v8a"},
            )
            failures = native_compat.check_artifact(artifact)
            self.assertTrue(any("packaged ABI set" in item for item in failures))


if __name__ == "__main__":
    unittest.main()
