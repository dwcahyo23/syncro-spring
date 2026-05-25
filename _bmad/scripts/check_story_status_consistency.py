#!/usr/bin/env python3
"""Check Syncro BMAD story `Status:` headers against sprint-status.yaml."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
ARTIFACTS = ROOT / "_bmad-output" / "implementation-artifacts"
STATUS_FILE = ARTIFACTS / "sprint-status.yaml"

status_text = STATUS_FILE.read_text(encoding="utf-8")
status_map: dict[str, str] = {}
for line in status_text.splitlines():
    match = re.match(r"\s{2}([0-9]+-[0-9]+-[^:]+):\s*(\S+)", line)
    if match:
        status_map[match.group(1)] = match.group(2)

mismatches: list[str] = []
missing_files: list[str] = []
for story_key, sprint_status in sorted(status_map.items()):
    story_file = ARTIFACTS / f"{story_key}.md"
    if not story_file.exists():
        if sprint_status != "backlog":
            missing_files.append(story_key)
        continue
    text = story_file.read_text(encoding="utf-8-sig")
    header = re.search(r"(?m)^Status:\s*(\S+)", text)
    file_status = header.group(1) if header else "<missing>"
    if file_status != sprint_status:
        mismatches.append(f"{story_key}: sprint-status={sprint_status}, file={file_status}")

if missing_files:
    print("Missing story files:")
    for item in missing_files:
        print(f"- {item}")
if mismatches:
    print("Status mismatches:")
    for item in mismatches:
        print(f"- {item}")
    sys.exit(1)

print(f"OK: {len(status_map)} story statuses match story file headers")
