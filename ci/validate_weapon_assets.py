"""Validate original weapon resources using only Python's standard library.
Run from any directory: python ci/validate_weapon_assets.py
This checks resource structure, not Minecraft's rendered appearance.
"""
from pathlib import Path
import json
import math
import re
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/counterstrike"
CONTEXTS = {"gui", "ground", "fixed", "head", "firstperson_righthand",
            "firstperson_lefthand", "thirdperson_righthand", "thirdperson_lefthand"}
FACES = {"north", "south", "east", "west", "up", "down"}


def vector(value, low, high):
    assert isinstance(value, list) and len(value) == 3, value
    assert all(isinstance(n, (int, float)) and math.isfinite(n) and low <= n <= high
               for n in value), value


def resource(identifier, folder, suffix):
    assert re.fullmatch(r"counterstrike:[a-z0-9_/-]+", identifier), identifier
    path = ASSETS / folder / (identifier.split(":", 1)[1] + suffix)
    assert path.is_file(), f"Missing resource: {path}"
    return path


def validate_png(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    offset, compressed, dimensions, ended = 8, bytearray(), None, False
    while offset < len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        crc = struct.unpack_from(">I", data, offset + 8 + length)[0]
        assert zlib.crc32(kind + payload) & 0xffffffff == crc, path
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            assert width > 0 and height > 0 and not width & (width - 1) and not height & (height - 1)
            assert (depth, color, compression, filtering, interlace) == (8, 6, 0, 0, 0)
            dimensions = (width, height)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            ended = True
        offset += length + 12
    assert ended and dimensions and offset == len(data), path
    width, height = dimensions
    assert len(zlib.decompress(compressed)) == height * (1 + width * 4), path
    return dimensions


def main():
    source = (ROOT / "src/main/java/com/jan1a234/counterstrike/weapon/WeaponType.java").read_text(encoding="utf-8")
    required = set(re.findall(r'\b[A-Z_]+\(\s*"([a-z0-9_]+)"', source))
    assert required, "No WeaponType IDs found"
    definitions = {p.stem: p for p in (ASSETS / "items").glob("*.json")}
    assert required <= definitions.keys(), f"Missing weapon definitions: {sorted(required - definitions.keys())}"
    textures, geometries, total = set(), set(), 0
    for identifier, path in sorted(definitions.items()):
        definition = json.loads(path.read_text(encoding="utf-8"))
        assert definition["model"]["type"] == "minecraft:model", path
        assert definition["model"]["model"] == f"counterstrike:item/{identifier}", path
        model_path = resource(definition["model"]["model"], "models", ".json")
        model = json.loads(model_path.read_text(encoding="utf-8"))
        assert model.get("gui_light") == "front", model_path
        assert CONTEXTS <= model["display"].keys(), model_path
        for context, transform in model["display"].items():
            vector(transform["rotation"], -360, 360)
            vector(transform["translation"], -80, 80)
            vector(transform["scale"], 0.001, 4)
        for ref in model["textures"].values():
            textures.add(resource(ref, "textures", ".png"))
        assert "particle" in model["textures"], model_path
        elements = model["elements"]
        assert len(elements) >= 8, f"Insufficient geometry: {model_path}"
        signature = json.dumps(elements, sort_keys=True)
        assert signature not in geometries, f"Duplicate silhouette: {model_path}"
        geometries.add(signature)
        for element in elements:
            vector(element["from"], -16, 32)
            vector(element["to"], -16, 32)
            assert all(a < b for a, b in zip(element["from"], element["to"])), element
            assert set(element["faces"]) == FACES, element
            if "rotation" in element:
                rotation = element["rotation"]
                vector(rotation["origin"], -16, 32)
                assert rotation["axis"] in "xyz" and rotation["angle"] in (-45, -22.5, 0, 22.5, 45)
            for face in element["faces"].values():
                assert face["texture"].startswith("#") and face["texture"][1:] in model["textures"], face
                assert len(face["uv"]) == 4 and all(0 <= value <= 16 for value in face["uv"]), face
        total += len(elements)
        print(f"PASS {identifier}: {len(elements)} cuboids; 8 display contexts; all references resolve")
    for texture in sorted(textures):
        print(f"PASS texture {texture.name}: RGBA {validate_png(texture)}, CRC and decompression valid")
    print(f"PASS {len(definitions)} models, {total} cuboids, {len(textures)} textures; all {len(required)} WeaponType IDs covered")


if __name__ == "__main__":
    main()
