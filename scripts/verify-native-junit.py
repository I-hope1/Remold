#!/usr/bin/env python3
"""Verify LibToolTest and EnumBucketPlanTest JUnit XML reports ran with 0 failures/skips."""
import pathlib
import sys
import xml.etree.ElementTree as ET

results_dir = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "tests/build/test-results/test")
required = ["TEST-remold.LibToolTest.xml", "TEST-remold.EnumBucketPlanTest.xml"]

for name in required:
    path = results_dir / name
    if not path.is_file():
        raise SystemExit(f"Missing JUnit report: {path}")
    root = ET.parse(path).getroot()
    tests = int(root.attrib.get("tests", "0"))
    failures = int(root.attrib.get("failures", "0"))
    errors = int(root.attrib.get("errors", "0"))
    skipped = int(root.attrib.get("skipped", "0"))
    print(f"[verify-junit] {name}: tests={tests}, failures={failures}, errors={errors}, skipped={skipped}")
    if tests <= 0 or failures != 0 or errors != 0 or skipped != 0:
        raise SystemExit(f"Unexpected result in {name}: tests={tests}, failures={failures}, errors={errors}, skipped={skipped}")
