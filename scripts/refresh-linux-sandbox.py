#!/usr/bin/env python3
"""Refresh the bundled Alpine rootfs and the Android proot host binaries.

The app does not download these at runtime. Re-run this script when bumping
Alpine or the Termux proot packages, then commit the updated assets, jniLibs,
and sandbox/upstream.lock.json.

Host binaries come from Termux's package repository and stay separate
executables. Two string patches make them load inside an ordinary app:
DT_NEEDED libtalloc.so.2 becomes libtalloc.so, and the Termux DT_RUNPATH
becomes $ORIGIN. Nothing else in the binaries is changed.
"""

from __future__ import annotations

import hashlib
import io
import json
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "linux-sandbox"
JNILIBS = ROOT / "app" / "src" / "main" / "jniLibs"
LOCK = ROOT / "sandbox" / "upstream.lock.json"

ALPINE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases"
TERMUX = "https://packages.termux.dev/apt/termux-main"
USER_AGENT = "IrioSandboxRefresh/1"

ABIS = {
    "arm64-v8a": {
        "alpine_arch": "aarch64",
        "termux_arch": "aarch64",
        "linker": "/system/bin/linker64",
    },
    "armeabi-v7a": {
        "alpine_arch": "armv7",
        "termux_arch": "arm",
        "linker": "/system/bin/linker",
    },
}

HOST_PACKAGES = ("proot", "libtalloc", "libandroid-shmem")
RUNPATH = b"/data/data/com.termux/files/usr/lib\0"
RUNPATH_REPLACEMENT = b"$ORIGIN\0" + b"\0" * (len(RUNPATH) - len(b"$ORIGIN\0"))
TALLOC_NEEDED = b"libtalloc.so.2\0"
TALLOC_REPLACEMENT = b"libtalloc.so\0" + b"\0" * (len(TALLOC_NEEDED) - len(b"libtalloc.so\0"))


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def sha256(blob: bytes) -> str:
    return hashlib.sha256(blob).hexdigest()


def parse_alpine_release(text: str) -> dict:
    releases = []
    current = None
    for line in text.splitlines():
        if line.startswith("-"):
            if current:
                releases.append(current)
            current = {}
            continue
        if current is None or not line.startswith("  ") or line.startswith("   ") or ":" not in line:
            continue
        key, value = line.split(":", 1)
        current[key.strip()] = value.strip().strip('"')
    if current:
        releases.append(current)
    matches = [item for item in releases if item.get("flavor") == "alpine-minirootfs"]
    if len(matches) != 1:
        raise SystemExit(f"expected one alpine minirootfs, found {len(matches)}")
    return matches[0]


def parse_packages(text: str) -> dict[str, dict]:
    found = {}
    for block in text.split("\n\n"):
        fields = {}
        for line in block.splitlines():
            if ":" not in line or line.startswith(" "):
                continue
            key, value = line.split(":", 1)
            fields[key] = value.strip()
        name = fields.get("Package")
        if name in HOST_PACKAGES:
            found[name] = fields
    missing = [name for name in HOST_PACKAGES if name not in found]
    if missing:
        raise SystemExit(f"termux package index missing {missing}")
    return found


def deb_member(blob: bytes, prefix: str) -> bytes:
    if blob[:8] != b"!<arch>\n":
        raise SystemExit("not a deb")
    offset = 8
    while offset + 60 <= len(blob):
        header = blob[offset : offset + 60]
        name = header[:16].decode().strip()
        size = int(header[48:58].decode().strip())
        data = blob[offset + 60 : offset + 60 + size]
        offset += 60 + size
        if offset % 2:
            offset += 1
        if name.startswith(prefix):
            return data
    raise SystemExit(f"deb has no {prefix}")


def data_files(blob: bytes) -> dict[str, bytes]:
    payload = deb_member(blob, "data.tar")
    archive = tarfile.open(fileobj=io.BytesIO(payload), mode="r:*")
    files = {}
    for member in archive.getmembers():
        if not member.isfile():
            continue
        extracted = archive.extractfile(member)
        if extracted is None:
            continue
        files[member.name.split("/")[-1]] = extracted.read()
    return files


