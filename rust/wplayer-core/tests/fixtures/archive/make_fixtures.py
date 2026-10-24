#!/usr/bin/env python3
"""Regenerates the archive fixtures for src/archive tests: python3 make_fixtures.py

The zip and 7z files are built here (7z needs the `7z` tool from p7zip or 7-Zip).
Each page holds "page <file name>\\n" a hundred times, which the tests rebuild.

The RAR files come from libarchive's test suite (BSD-2-Clause, like libarchive),
since RAR archives can only be written with the proprietary rar tool:
  rar4-unicode.rar   test_read_format_rar_unicode.rar
  rar5-solid.rar     test_read_format_rar5_multiple_files_solid.rar
  rar4-encrypted.rar test_read_format_rar4_encrypted.rar
"""

import binascii
import pathlib
import subprocess
import tempfile
import urllib.request
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
LIBARCHIVE_TESTS = "https://raw.githubusercontent.com/libarchive/libarchive/v3.8.10/libarchive/test/"

# Stored out of page order; the listing has to sort them.
PAGES = ["책/2.jpg", "책/10.JPG", "책/1.png", "책/cover.webp"]
NOT_PAGES = ["책/notes.txt", "책/.hidden.jpg", "__MACOSX/책/._2.jpg"]


def page(name):
    return (f"page {name.rsplit('/', 1)[-1]}\n" * 100).encode()


class LegacyName(zipfile.ZipInfo):
    """A zip entry whose name is raw bytes without the UTF-8 flag, like old Windows tools write."""

    def __init__(self, raw_name):
        super().__init__(raw_name.decode("latin-1"))
        self.raw_name = raw_name
        self.compress_type = zipfile.ZIP_DEFLATED

    def _encodeFilenameFlags(self):
        return self.raw_name, self.flag_bits & ~0x800


def write_tree(root, names):
    for name in names:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(page(name))


def main():
    with zipfile.ZipFile(HERE / "pages.zip", "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(zipfile.ZipInfo("책/"), b"")
        for name in PAGES + NOT_PAGES:
            z.writestr(name, page(name))

    with zipfile.ZipFile(HERE / "cp949.zip", "w") as z:
        for name in ["만화/02.jpg", "만화/01.jpg"]:
            z.writestr(LegacyName(name.encode("cp949")), page(name))

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        write_tree(root, PAGES + NOT_PAGES)
        for name in ["pages.7z", "encrypted.zip"]:
            (HERE / name).unlink(missing_ok=True)
        subprocess.run(["7z", "a", "-t7z", "-ms=on", HERE / "pages.7z", "책", "__MACOSX"], cwd=root, check=True,
                       stdout=subprocess.DEVNULL)
        subprocess.run(["7z", "a", "-tzip", "-pwplayer", "-mem=ZipCrypto", HERE / "encrypted.zip", "책/1.png"],
                       cwd=root, check=True, stdout=subprocess.DEVNULL)

    for name, source in [("rar4-unicode.rar", "test_read_format_rar_unicode.rar"),
                         ("rar5-solid.rar", "test_read_format_rar5_multiple_files_solid.rar"),
                         ("rar4-encrypted.rar", "test_read_format_rar4_encrypted.rar")]:
        with urllib.request.urlopen(LIBARCHIVE_TESTS + source + ".uu") as response:
            lines = response.read().decode().splitlines()
        body = lines[1:lines.index("end")]
        (HERE / name).write_bytes(b"".join(binascii.a2b_uu(line) for line in body if line.strip()))


if __name__ == "__main__":
    main()
