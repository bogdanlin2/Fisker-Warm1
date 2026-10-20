#!/usr/bin/env python3
"""Offline evidence audit. Reads files only; no BLE, CAN, network or vehicle operations.

The parser below is independent of the Android CAN parser. PDF text anchors establish
document identity/search locations; conductor connections require visual review and
never establish that a drawing matches the owner's as-built vehicle.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import subprocess


PINNED_DBC_SHA256 = "0cbf35b8f65e8f1184b4c0c9db4fc9877c33382b8d744cf717a69f3ff622912b"
PINNED_GENERATOR_SHA256 = "ec790c0fd57a7f86159a59b5d56b8124c430464bfdd38a49ff5c97143b25cb1e"
MEASURED_LOG_SHA256 = "3add07e50f00ff607b7a17727028fe825d8e957971fd63e144c435be243ab757"
ALLOWED = {"ATZ\r", "ATE0\r", "ATI\r", "ATRV\r", "ATL0\r", "ATS1\r", "ATH1\r",
           "ATSP6\r", "ATCAF0\r", "ATCFC0\r", "ATCSM1\r", "ATD1\r", "ATDPN\r",
           "ATAR\r", "ATCM7FF\r", "ATCF0E9\r", "ATCF373\r", "ATCRA373\r", "ATMA\r", " "}
PDFS = {
    "wiring": {"sha256": "bd9d4731622b18060ba041a7d6c6c029e89dc7b7a9d58a50b924e03c3ca927f7", "pages": 91,
               "anchors": {4: ["Electronic Climate Control (ECC)", "EV-CAN-H", "C088B*2F"],
                           5: ["THERMAL BUS", "Thermal-CAN-H"],
                           9: ["INFOTAINMENT BUS", "IBUS2 CAN_H", "IBUS1 CAN _H", "V078B*1F"],
                           13: ["DiagBus_H", "DiagBus_L", "OBD", "W1040_1", "X*06", "X*14"],
                           29: ["DiagBus_H", "IBUS2_H", "Private-CAN-H"],
                           67: ["EV-CAN-H", "Thermal-CAN-H"],
                           69: ["IBUS1 CAN _H", "IBUS2 CAN_H"]}},
    "architecture": {"sha256": "de13684830b87d1f4ce0a8655aded4f42122d9ff4622a94ea3cbfcfe496f1086", "pages": 144,
                     "anchors": {1: ["PT Full", "V0.04", "01.02.2022"],
                                 12: ["DLC1 moved from Diagnostic CAN to T BUS CAN"],
                                 40: ["DLC1 moved from Diagnostic CAN to TBus CAN"]}},
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def dump(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def can_rows(raw: bytes) -> tuple[list[dict], list[str]]:
    valid, invalid = [], []
    for row in re.split(rb"[\r\n]+", raw.replace(b">", b"\r")):
        tokens = row.split()
        if not tokens or not re.fullmatch(rb"[0-9A-Fa-f]{3}|[0-9A-Fa-f]{8}", tokens[0]):
            continue
        good_dlc = len(tokens) > 1 and bool(re.fullmatch(rb"[0-8]", tokens[1]))
        dlc = int(tokens[1]) if good_dlc else -1
        if good_dlc and len(tokens) == 2 + dlc and all(re.fullmatch(rb"[0-9A-Fa-f]{2}", t) for t in tokens[2:]):
            valid.append({"id": tokens[0].decode().upper(), "dlc": dlc,
                          "payload": " ".join(t.decode().upper() for t in tokens[2:])})
        else:
            invalid.append(row.decode("ascii", "replace"))
    return valid, invalid


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--log", type=Path, required=True)
    ap.add_argument("--source-root", type=Path, required=True, help="pinned oceanfront-view source files")
    ap.add_argument("--wiring-pdf", type=Path)
    ap.add_argument("--architecture-pdf", type=Path)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    checks: list[dict] = []

    def require(name: str, condition: bool) -> None:
        checks.append({"check": name, "passed": bool(condition)})
        if not condition:
            raise ValueError(name)

    log_bytes = args.log.read_bytes()
    bundle = json.loads(log_bytes)
    require("Measured input hash identifies the new 08:24 export", digest(log_bytes) == MEASURED_LOG_SHA256)
    runs = [r for r in bundle["runs"] if r.get("appVersion") == "0.3.6-lab" and r.get("mode") == "CAN_RX_CONTROL"]
    require("Exactly one new differential session; no accumulated historical sessions used", len(runs) == 1)
    run = runs[0]
    expected = [("CONTROL_BEFORE", "0E9", "CM_CF", 40), ("ECC_VISIBILITY", "373", "CM_CF", 0),
                ("ECC_ALTERNATE_FILTER", "373", "CRA", 0), ("CONTROL_AFTER", "0E9", "CM_CF", 41)]
    require("Four expected phases in order", [(p["phase"], p["id"], p["filterMethod"]) for p in run["canRxAudit"]]
            == [x[:3] for x in expected])
    events = run["bleIo"]["events"]
    global_rx = collections.Counter((e["receivedNanos"], e["hex"].upper()) for e in events if e["kind"] == "RX_NOTIFICATION")
    summaries, fixture = [], []
    for phase, (name, can_id, method, count) in zip(run["canRxAudit"], expected):
        cap = phase["capture"]
        packets = cap["notifications"]
        raw = b"".join(bytes.fromhex(p["hex"]) for p in packets)
        frames, invalid = can_rows(raw)
        require(name + ": independent raw CAN row count matches frames[]", len(frames) == count == cap["frameCount"] == len(cap["frames"]))
        require(name + ": all retained raw packets occur in global BLE journal", all(global_rx[(p["receivedNanos"], p["hex"].upper())] == 1 for p in packets))
        require(name + ": ID, DLC, payload and raw byte count agree", all(f["id"] == can_id and f["dlc"] == 8 for f in frames)
                and [f["payload"] for f in frames] == [" ".join(f"{v:02X}" for v in bytes.fromhex(f["payload"])) for f in cap["frames"]]
                and len(raw) == cap["receivedBytes"])
        if can_id == "373":
            require(name + ": only STOPPED/prompt, complete host window and no overflow", raw == b"STOPPED\r\r>"
                    and not cap["incomplete"] and not cap["adapterBufferFull"] and cap["windowElapsed"]
                    and cap["captureElapsedMillis"] >= 3000 and cap["startWriteConfirmed"] and cap["stopConfirmed"])
        else:
            require(name + ": overflow and malformed lines remain incomplete", cap["incomplete"] and cap["adapterBufferFull"]
                    and len(invalid) == cap["invalidFrameLines"] and b"BUFFER FULL" in raw)
        metadata_keys = ["startedEpochMillis", "startedNanos", "captureElapsedMillis", "captureTotalMillis", "endReason",
                         "frameCountAtAcquisitionEnd", "drainFrameCount", "startWriteConfirmed", "stopWriteConfirmed",
                         "stopConfirmed", "promptSeen", "adapterBufferFull", "incomplete", "invalidFrameLines",
                         "droppedFrames", "droppedPackets", "truncated"]
        summaries.append({"phase": name, "id": can_id, "filterMethod": method, "independentValidRows": len(frames),
                          "notifications": len(packets), "rawBytes": len(raw),
                          "distinctPayloads": dict(collections.Counter(f["payload"] for f in frames)),
                          "invalidRawLines": invalid, **{k: cap[k] for k in metadata_keys}})
        fixture.append({"phase": name, "id": can_id, "filterMethod": method, "notifications": packets})

    requests = [e for e in events if e["kind"] == "TX_REQUEST"]
    commands = [bytes.fromhex(e["hex"]).decode("ascii") for e in requests]
    require("All 46 physical write requests are fixed adapter settings or monitor stop", len(requests) == 46 and all(c in ALLOWED for c in commands))
    require("Each request has exactly one successful GATT callback and completion", all(
        sum(e["kind"] == kind and e["txId"] == req["txId"] and e["status"] == 0 for e in events) == 1
        for req in requests for kind in ["TX_CALLBACK", "TX_COMPLETE"]))
    require("Exactly four ATMA writes; no active ECU request or CAN payload", commands.count("ATMA\r") == 4
            and run["vehicleCommandsSent"] == 0 and run["canTransmissionsRequested"] == 0)
    prefix = ["ATZ\r", "ATE0\r", "ATI\r", "ATRV\r", "ATL0\r", "ATS1\r", "ATH1\r", "ATSP6\r", "ATCAF0\r", "ATCFC0\r", "ATCSM1\r", "ATD1\r", "ATDPN\r"]
    require("Full verified startup repeated before 373; CM/CF and CRA independently requested", commands[:13] == prefix
            and commands[18:31] == prefix and commands[31:40] == ["ATAR\r", "ATCM7FF\r", "ATCF373\r", "ATMA\r", " ", "ATAR\r", "ATCRA373\r", "ATMA\r", " "])
    require("One recovery and honest overall incomplete classification", run["differentialPhasesCompleted"]
            and run["incomplete"] and run["canRxAuditEvidence"]["recoveriesCompleted"] == 1
            and not run["canRxAuditEvidence"]["allCapturesComplete"] and run["canRxAuditEvidence"]["eccCapturesComplete"])
    require("Host journal retains raw RX; no synthetic input, PHY silence or hardware timestamp claim", run["bleIo"]["complete"]
            and run["bleIo"]["droppedEvents"] == 0 and not run["syntheticRx"] and not run["physicalSilentModeVerified"]
            and not run["physicalCanRxTimestampsAvailable"])
    measurement = {"schema": "fisker-warm-stage7.6-measurement-v1", "inputFile": args.log.name,
                   "inputSha256": digest(log_bytes), "inputBytes": len(log_bytes),
                   "runStartedAt": run["startedAt"], "runFinishedAt": run["finishedAt"],
                   "result": run["result"], "evidence": run["canRxAuditEvidence"], "phases": summaries,
                   "txRequests": [{"id": e["txId"], "nanos": e["receivedNanos"], "command": c} for e, c in zip(requests, commands)],
                   "gattWritesAndCompletions": len(requests), "globalRxNotifications": sum(global_rx.values()),
                   "ownerMarks": run["ownerMarks"], "climateRunningMeasured": run.get("climateRunning"),
                   "physicalSilentModeVerified": False, "physicalCanTimestampsAvailable": False,
                   "scope": "Raw bytes received by Android; distinct physical CAN transmissions and all upstream losses remain unknown"}
    dump(args.output / "measured-session-analysis.json", measurement)
    dump(args.output / "measured-phase-rx-fixture.json", {"inputSha256": digest(log_bytes), "phases": fixture})

    dbc_bytes = (args.source_root / "dbc/fisker_ocean_ibus.dbc").read_bytes()
    generator_bytes = (args.source_root / "tools/gen_ibus.py").read_bytes()
    require("Pinned DBC and generator bytes match previous source audit", digest(dbc_bytes) == PINNED_DBC_SHA256 and digest(generator_bytes) == PINNED_GENERATOR_SHA256)
    dbc = dbc_bytes.decode()
    generator = generator_bytes.decode()
    sg_writer = [(n, line) for n, line in enumerate(generator.splitlines(), 1) if "lines.append(f' SG_ " in line]
    require("Generator hard-codes receiver ICC and collapses duplicate IDs across buses", len(sg_writer) == 1
            and sg_writer[0][1].endswith(" ICC')") and 'if m["addr"] in seen_addr' in generator)
    dbc_records = []
    for number in [0x373, 0x534, 0x0E9]:
        message = re.search(rf"^BO_ {number} (\w+): (\d+) (\S+)$", dbc, re.M)
        comment = re.search(rf'^CM_ BO_ {number} "([^"]*)";', dbc, re.M)
        cycle = re.search(rf'^BA_ "GenMsgCycleTime" BO_ {number} (\d+);', dbc, re.M)
        require(f"DBC ID {number:03X}: exact 11-bit, 8-byte source record found", bool(message and comment and int(message[2]) == 8 and number < 0x800))
        dbc_records.append({"id": f"{number:03X}", "name": message[1], "length": int(message[2]),
                            "declaredTransmitter": message[3], "comment": comment[1], "cycleMillis": int(cycle[1]) if cycle else None,
                            "messageLine": dbc[:message.start()].count("\n") + 1,
                            "commentLine": dbc[:comment.start()].count("\n") + 1})
    dump(args.output / "dbc-provenance-audit.json", {"commit": "68a88af90b369c1b2c55d7949c4c848f41deb857",
         "dbcSha256": digest(dbc_bytes), "generatorSha256": digest(generator_bytes), "versionHeader": dbc.splitlines()[0],
         "records": dbc_records, "receiverIsGeneratedPlaceholder": True, "receiverWriterLine": sg_writer[0][0],
         "duplicateIdsAcrossBusesAreCollapsed": True, "originalXlsxVerified": False, "ownerFirmwareMatchVerified": False,
         "obdRoutingSpecifiedByThisDbc": False})

    pdf_audits = []
    for name, path in [("wiring", args.wiring_pdf), ("architecture", args.architecture_pdf)]:
        if path is None:
            continue
        spec = PDFS[name]
        require(name + ": exact public PDF bytes", digest(path.read_bytes()) == spec["sha256"])
        info = subprocess.check_output(["pdfinfo", str(path)], text=True)
        require(name + ": expected PDF page count", bool(re.search(rf"^Pages:\s+{spec['pages']}$", info, re.M)))
        pages = []
        for page, anchors in spec["anchors"].items():
            text = subprocess.check_output(["pdftotext", "-f", str(page), "-l", str(page), str(path), "-"], text=True)
            normalized = " ".join(text.split())
            require(f"{name} PDF page {page}: document search anchors", all(" ".join(a.split()) in normalized for a in anchors))
            pages.append({"pdfPage": page, "anchorsFound": anchors})
        pdf_audits.append({"name": name, "sha256": spec["sha256"], "pages": spec["pages"], "locations": pages,
                           "scope": "Search locations only; physical wire mapping was separately visually reviewed, VIN/OS fit unverified"})
    dump(args.output / "pdf-source-anchor-audit.json", pdf_audits)
    dump(args.output / "offline-evidence-checks.json", {"checks": checks, "checksPassed": len(checks), "allPassed": True,
                                                       "vehicleCommandsSent": 0, "apkCreated": False, "newPhysicalTestPerformed": False})
    print(json.dumps({"checksPassed": len(checks), "allPassed": True, "phases": [p["independentValidRows"] for p in summaries],
                      "txAdapterRequests": len(requests), "vehicleCommandsSent": 0, "apkCreated": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
