#!/usr/bin/env python3
"""Upload local release signing material to the personal fork's Actions secrets."""
import base64
import json
from pathlib import Path
import subprocess


def main():
    repo = "rodolforubens/docking-enhancer-plus"
    root = Path(__file__).resolve().parent.parent
    access = json.loads(subprocess.check_output(
        ["gh", "repo", "view", repo, "--json", "viewerPermission"], text=True
    ))
    if access["viewerPermission"] != "ADMIN":
        raise SystemExit("Authenticate gh with the fork owner's account before configuring secrets.")
    properties = {}
    for line in (root / "android/keystore.properties").read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value
    keystore = root / "android/app" / properties["storeFile"]
    secrets = {
        "RELEASE_KEYSTORE_BASE64": base64.b64encode(keystore.read_bytes()).decode(),
        "RELEASE_STORE_PASSWORD": properties["storePassword"],
        "RELEASE_KEY_ALIAS": properties["keyAlias"],
        "RELEASE_KEY_PASSWORD": properties["keyPassword"],
    }
    for name, value in secrets.items():
        subprocess.run(["gh", "secret", "set", name, "--repo", repo], input=value, text=True, check=True)
        print("Configured " + name)


if __name__ == "__main__":
    main()
