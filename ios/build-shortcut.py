#!/usr/bin/env python3
"""Build the iOS shortcut that turns Personal Hotspot on.

Run from the repo root:

    python3 ios/build-shortcut.py

Produces ios/iPhone-Hotspot-On.shortcut as a binary plist.

Important iOS facts this file cannot change:
  * Personal automations (the Bluetooth trigger) are not exportable. The
    trigger must be created on the iPhone by hand; see the README.
  * Since iOS 15 a shortcut must be signed with Apple's `shortcuts` CLI before
    iOS will import it. See ios/sign-shortcut.sh.
"""
from __future__ import annotations

import pathlib
import plistlib

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "iPhone-Hotspot-On.shortcut"

SHORTCUT = {
    "WFWorkflowName": "iPhone Hotspot On",
    "WFWorkflowClientVersion": "3036.0.4",
    "WFWorkflowClientRelease": "17.0",
    "WFWorkflowMinimumClientVersion": 900,
    "WFWorkflowMinimumClientVersionString": "900",
    "WFWorkflowIcon": {
        "WFWorkflowIconGlyphNumber": 59511,
        "WFWorkflowIconStartColor": 4282601983,
    },
    "WFWorkflowTypes": ["NCWidget"],
    "WFWorkflowInputContentItemClasses": [],
    "WFWorkflowActions": [
        # Give iOS a beat to finish bringing the Bluetooth link up.
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.delay",
            "WFWorkflowActionParameters": {"WFDelayTime": 3},
        },
        # Set Personal Hotspot -> On. These "set" actions share the OnValue key.
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.personalhotspot.set",
            "WFWorkflowActionParameters": {"OnValue": True},
        },
    ],
}


def main() -> None:
    OUT.write_bytes(plistlib.dumps(SHORTCUT, fmt=plistlib.FMT_BINARY))
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
