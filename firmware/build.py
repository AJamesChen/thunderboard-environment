#!/usr/bin/env python3
"""Generate and build using tools registered by Simplicity Installer."""
import json
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import zipfile

PROJECT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", type=int, default=4, help="application firmware version (default: 4)")
    args = parser.parse_args()
    if not 1 <= args.version <= 0xffffffff:
        parser.error("version must fit an unsigned 32-bit integer")
    registry = Path.home() / ".silabs/tools.json"
    if not registry.is_file():
        raise SystemExit("Install Simplicity Studio 6 and its SDK/toolchain first.")
    tools = json.loads(registry.read_text())

    def locate(tool, version=None):
        for item in tools.get(tool, []):
            if version is None or item["version"] == version:
                return Path(item["path"])
        suffix = f" {version}" if version else ""
        raise SystemExit(f"Missing {tool}{suffix}; install it with Simplicity Installer.")

    sdk = locate("simplicity-sdk", "2026.6.2")
    gcc = locate("gcc-arm-none-eabi", "14.2.rel1")
    slc = locate("slc-cli") / "slc"
    cmake = locate("cmake") / "bin/cmake"
    commander = locate("commander") / "commander"
    env = os.environ.copy()
    env["ARM_GCC_DIR"] = str(gcc)
    env["NINJA_EXE_PATH"] = str(locate("ninja") / "ninja")
    env["POST_BUILD_EXE"] = str(commander)

    def run(args, cwd):
        print("+ " + shlex.join(map(str, args)), flush=True)
        subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)

    # config/ is generated and ignored: source configuration lives in the .slcp
    # and root .btconf. Always refresh it so GATT edits reach the firmware.
    run([slc, "generate", "bootloader.slcp", "--sdk", sdk,
         "-o", "cmake", "--toolchain-locations", f"gcc:{gcc}",
         "--replace-config", "--no-daemon", "--no-color"], PROJECT / "bootloader")
    run([cmake, "--workflow", "--preset", "project"], PROJECT / "bootloader/cmake_gcc")
    # SLC preserves project-owned GATT input across generations, even with
    # --replace-config. Explicitly stage the versioned source before generation.
    gatt_input = PROJECT / "config/btconf/gatt_configuration.btconf"
    gatt_input.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(PROJECT / "gatt_configuration.btconf", gatt_input)
    run([slc, "generate", "thunderboard_environment.slcp", "--sdk", sdk,
         "-o", "cmake,vscode", "--toolchain-locations", f"gcc:{gcc}",
         "--configuration", f"SL_APPLICATION_VERSION:{args.version}",
         "--replace-config", "--no-daemon", "--no-color"], PROJECT)
    run([cmake, "--workflow", "--preset", "project"], PROJECT / "cmake_gcc")
    output = PROJECT / "artifacts"
    output.mkdir(exist_ok=True)
    app = PROJECT / "cmake_gcc/build/base/thunderboard_environment"
    # Reject growth into the reserved update slot, even if the SDK linker allows it.
    upper = 0
    for line in app.with_suffix(".hex").read_text().splitlines():
        record = bytes.fromhex(line[1:])
        kind = record[3]
        if kind == 4:
            upper = int.from_bytes(record[4:6], "big") << 16
        elif kind == 2:
            upper = int.from_bytes(record[4:6], "big") << 4
        elif kind == 0:
            start = upper + int.from_bytes(record[1:3], "big")
            if start < 0x6000 or start + record[0] > 0x44000:
                raise SystemExit("Application overlaps the bootloader or reserved OTA slot")
    gbl = output / f"firmware-{args.version}.gbl"
    run([commander, "gbl", "create", gbl, "--app", app.with_suffix(".s37")], PROJECT)
    image = gbl.read_bytes()
    if len(image) > 196608:
        raise SystemExit("Application GBL exceeds the 192 KiB OTA slot")
    manifest = (f"format=1\nboard=BRD4184A-A02\nversion={args.version}\n"
                f"size={len(image)}\nsha256={hashlib.sha256(image).hexdigest()}\n")
    package = output / f"firmware-{args.version}.bg22ota"
    with zipfile.ZipFile(package, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.properties", manifest)
        archive.writestr("application.gbl", image)
    bootstrap = output / f"bootstrap-{args.version}.hex"
    run([commander, "convert", PROJECT / "bootloader/cmake_gcc/build/base/bg22_bootloader.s37",
         app.with_suffix(".s37"), "--outfile", bootstrap], PROJECT)
    print("USB bootstrap: " + str(bootstrap))
    print("Phone OTA package: " + str(package))


if __name__ == "__main__":
    main()
