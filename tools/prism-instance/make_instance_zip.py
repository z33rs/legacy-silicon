#!/usr/bin/env python3
"""Builds the one-click Prism Launcher / MultiMC instance zip shipped with each release.

Contents: Legacy Fabric loader + intermediary for 1.9.4, the mod jar, and Prism's own LWJGL 2 component
(which already carries Apple Silicon natives). Java is left to Prism's automatic Java selection.

usage: make_instance_zip.py <mod.jar> <out.zip>
"""
import json, os, sys, zipfile

MC, LOADER = "1.9.4", "0.19.5"


def main():
    jar, out = sys.argv[1], sys.argv[2]
    pack = {"formatVersion": 1, "components": [
        {"uid": "org.lwjgl", "version": "2.9.4-nightly-20150209", "dependencyOnly": True},
        {"uid": "net.minecraft", "version": MC, "important": True},
        {"uid": "net.fabricmc.intermediary", "version": MC, "dependencyOnly": True},
        {"uid": "net.fabricmc.fabric-loader", "version": LOADER},
    ]}
    intermediary = {"formatVersion": 1, "uid": "net.fabricmc.intermediary", "name": "Intermediary Mappings (Legacy Fabric)",
                    "version": MC, "+libraries": [{"name": f"net.legacyfabric:intermediary:{MC}", "url": "https://maven.legacyfabric.net/"}]}
    cfg = ["[General]", "ConfigVersion=1.3", "InstanceType=OneSix", "iconKey=default",
           f"name=Legacy Silicon {MC}", "AutomaticJava=true"]
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("instance.cfg", "\n".join(cfg) + "\n")
        z.writestr("mmc-pack.json", json.dumps(pack, indent=4))
        z.writestr("patches/net.fabricmc.intermediary.json", json.dumps(intermediary, indent=4))
        z.write(jar, ".minecraft/mods/" + os.path.basename(jar))
    print("wrote", out)


if __name__ == "__main__":
    main()