def replace_cstr(blob: bytes, old: bytes, new: bytes) -> tuple[bytes, int]:
    if len(new) != len(old):
        raise SystemExit("cstring replacement length mismatch")
    count = blob.count(old)
    return blob.replace(old, new), count


def elf_info(blob: bytes) -> dict:
    import struct

    if blob[:4] != b"\x7fELF":
        raise SystemExit("not an elf")
    elf_class = blob[4]
    endian = "<" if blob[5] == 1 else ">"
    if elf_class == 2:
        phoff = struct.unpack_from(endian + "Q", blob, 32)[0]
        phentsize = struct.unpack_from(endian + "H", blob, 54)[0]
        phnum = struct.unpack_from(endian + "H", blob, 56)[0]
        pack = endian + "IIQQQQQQ"
    else:
        phoff = struct.unpack_from(endian + "I", blob, 28)[0]
        phentsize = struct.unpack_from(endian + "H", blob, 42)[0]
        phnum = struct.unpack_from(endian + "H", blob, 44)[0]
        pack = endian + "IIIIIIII"
    interp = None
    dynamic = None
    loads = []
    for index in range(phnum):
        off = phoff + index * phentsize
        values = struct.unpack_from(pack, blob, off)
        if elf_class == 2:
            p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = values
        else:
            p_type, p_offset, p_vaddr, _, p_filesz, _, _, _ = values
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 3:
            interp = blob[p_offset : p_offset + p_filesz].split(b"\0", 1)[0].decode()
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)

    def vaddr_to_offset(vaddr: int) -> int:
        for base, offset, size in loads:
            if base <= vaddr < base + size:
                return offset + (vaddr - base)
        raise SystemExit("dynamic vaddr not in a load segment")

    needed = []
    runpath = None
    soname = None
    if dynamic is not None:
        dyn_off, dyn_size = dynamic
        entsize = 16 if elf_class == 2 else 8
        entries = []
        for cursor in range(0, dyn_size, entsize):
            if elf_class == 2:
                tag, value = struct.unpack_from(endian + "qQ", blob, dyn_off + cursor)
            else:
                tag, value = struct.unpack_from(endian + "iI", blob, dyn_off + cursor)
            entries.append((tag, value))
            if tag == 0:
                break
        strtab = vaddr_to_offset(next(value for tag, value in entries if tag == 5))

        def read_str(value: int) -> str:
            end = blob.index(b"\0", strtab + value)
            return blob[strtab + value : end].decode()

        for tag, value in entries:
            if tag == 1:
                needed.append(read_str(value))
            elif tag == 14:
                soname = read_str(value)
            elif tag == 29:
                runpath = read_str(value)
    return {"interp": interp, "needed": needed, "runpath": runpath, "soname": soname}


def patch_host(files: dict[str, bytes], abi: str, linker: str) -> dict[str, bytes]:
    proot, proot_needed = replace_cstr(files["proot"], TALLOC_NEEDED, TALLOC_REPLACEMENT)
    proot, proot_runpath = replace_cstr(proot, RUNPATH, RUNPATH_REPLACEMENT)
    if proot_needed < 1 or proot_runpath < 1:
        raise SystemExit(f"{abi} proot patch missed needed={proot_needed} runpath={proot_runpath}")
    talloc_name = next(name for name in files if name.startswith("libtalloc.so."))
    talloc, talloc_soname = replace_cstr(files[talloc_name], TALLOC_NEEDED, TALLOC_REPLACEMENT)
    if talloc_soname < 1:
        raise SystemExit(f"{abi} libtalloc has no soname string")
    if RUNPATH in talloc:
        talloc, _ = replace_cstr(talloc, RUNPATH, RUNPATH_REPLACEMENT)
    shmem = files["libandroid-shmem.so"]
    if RUNPATH in shmem:
        shmem, _ = replace_cstr(shmem, RUNPATH, RUNPATH_REPLACEMENT)
    info = elf_info(proot)
    if info["interp"] != linker:
        raise SystemExit(f"{abi} proot interp is {info['interp']}, expected {linker}")
    if "libtalloc.so" not in info["needed"] or "libtalloc.so.2" in info["needed"]:
        raise SystemExit(f"{abi} proot needed {info['needed']}")
    if info["runpath"] != "$ORIGIN":
        raise SystemExit(f"{abi} proot runpath is {info['runpath']}")
    if elf_info(talloc)["soname"] != "libtalloc.so":
        raise SystemExit(f"{abi} libtalloc soname was not rewritten")
    patched = {
        "libproot.so": proot,
        "libproot-loader.so": files["loader"],
        "libtalloc.so": talloc,
        "libandroid-shmem.so": shmem,
    }
    if "loader32" in files:
        patched["libproot-loader32.so"] = files["loader32"]
    return patched


