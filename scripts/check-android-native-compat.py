#!/usr/bin/env python3
"""Verify that packaged Android JNI consumers match their shared ONNX Runtime.

The APK is the authority for this check. Gradle can resolve multiple AARs that
contain the same native filename, and ``pickFirst`` alone does not prove that
the selected runtime exports the symbol version required by sherpa's JNI.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
import re
import struct
import sys
import zipfile


ORT_ENTRY_POINT = "OrtGetApiBase"
RUNTIME_LIBRARY = "libonnxruntime.so"
REQUIRED_CONSUMER = "libsherpa-onnx-jni.so"
ORT_JAVA_CONSUMER = "libonnxruntime4j_jni.so"
SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
LIBRARY_PATH = re.compile(
    r"^(?:base/)?lib/(?P<abi>[^/]+)/(?P<library>[^/]+\.so)$"
)


@dataclass(frozen=True)
class Section:
    section_type: int
    offset: int
    size: int
    link: int
    entry_size: int


@dataclass(frozen=True)
class VersionedSymbol:
    defined: bool
    version: str | None


def _unpack(fmt: str, data: bytes, offset: int) -> tuple[int, ...]:
    size = struct.calcsize(fmt)
    if offset < 0 or offset + size > len(data):
        raise ValueError("ELF structure extends beyond the file")
    return struct.unpack_from(fmt, data, offset)


def _cstring(data: bytes, offset: int) -> str:
    if offset < 0 or offset >= len(data):
        raise ValueError("ELF string offset is outside its string table")
    end = data.find(b"\0", offset)
    if end < 0:
        raise ValueError("ELF string is not NUL-terminated")
    return data[offset:end].decode("utf-8", errors="replace")


def _elf_layout(data: bytes) -> tuple[str, bool, list[Section]]:
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class = data[4]
    byte_order = data[5]
    if elf_class not in (1, 2) or byte_order not in (1, 2):
        raise ValueError("unsupported ELF class or byte order")
    endian = "<" if byte_order == 1 else ">"
    is_64_bit = elf_class == 2

    if is_64_bit:
        header = _unpack(endian + "HHIQQQIHHHHHH", data, 16)
        section_offset, section_entry_size, section_count = header[5], header[10], header[11]
        section_format = endian + "IIQQQQIIQQ"
    else:
        header = _unpack(endian + "HHIIIIIHHHHHH", data, 16)
        section_offset, section_entry_size, section_count = header[5], header[10], header[11]
        section_format = endian + "IIIIIIIIII"

    minimum_entry_size = struct.calcsize(section_format)
    if section_entry_size < minimum_entry_size:
        raise ValueError("ELF section-header entry is too small")

    sections: list[Section] = []
    for index in range(section_count):
        fields = _unpack(section_format, data, section_offset + index * section_entry_size)
        sections.append(
            Section(
                section_type=fields[1],
                offset=fields[4],
                size=fields[5],
                link=fields[6],
                entry_size=fields[9],
            )
        )
    return endian, is_64_bit, sections


def _section_data(data: bytes, section: Section) -> bytes:
    end = section.offset + section.size
    if section.offset < 0 or end > len(data):
        raise ValueError("ELF section extends beyond the file")
    return data[section.offset:end]


def _version_names(
    data: bytes,
    endian: str,
    sections: list[Section],
) -> dict[int, str]:
    names: dict[int, str] = {}
    for section in sections:
        if section.section_type not in (0x6FFFFFFD, 0x6FFFFFFE):
            continue
        if section.link >= len(sections):
            raise ValueError("ELF version section has an invalid string-table link")
        strings = _section_data(data, sections[section.link])
        cursor = 0
        while cursor < section.size:
            base = section.offset + cursor
            if section.section_type == 0x6FFFFFFD:  # SHT_GNU_verdef
                fields = _unpack(endian + "HHHHIII", data, base)
                version_index, aux_offset, next_offset = fields[2], fields[5], fields[6]
                name_offset, _ = _unpack(endian + "II", data, base + aux_offset)
                names[version_index] = _cstring(strings, name_offset)
            else:  # SHT_GNU_verneed
                fields = _unpack(endian + "HHIII", data, base)
                count, aux_offset, next_offset = fields[1], fields[3], fields[4]
                aux_cursor = base + aux_offset
                for _ in range(count):
                    aux = _unpack(endian + "IHHII", data, aux_cursor)
                    names[aux[2] & 0x7FFF] = _cstring(strings, aux[3])
                    if aux[4] == 0:
                        break
                    aux_cursor += aux[4]
            if next_offset == 0:
                break
            cursor += next_offset
    return names


def read_versioned_symbols(data: bytes) -> dict[str, list[VersionedSymbol]]:
    endian, is_64_bit, sections = _elf_layout(data)
    version_names = _version_names(data, endian, sections)
    symbols: dict[str, list[VersionedSymbol]] = defaultdict(list)

    for dynsym_index, dynsym in enumerate(sections):
        if dynsym.section_type != 11:  # SHT_DYNSYM
            continue
        if dynsym.link >= len(sections):
            raise ValueError("ELF dynamic-symbol table has an invalid string-table link")
        strings = _section_data(data, sections[dynsym.link])
        symbol_format = endian + ("IBBHQQ" if is_64_bit else "IIIBBH")
        symbol_size = dynsym.entry_size or struct.calcsize(symbol_format)
        symbol_count = dynsym.size // symbol_size

        versions: tuple[int, ...] = ()
        for section in sections:
            if section.section_type == 0x6FFFFFFF and section.link == dynsym_index:
                raw_versions = _section_data(data, section)
                versions = struct.unpack(endian + f"{len(raw_versions) // 2}H", raw_versions)
                break

        for index in range(symbol_count):
            fields = _unpack(symbol_format, data, dynsym.offset + index * symbol_size)
            name_offset = fields[0]
            section_index = fields[3] if is_64_bit else fields[5]
            name = _cstring(strings, name_offset)
            if not name:
                continue
            version_index = (versions[index] & 0x7FFF) if index < len(versions) else 0
            symbols[name].append(
                VersionedSymbol(
                    defined=section_index != 0,
                    version=version_names.get(version_index),
                )
            )
    return symbols


def _single_symbol_version(
    blob: bytes,
    *,
    defined: bool,
    context: str,
) -> str:
    matches = [
        symbol.version
        for symbol in read_versioned_symbols(blob).get(ORT_ENTRY_POINT, [])
        if symbol.defined == defined
    ]
    if not matches:
        role = "export" if defined else "requirement"
        raise ValueError(f"{context} has no {ORT_ENTRY_POINT} {role}")
    versions = set(matches)
    if None in versions:
        raise ValueError(f"{context} uses an unversioned {ORT_ENTRY_POINT} symbol")
    if len(versions) != 1:
        raise ValueError(f"{context} has ambiguous {ORT_ENTRY_POINT} versions: {sorted(versions)}")
    return next(iter(versions))  # type: ignore[return-value]


def check_artifact(path: Path, expected_abis: set[str] | None = None) -> list[str]:
    failures: list[str] = []
    with zipfile.ZipFile(path) as archive:
        libraries: dict[str, dict[str, list[zipfile.ZipInfo]]] = defaultdict(
            lambda: defaultdict(list)
        )
        for info in archive.infolist():
            match = LIBRARY_PATH.fullmatch(info.filename)
            if match:
                libraries[match.group("abi")][match.group("library")].append(info)

        if not libraries:
            return [f"{path}: no packaged native libraries found"]

        expected = SUPPORTED_ABIS if expected_abis is None else expected_abis
        actual = set(libraries)
        if actual != expected:
            failures.append(
                f"{path.name}: packaged ABI set is {sorted(actual)}, expected {sorted(expected)}"
            )

        for abi, by_name in sorted(libraries.items()):
            for required in (RUNTIME_LIBRARY, REQUIRED_CONSUMER, ORT_JAVA_CONSUMER):
                count = len(by_name.get(required, []))
                if count != 1:
                    failures.append(
                        f"{path.name} [{abi}]: expected exactly one {required}, found {count}"
                    )
            if failures and (
                len(by_name.get(RUNTIME_LIBRARY, [])) != 1
                or len(by_name.get(REQUIRED_CONSUMER, [])) != 1
                or len(by_name.get(ORT_JAVA_CONSUMER, [])) != 1
            ):
                continue

            try:
                runtime_version = _single_symbol_version(
                    archive.read(by_name[RUNTIME_LIBRARY][0]),
                    defined=True,
                    context=f"{path.name} [{abi}] {RUNTIME_LIBRARY}",
                )
                for consumer in (REQUIRED_CONSUMER, ORT_JAVA_CONSUMER):
                    consumer_version = _single_symbol_version(
                        archive.read(by_name[consumer][0]),
                        defined=False,
                        context=f"{path.name} [{abi}] {consumer}",
                    )
                    if runtime_version != consumer_version:
                        failures.append(
                            f"{path.name} [{abi}]: {consumer} requires "
                            f"{ORT_ENTRY_POINT}@{consumer_version}, but {RUNTIME_LIBRARY} exports "
                            f"{ORT_ENTRY_POINT}@{runtime_version}"
                        )
            except ValueError as error:
                failures.append(str(error))
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "artifact",
        nargs="+",
        type=Path,
        help="APK or AAB artifact to inspect.",
    )
    parser.add_argument(
        "--expected-abi",
        action="append",
        default=[],
        help=(
            "Expected packaged ABI; repeat to override the standard four-ABI set "
            "for a deliberate -Phermes.devAbi build."
        ),
    )
    args = parser.parse_args()

    failures: list[str] = []
    for artifact in args.artifact:
        if not artifact.is_file():
            failures.append(f"artifact does not exist: {artifact}")
            continue
        try:
            expected_abis = set(args.expected_abi) or SUPPORTED_ABIS
            failures.extend(check_artifact(artifact, expected_abis))
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            failures.append(f"{artifact}: {error}")

    if failures:
        print("Android native compatibility check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1

    print(
        "Android native compatibility check passed "
        f"({len(args.artifact)} artifact(s), {ORT_ENTRY_POINT} symbol versions aligned)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
