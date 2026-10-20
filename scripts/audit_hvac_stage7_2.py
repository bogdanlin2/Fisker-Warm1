#!/usr/bin/env python3
"""Offline source/bit-layout audit. No networking, BLE, CAN or vehicle writes.

Input is a local, unexecuted snapshot of moistpope/oceanfront-view at COMMIT.
The upstream files are not redistributed by this project. This audit proves
source consistency, not ECU acceptance, authorization or HVAC operation.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

COMMIT = "68a88af90b369c1b2c55d7949c4c848f41deb857"
REPO = "https://github.com/moistpope/oceanfront-view"
BLOBS = {
    "README.md": "977ffa5c4949119eb53601c70fc50f71da932a0b",
    "LICENSE.md": "650b6f55b3764720f9cc11251b0d8921eae60792",
    "static/js/carcatalog.js": "3d91ad247f79a3c7f259f2778ac941e9fd62d212",
    "static/js/cancmd.js": "a8c6be6312248ca773377f4d31f527f7f7b6e96a",
    "static/js/ibus_tables.js": "e65746276b335260f5e5ac67a498ebf95bac9236",
    "dbc/fisker_ocean_ibus.dbc": "823ddccbca4e6513c9781aa8789915db0b45e850",
    "tools/gen_ibus.py": "8c98965af8246ec79fd35bb7f6e79473ffc5b78f",
    "android/canbridge/main.go": "174f76903a197bb73dd38b68df8242293c3ecc8d",
    "android/app/src/main/java/ai/sunnypilot/webhud/CanBridge.kt":
        "20ea9075877e4c1e7f16d14733ae714badd70ea8",
}


def require(condition, description):
    if not condition:
        raise ValueError(description)


def motorola_read(data, start, width):
    """Independent DBC sawtooth traversal, MSB first (no upstream encoder)."""
    result, pos = 0, start
    for _ in range(width):
        result = (result << 1) | ((data[pos // 8] >> (pos % 8)) & 1)
        pos = pos + 15 if pos % 8 == 0 else pos - 1
    return result


def run(root):
    manifest, texts = [], {}
    for rel, expected in BLOBS.items():
        raw = (root / rel).read_bytes()
        blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        require(blob == expected, f"Source mismatch: {rel}")
        manifest.append({"path": rel, "git_blob": blob,
                         "sha256": hashlib.sha256(raw).hexdigest(),
                         "url": f"{REPO}/blob/{COMMIT}/{rel}"})
        texts[rel] = raw.decode("utf-8")
    dbc = texts["dbc/fisker_ocean_ibus.dbc"]

    def message(mid):
        match = re.search(rf"^BO_ {mid} .+?(?=^BO_|\Z)", dbc, re.M | re.S)
        require(match is not None, f"Missing message: {mid}")
        return match.group(0)

    def field(mid, name, start, width):
        require(re.search(rf"^ SG_ {name} : {start}\|{width}@0\+", message(mid), re.M), name)
        return {"id": f"{mid:03X}", "name": name, "start": start, "length": width}

    fields = [field(0x534, "ICC_ECCSysSwtCmd", 57, 2),
              field(0x373, "ECC_CrtSts", 3, 4),
              field(0x373, "ECC_WindSpdSts", 12, 4),
              field(0x373, "ECC_HeatSts", 32, 1),
              field(0x373, "ECC_RemSts", 60, 2),
              field(0x4F8, "TBOX_PreCondTriggerReq", 42, 3)]
    # Exhaust the entire byte, with the other bytes nonzero, against independent formulas.
    for b in range(256):
        data = bytearray([0xA5] * 8)
        data[7] = b
        require(motorola_read(data, 57, 2) == b & 3, "ICC switch bit layout")
        require(motorola_read(data, 60, 2) == (b >> 3) & 3, "ECC remote bit layout")
        data[0] = b
        require(motorola_read(data, 3, 4) == b & 15, "ECC state bit layout")
        data[1] = b
        require(motorola_read(data, 12, 4) == (b >> 1) & 15, "ECC fan bit layout")
        data[4] = b
        require(motorola_read(data, 32, 1) == b & 1, "ECC heat bit layout")
        data[5] = b
        require(motorola_read(data, 42, 3) == b & 7, "TBOX trigger bit layout")
    catalog = texts["static/js/carcatalog.js"]
    require("CLIMATE2: 0x534" in catalog, "UI message ID")
    require("ICC_ECCSysSwtCmd: v ? 1 : 2" in catalog, "UI power mapping")
    require('1 "Air_conditioning_power_on_(Short)"' in dbc, "DBC ON enum")
    require('2 "Air_conditioning_shutdown_(Long)"' in dbc, "DBC OFF enum")
    require('3 "Start_Precond._Cabin"' in dbc, "DBC cabin trigger enum")
    # Co-carried fields are a reason NOT to copy the default-filled TX frame.
    require("ICC_MinChrgSoc_Setting" in message(0x534), "Charging side-effect field")
    require("ICC_PwrBattDrvgHeatSwtSts" in message(0x534), "Battery heating side-effect field")
    require("CheckSum" in message(0x4F8) and "AliveCounter" in message(0x4F8), "TBOX integrity fields")
    return {
        "schema": "fisker-warm-stage7.2-source-audit-v1",
        "source_commit": COMMIT,
        "source_files": manifest,
        "source_consistency": "PASS",
        "bit_layout": {"method": "independent Motorola sawtooth traversal vs byte-mask formulas",
                       "byte_values_per_field": 256, "fields": fields, "result": "PASS"},
        "findings": {
            "icc_native_can_candidate": "0x534 ICC_ECCSysSwtCmd; source-level only",
            "tbox_preconditioning_candidate": "0x4F8 TBOX_PreCondTriggerReq; source-level only",
            "full_534_frame_has_other_setting_fields": True,
            "tbox_4f8_has_checksum_and_counter": True,
            "upstream_rx_keepalive_is_not_a_fresh_vehicle_rx_proof": True,
        },
        "limits": {"no_original_matrix_xlsx": True,
                   "no_owner_vehicle_trace": True,
                   "obd_routing": "UNVERIFIED",
                   "vehicle_authorization": "UNVERIFIED",
                   "sleep_locked_start": "UNVERIFIED",
                   "last_settings_retention": "UNVERIFIED",
                   "automatic_shutdown": "UNVERIFIED",
                   "actual_hvac_started_by_fisker_warm": False},
        "vehicle_commands_sent": 0,
        "apk_created": 0,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = run(args.source_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Source hashes and independent CAN bit layout: PASS. Vehicle operation: UNVERIFIED.")