def main() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    lock = {"alpine": {}, "proot": {}}
    archives = []
    alpine_version = None
    for abi, spec in ABIS.items():
        release_url = f"{ALPINE}/{spec['alpine_arch']}/latest-releases.yaml"
        release = parse_alpine_release(fetch(release_url).decode())
        version = release["version"]
        if alpine_version is None:
            alpine_version = version
        elif alpine_version != version:
            raise SystemExit(f"alpine version mismatch: {alpine_version} vs {version}")
        filename = release["file"]
        blob = fetch(f"{ALPINE}/{spec['alpine_arch']}/{filename}")
        digest = sha256(blob)
        if digest != release["sha256"]:
            raise SystemExit(f"{filename} sha256 {digest} != {release['sha256']}")
        asset_name = f"alpine-minirootfs-{spec['alpine_arch']}.bin"
        # Not .gz or .tar.gz: aapt unpacks those and drops the extension, so the
        # packaged name no longer matches the catalog. The bytes stay gzip.
        (ASSETS / asset_name).write_bytes(blob)
        alpine_bytes = len(blob)
        archives.append(
            {
                "abi": abi,
                "asset": f"linux-sandbox/{asset_name}",
                "sha256": digest,
            }
        )
        packages_url = f"{TERMUX}/dists/stable/main/binary-{spec['termux_arch']}/Packages"
        packages = parse_packages(fetch(packages_url).decode())
        extracted = {}
        lock_packages = {}
        for name, fields in packages.items():
            deb = fetch(f"{TERMUX}/{fields['Filename']}")
            digest = sha256(deb)
            if digest != fields["SHA256"]:
                raise SystemExit(f"{name} {abi} sha256 mismatch")
            extracted.update(data_files(deb))
            lock_packages[name] = {
                "version": fields["Version"],
                "filename": fields["Filename"],
                "sha256": digest,
            }
        patched = patch_host(extracted, abi, spec["linker"])
        destination = JNILIBS / abi
        destination.mkdir(parents=True, exist_ok=True)
        for stale in destination.glob("lib*.so"):
            stale.unlink()
        for name, blob in patched.items():
            (destination / name).write_bytes(blob)
        lock["proot"][abi] = {
            "linker": spec["linker"],
            "files": sorted(patched),
            "packages": lock_packages,
        }
        print(f"{abi}: alpine {version} {asset_name} {alpine_bytes} bytes, host {', '.join(sorted(patched))}")

    catalog = {"alpineVersion": alpine_version, "archives": archives}
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    notice = f"""Bundled Linux sandbox components

Alpine minirootfs {alpine_version}
  Official archive from {ALPINE}
  Packages inside the rootfs keep their own licenses.

proot, libtalloc, and libandroid-shmem
  Binaries from the Termux package repository ({TERMUX}).
  proot is GPL-2.0-or-later: https://github.com/termux/proot
  libtalloc is the Samba talloc library shipped by Termux.
  libandroid-shmem is the Termux Android System V shared-memory shim.
  These programs are executed as separate processes. They are not linked into Irio.
  The only modification is a packaging relocation: libtalloc.so.2 is renamed to
  libtalloc.so in DT_NEEDED and DT_SONAME, and DT_RUNPATH is set to $ORIGIN so
  Android can execute them from the app native-library directory.
"""
    (ASSETS / "NOTICE").write_text(notice, encoding="utf-8")
    lock["alpine"] = {"version": alpine_version, "archives": archives}
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    LOCK.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {ASSETS / 'catalog.json'}")


if __name__ == "__main__":
    main()
