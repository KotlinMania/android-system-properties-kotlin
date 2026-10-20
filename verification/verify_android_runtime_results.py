"""Verify actual Android instrumentation/native execution and its device binary."""

import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
INPUT_CASES = {
    "rejectsInteriorNulPropertyNames",
    "rejectsNonTerminatedCStringNames",
    "rejectsTrailingBytesAfterCStringTerminator",
    "rejectsMalformedUtf8CStringNames",
    "rejectsEmptyCStringBuffer",
}
DEVICE_CASES = INPUT_CASES | {
    "readsRealAndroidBuildProperties", "readsValidCStringAndMissingProperty",
}
NATIVE_CASES = INPUT_CASES | {
    "readsRealAndroidProperty", "legacyAndAvailableCallbackReadTheSameProperty",
    "missingPropertiesReturnNull", "dynamicallyResolvesRealLibcSymbols",
}


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def read_cases(directory, expected):
    files = sorted(directory.rglob("*.xml"))
    require(files, f"No test XML in {directory}")
    names = []
    for file in files:
        root = ET.parse(file).getroot()
        for key in ("failures", "errors", "skipped"):
            require(root.get(key) == "0", f"{file}: {key} is not explicitly zero")
        for case in root.iter("testcase"):
            require(not any(case.find(key) is not None for key in ("failure", "error", "skipped")), f"Unsuccessful case in {file}")
            names.append(case.attrib["name"])
    require(collections.Counter(names) == collections.Counter(expected), f"Unexpected or missing test cases: {names}")
    return names, [str(file.relative_to(ROOT)) for file in files]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=("androidNativeArm64", "androidNativeX64"))
    parser.add_argument("--device-identity", type=Path, required=True)
    parser.add_argument("--expected-avd", required=True)
    args = parser.parse_args()
    identity = json.loads(args.device_identity.read_text())
    abi, machine = {"androidNativeArm64": ("arm64-v8a", 183), "androidNativeX64": ("x86_64", 62)}[args.target]
    require(identity["api"] == "35", f"Unexpected runtime API: {identity}")
    require(identity["abi"] == abi, f"Unexpected runtime ABI: {identity}")
    require(identity["avd"] == args.expected_avd, f"Unexpected AVD: {identity}")
    require(identity["serial"] == "emulator-5554", f"Unexpected owned device serial: {identity}")
    device_cases, xml_files = read_cases(ROOT / "build/outputs/androidTest-results/connected", DEVICE_CASES)
    host_cases, host_xml = read_cases(ROOT / "build/test-results/testAndroidHostTest", INPUT_CASES)
    native_log = ROOT / f"build/test-results/{args.target}DeviceTest/runtime.log"
    text = native_log.read_text()
    require("testFailed" not in text and "testIgnored" not in text, "Native failures/ignored tests found")
    finished = re.findall(r"##teamcity\[testFinished name='([^']+)'", text)
    require(collections.Counter(finished) == collections.Counter(NATIVE_CASES), f"Unexpected native execution: {finished}")
    binary = ROOT / f"build/bin/{args.target}/debugTest/test.kexe"
    payload = binary.read_bytes()
    require(payload[:6] == b"\x7fELF\x02\x01", "Expected a 64-bit little-endian ELF test binary")
    require(struct.unpack_from("<H", payload, 18)[0] == machine, "Native binary ABI does not match runtime")
    digest = hashlib.sha256(payload).hexdigest()
    require(digest == identity["device_binary_sha256"], "Pushed native binary differs from built binary")
    source_files = list((ROOT / "src").rglob("*.kt")) + [ROOT / "build.gradle.kts", ROOT / "gradle.properties", ROOT / "gradle/libs.versions.toml"]
    result = {
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "target": args.target, "identity": identity, "native_binary_sha256": digest,
        "native_binary_bytes": len(payload), "host_cases": sorted(host_cases),
        "instrumentation_cases": sorted(device_cases), "native_cases": sorted(finished),
        "instrumentation_xml": xml_files, "host_xml": host_xml,
        "failed": 0, "skipped": 0,
        "source_sha256": {str(file.relative_to(ROOT)): hashlib.sha256(file.read_bytes()).hexdigest() for file in sorted(source_files)},
    }
    report = ROOT / f"build/reports/runtime/{args.target}-verified.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"report": str(report), "host": len(host_cases), "instrumentation": len(device_cases), "native": len(finished), "failed": 0, "skipped": 0, "binary_sha256": digest}))


if __name__ == "__main__":
    main()
