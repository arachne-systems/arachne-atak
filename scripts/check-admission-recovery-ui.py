#!/usr/bin/env python3
"""Check the stranded-admission recovery path without a device or network."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = (ROOT / "android/app/src/main/kotlin/dev/datafabric/atak/WorkspaceController.kt").read_text()
PLUGIN = (ROOT / "android/app/src/main/kotlin/dev/datafabric/atak/FabricPlugin.kt").read_text()

assert '"admission_recovery_required" ->' in CONTROLLER
assert 'reply.optString("recovery") == "remove_and_reinvite"' in CONTROLLER
assert 'state != "admission_recovery_required"' in CONTROLLER
assert 'staged.optString("recovery") == "remove_and_reinvite"' in CONTROLLER
assert "Use Remove member below" in CONTROLLER
assert '"Retry now"' in PLUGIN
assert '"Remove member"' in PLUGIN
assert 'missing local join state' in PLUGIN

print("PASS: stranded admission is surfaced, automatic retry stops, and the member view exposes remove/reinvite recovery")
