#!/usr/bin/env python3
"""Models, blockstates, recipes and lang for PrideChat's radios + devices."""
import json, os
R = os.path.expanduser("~/mc-mods/pridechat/src/main/resources/assets/pridechat")
def w(path, obj):
    os.makedirs(os.path.dirname(os.path.join(R, path)), exist_ok=True)
    with open(os.path.join(R, path), "w") as f: json.dump(obj, f, indent=2)

ITEMS = ["walkie_talkie", "radio", "megaphone", "radio_key", "room_marker"]
for i in ITEMS:
    w(f"models/item/{i}.json", {"parent": "item/generated", "textures": {"layer0": f"pridechat:items/{i}"}})

def face(tex, uv=None):
    f = {"texture": tex}
    if uv: f["uv"] = uv
    return f

def box(frm, to, top, side, bottom=None, side_uv=None):
    return {"from": frm, "to": to, "faces": {
        "up": face(top), "down": face(bottom or side),
        "north": face(side, side_uv), "south": face(side, side_uv), "east": face(side, side_uv), "west": face(side, side_uv)}}

BLOCKS = {
    "radio_tower": {"parent": "block/cube_column", "textures": {"end": "pridechat:blocks/radio_tower_top", "side": "pridechat:blocks/radio_tower_side"}},
    "intercom": {"parent": "block/cube_bottom_top", "textures": {"side": "pridechat:blocks/intercom_front", "top": "pridechat:blocks/device_side", "bottom": "pridechat:blocks/device_side"}},
    "speaker": {"parent": "block/cube_bottom_top", "textures": {"side": "pridechat:blocks/speaker_front", "top": "pridechat:blocks/device_side", "bottom": "pridechat:blocks/device_side"}},
    "pa_system": {"parent": "block/cube_bottom_top", "textures": {"side": "pridechat:blocks/pa_system_front", "top": "pridechat:blocks/device_side", "bottom": "pridechat:blocks/device_side"}},
    "satellite_dish": {"parent": "block/block", "textures": {"particle": "pridechat:blocks/satellite_dish", "dish": "pridechat:blocks/satellite_dish", "base": "pridechat:blocks/satellite_base"},
        "elements": [box([2, 0, 2], [14, 3, 14], "#base", "#base", side_uv=[0, 0, 16, 3]),
                     box([7, 3, 7], [9, 10, 9], "#base", "#base", side_uv=[7, 0, 9, 7]),
                     box([1, 10, 1], [15, 12, 15], "#dish", "#base", "#base", side_uv=[0, 0, 16, 2]),
                     box([7, 12, 7], [9, 15, 9], "#base", "#base", side_uv=[7, 0, 9, 3])]},
    "telephone": {"parent": "block/block", "textures": {"particle": "pridechat:blocks/telephone_top", "top": "pridechat:blocks/telephone_top", "side": "pridechat:blocks/telephone_side", "dark": "pridechat:blocks/device_side"},
        "elements": [box([2, 0, 3], [14, 6, 13], "#top", "#side", "#dark", side_uv=[0, 0, 16, 7]),
                     box([2, 6, 6], [14, 8, 10], "#dark", "#dark", side_uv=[0, 0, 16, 2]),
                     box([1, 5, 5], [3, 9, 11], "#dark", "#dark", side_uv=[0, 0, 4, 4]),
                     box([13, 5, 5], [15, 9, 11], "#dark", "#dark", side_uv=[0, 0, 4, 4])]},
}
for b, m in BLOCKS.items():
    w(f"models/block/{b}.json", m)
    w(f"models/item/{b}.json", {"parent": f"pridechat:block/{b}"})
    w(f"blockstates/{b}.json", {"variants": {"normal": {"model": f"pridechat:{b}"}}})

def shaped(name, pattern, key, count=1):
    k = {}
    for c, it in key.items():
        if isinstance(it, tuple): k[c] = {"item": it[0], "data": it[1]}
        else: k[c] = {"item": it}
    w(f"recipes/{name}.json", {"type": "minecraft:crafting_shaped", "pattern": pattern, "key": k, "result": {"item": f"pridechat:{name}", "count": count}})

I, R_, T, N, G = "minecraft:iron_ingot", "minecraft:redstone", "minecraft:redstone_torch", "minecraft:noteblock", "minecraft:glass_pane"
shaped("walkie_talkie", [" T ", "IRI", "IBI"], {"T": T, "I": I, "R": R_, "B": "minecraft:stone_button"})
shaped("radio", ["ITI", "GNG", "IRI"], {"I": I, "T": T, "G": G, "N": N, "R": R_})
shaped("megaphone", ["  I", "INI", "  I"], {"I": I, "N": N})
w("recipes/radio_key.json", {"type": "minecraft:crafting_shapeless", "ingredients": [{"item": "minecraft:tripwire_hook"}, {"item": R_}, {"item": "minecraft:gold_nugget"}],
                              "result": {"item": "pridechat:radio_key"}})
shaped("room_marker", ["  P", " S ", "S  "], {"P": "minecraft:paper", "S": "minecraft:stick"})
shaped("radio_tower", [" T ", "IBI", "IBI"], {"T": T, "I": I, "B": "minecraft:iron_bars"})
shaped("satellite_dish", ["I I", " E ", "IRI"], {"I": I, "E": "minecraft:ender_eye", "R": R_})
shaped("telephone", ["SRS", "SNS"], {"S": ("minecraft:stone", 0), "R": R_, "N": N}, 2)
shaped("intercom", ["III", "INI", "IRI"], {"I": I, "N": N, "R": R_}, 2)
shaped("speaker", ["WWW", "WNW", "WRW"], {"W": ("minecraft:wool", 32767), "N": N, "R": R_})
shaped("pa_system", ["GNG", "NRN", "GNG"], {"G": "minecraft:gold_ingot", "N": N, "R": "minecraft:redstone_block"})

names = {"walkie_talkie": "Walkie-Talkie", "radio": "Radio", "megaphone": "Megaphone", "radio_key": "Radio Encryption Key", "room_marker": "Room Marker"}
blocks = {"radio_tower": "Radio Tower", "satellite_dish": "Satellite Dish", "telephone": "Telephone", "intercom": "Intercom", "speaker": "Speaker", "pa_system": "PA System"}
lines = ["itemGroup.pridechat=PrideChat"] + [f"item.pridechat.{k}.name={v}" for k, v in names.items()] + [f"tile.pridechat.{k}.name={v}" for k, v in blocks.items()]
with open(os.path.join(R, "lang/en_us.lang"), "w") as f: f.write("\n".join(lines) + "\n")
print("ok")
