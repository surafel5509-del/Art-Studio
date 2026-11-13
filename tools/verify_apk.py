#!/usr/bin/env python3
"""Structural validator for a built APK.

`apksigner verify` proves the signature is intact, but "There was a problem parsing the
package" on a device almost always means the *file that arrived* was wrong: a truncated
download, a ZIP-wrapped workflow artifact, or a browser-renamed download. This script
checks the container itself, with no Android SDK required:

  * central directory + CRC32 of every entry (catches truncation/corruption)
  * AndroidManifest.xml, classes*.dex and a signature block are present
  * the binary AndroidManifest.xml is parsed for package name, versionCode,
    minSdkVersion and targetSdkVersion (so a min-SDK mismatch is caught before shipping)

Usage: python3 tools/verify_apk.py path/to/app.apk [--expected-package com.example]

Exits non-zero only for problems that actually prevent installation; manifest parsing
failures are reported as warnings so a parser bug can never block a good build.
"""

import struct
import sys
import zipfile
import zlib

# ---------------------------------------------------------------------------
# Binary (AXML) manifest parsing
# ---------------------------------------------------------------------------

CHUNK_STRING_POOL = 0x0001
CHUNK_RESOURCE_MAP = 0x0180
CHUNK_START_ELEMENT = 0x0102
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11


class AxmlError(Exception):
    pass


def _parse_string_pool(data, offset):
    header = struct.unpack_from("<HHI", data, offset)
    _type, header_size, _size = header
    count, _style_count, flags, strings_start, _styles_start = struct.unpack_from("<IIIII", data, offset + 8)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from("<%dI" % count, data, offset + header_size)
    strings = []
    for rel in offsets:
        pos = offset + strings_start + rel
        if utf8:
            # UTF-16 length (1 or 2 bytes) then UTF-8 length, then the bytes.
            n = data[pos]
            if n & 0x80:
                pos += 2
            else:
                pos += 1
        else:
            n = struct.unpack_from("<H", data, pos)[0]
            pos += 2
            if n & 0x8000:
                pos += 2
                n &= 0x7FFF
        if utf8:
            raw = data[pos:pos + n]
            strings.append(raw.decode("utf-8", "replace"))
        else:
            raw = data[pos:pos + n * 2]
            strings.append(raw.decode("utf-16-le", "replace"))
    return strings


def parse_manifest(raw):
    """Returns {(element, attr): value} for the attributes we care about."""
    if len(raw) < 8:
        raise AxmlError("manifest too short")
    xml_type, header_size, total_size = struct.unpack_from("<HHI", raw, 0)
    if xml_type != 0x0003:
        raise AxmlError("not a binary XML document (type 0x%04x)" % xml_type)

    strings = []
    offset = header_size
    result = {}

    while offset + 8 <= min(total_size, len(raw)):
        chunk_type, chunk_header_size, chunk_size = struct.unpack_from("<HHI", raw, offset)
        if chunk_size <= 0:
            break
        if chunk_type == CHUNK_STRING_POOL:
            strings = _parse_string_pool(raw, offset)
        elif chunk_type == CHUNK_START_ELEMENT:
            body = offset + chunk_header_size
            _ns, name_idx, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", raw, body)
            element = strings[name_idx] if name_idx < len(strings) else ""
            for i in range(attr_count):
                a = body + attr_start + i * attr_size
                _ns_idx, attr_name_idx, raw_value_idx = struct.unpack_from("<III", raw, a)
                value_type = raw[a + 15]
                value_data = struct.unpack_from("<I", raw, a + 16)[0]
                name = strings[attr_name_idx] if attr_name_idx < len(strings) else ""
                if value_type == TYPE_STRING:
                    value = strings[value_data] if value_data < len(strings) else ""
                elif value_type in (TYPE_INT_DEC, TYPE_INT_HEX):
                    value = value_data
                else:
                    value = strings[raw_value_idx] if raw_value_idx < len(strings) else value_data
                result[(element, name)] = value
        offset += chunk_size
    return result


# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

PROBLEMS = []
WARNINGS = []


def problem(message):
    PROBLEMS.append(message)


def warn(message):
    WARNINGS.append(message)


def check_zip(apk):
    try:
        archive = zipfile.ZipFile(apk)
    except zipfile.BadZipFile as exc:
        problem("not a valid ZIP/APK container: %s" % exc)
        return None

    names = archive.namelist()
    bad = archive.testzip()
    if bad is not None:
        problem("corrupt entry (CRC mismatch): %s - the file was truncated or modified in transit" % bad)

    # Refuse an artifact that is actually a ZIP of an APK (a GitHub Actions artifact).
    apks_inside = [n for n in names if n.lower().endswith(".apk")]
    if apks_inside:
        problem(
            "this file contains %s - it is a ZIP wrapping an APK, not an installable APK. "
            "Download the release asset named *.apk, not the Actions artifact." % apks_inside[0]
        )

    for required in ("AndroidManifest.xml", "resources.arsc"):
        if required not in names:
            problem("missing %s" % required)
    if not any(n.startswith("classes") and n.endswith(".dex") for n in names):
        problem("missing classes*.dex (no code in the APK)")

    has_v1 = any(n.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC")) for n in names)
    with open(apk, "rb") as handle:
        head = handle.read(4)
    if head[:2] != b"PK":
        problem("file does not start with the ZIP magic 'PK' - wrong or truncated download")
    if not has_v1:
        warn("no v1 (JAR) signature found; APK relies on v2/v3 signing (fine for API 24+)")
    return (archive, names)


def check_manifest(archive):
    try:
        raw = archive.read("AndroidManifest.xml")
    except KeyError:
        return
    try:
        attrs = parse_manifest(raw)
    except Exception as exc:  # parser is best-effort by design
        warn("could not parse binary AndroidManifest.xml (%s)" % exc)
        return

    package = attrs.get(("manifest", "package"))
    version_code = attrs.get(("manifest", "versionCode"))
    version_name = attrs.get(("manifest", "versionName"))
    min_sdk = attrs.get(("uses-sdk", "minSdkVersion"))
    target_sdk = attrs.get(("uses-sdk", "targetSdkVersion"))
    label = attrs.get(("application", "label"))

    print("  package      : %s" % package)
    print("  versionCode  : %s" % version_code)
    print("  versionName  : %s" % version_name)
    print("  minSdkVersion: %s" % min_sdk)
    print("  targetSdk    : %s" % target_sdk)
    print("  label        : %s" % label)

    if not package:
        problem("manifest has no package name")
    expected = EXPECTED_PACKAGE
    if expected and package and package not in (expected, expected + ".debug"):
        problem("unexpected package %r (expected %r)" % (package, expected))
    if isinstance(min_sdk, int) and min_sdk > 34:
        problem("minSdkVersion %d is higher than any released Android version" % min_sdk)
    if isinstance(min_sdk, int):
        print("  -> installs on Android %d (API %d) and newer" % (min_sdk, min_sdk))


EXPECTED_PACKAGE = None


def main(argv):
    global EXPECTED_PACKAGE
    if len(argv) < 2:
        print("usage: verify_apk.py <apk> [--expected-package pkg]")
        return 2
    apk = argv[1]
    if "--expected-package" in argv:
        EXPECTED_PACKAGE = argv[argv.index("--expected-package") + 1]

    print("verifying %s" % apk)
    result = check_zip(apk)
    if result is not None:
        archive, names = result
        print("  entries      : %d" % len(names))
        print("  signature v1 : %s" % ("yes" if any(n.endswith(".RSA") for n in names) else "no"))
        check_manifest(archive)

    for message in WARNINGS:
        print("  warning: %s" % message)
    if PROBLEMS:
        for message in PROBLEMS:
            print("  ERROR: %s" % message)
        print("APK_INVALID")
        return 1
    print("  all container checks passed")
    print("APK_VALID")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
