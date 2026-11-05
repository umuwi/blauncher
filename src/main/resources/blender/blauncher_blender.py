# BLauncher: постройка из лаунчера как сцена Blender и обратно.
# Запускает лаунчер: blender --python blauncher_blender.py -- <папка сцены>
# В папке: scene.json (палитра с настоящей геометрией блоков), cells.bin (клетки), textures/*.png.
# Кнопка «Вернуть в BLauncher» (панель N → BLauncher) пишет export/build.obj и export/done.txt: лаунчер
# следит за этим файлом и переводит сцену обратно в блоки. Один файл и только то, что есть в Blender.
import array
import json
import os
import sys
import time

import bpy

TEXTS = {
    "ru": {"panel": "BLauncher", "send": "Вернуть в BLauncher", "sent": "Отправлено в лаунчер",
           "hint": "1 единица = 1 блок. Новые объекты станут блоками ближайшего цвета."},
    "en": {"panel": "BLauncher", "send": "Send back to BLauncher", "sent": "Sent to the launcher",
           "hint": "1 unit = 1 block. New objects become blocks of the nearest colour."},
}
SCENE_DIR = ""
TEXT = TEXTS["en"]


def load_scene(folder):
    with open(os.path.join(folder, "scene.json"), encoding="utf-8") as f:
        scene = json.load(f)
    cells = array.array("H")
    with open(os.path.join(folder, "cells.bin"), "rb") as f:
        cells.frombytes(f.read())
    if sys.byteorder != "little":
        cells.byteswap()
    return scene, cells


def to_blender(p):
    """В игре x на восток, y вверх, z на юг; в Blender z вверх."""
    return (p[0], -p[2], p[1])


def make_material(name, texture_file, tinted, shade):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    bsdf = nodes.get("Principled BSDF")
    bsdf.inputs["Roughness"].default_value = 1.0
    if "Specular IOR Level" in bsdf.inputs:
        bsdf.inputs["Specular IOR Level"].default_value = 0.0
    if not texture_file or not os.path.isfile(texture_file):
        bsdf.inputs["Base Color"].default_value = (0.5, 0.5, 0.5, 1)
        return mat
    node = nodes.new("ShaderNodeTexImage")
    node.image = bpy.data.images.load(texture_file, check_existing=True)
    node.interpolation = "Closest"   # пиксели остаются квадратными
    color = node.outputs["Color"]
    if tinted:
        # Трава и листва в файле серые: игра красит их сама.
        mix = nodes.new("ShaderNodeVectorMath")
        mix.operation = "MULTIPLY"
        mix.inputs[1].default_value = (0.42, 0.68, 0.34)
        mat.node_tree.links.new(color, mix.inputs[0])
        color = mix.outputs[0]
    mat.node_tree.links.new(color, bsdf.inputs["Base Color"])
    mat.node_tree.links.new(node.outputs["Alpha"], bsdf.inputs["Alpha"])
    mat.blend_method = "CLIP"
    return mat


def build(folder):
    scene, cells = load_scene(folder)
    w, h, l = scene["w"], scene["h"], scene["l"]
    palette = scene["palette"]
    full = [bool(p.get("full")) for p in palette]
    # Вершины и грани, собранные по материалу: один объект на текстуру, иначе тысячи объектов.
    groups = {}
    neighbours = {(1, 0, 0): (1, 0, 0), (-1, 0, 0): (-1, 0, 0), (0, 1, 0): (0, 1, 0),
                  (0, -1, 0): (0, -1, 0), (0, 0, 1): (0, 0, 1), (0, 0, -1): (0, 0, -1)}
    prepared = []
    for p in palette:
        quads = []
        for q in p.get("quads", []):
            pos = q["pos"]
            corners = [pos[0:3], pos[3:6], pos[6:9], pos[9:12]]
            side = None
            for axis in range(3):
                vals = [c[axis] for c in corners]
                if max(vals) - min(vals) < 1e-4 and (vals[0] < 1e-4 or vals[0] > 1 - 1e-4):
                    d = [0, 0, 0]
                    d[axis] = 1 if vals[0] > 0.5 else -1
                    side = tuple(d)
            quads.append((corners, q["uv"], (q["tex"], bool(q.get("tint")), q.get("shade", 1.0)), side))
        prepared.append(quads)

    for y in range(h):
        for z in range(l):
            row = (y * l + z) * w
            for x in range(w):
                id_ = cells[row + x]
                if id_ == 0:
                    continue
                for corners, uv, key, side in prepared[id_]:
                    if side is not None:
                        nx, ny, nz = x + side[0], y + side[1], z + side[2]
                        if 0 <= nx < w and 0 <= ny < h and 0 <= nz < l and full[cells[(ny * l + nz) * w + nx]]:
                            continue   # грань прижата к целому блоку: её не видно
                    g = groups.setdefault(key, ([], [], []))
                    base = len(g[0])
                    verts = [(x + c[0], y + c[1], z + c[2]) for c in corners]
                    # Нормаль наружу: лаунчер по ней поймёт, в какой клетке грань, когда сцена вернётся.
                    ax, ay, az = verts[1][0] - verts[0][0], verts[1][1] - verts[0][1], verts[1][2] - verts[0][2]
                    bx, by, bz = verts[2][0] - verts[0][0], verts[2][1] - verts[0][1], verts[2][2] - verts[0][2]
                    nvx, nvy, nvz = ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx
                    cx = sum(v[0] for v in verts) / 4 - (x + 0.5)
                    cy = sum(v[1] for v in verts) / 4 - (y + 0.5)
                    cz = sum(v[2] for v in verts) / 4 - (z + 0.5)
                    order = [0, 1, 2, 3] if nvx * cx + nvy * cy + nvz * cz >= 0 else [3, 2, 1, 0]
                    g[0].extend(to_blender(verts[i]) for i in order)
                    g[1].append((base, base + 1, base + 2, base + 3))
                    g[2].extend((uv[i * 2], 1 - uv[i * 2 + 1]) for i in order)

    for obj in list(bpy.data.objects):
        bpy.data.objects.remove(obj)
    collection = bpy.data.collections.new("BLauncher")
    bpy.context.scene.collection.children.link(collection)
    materials = {}
    for key, (verts, faces, uvs) in groups.items():
        tex, tinted, shade = key
        mesh = bpy.data.meshes.new(tex or "block")
        mesh.from_pydata(verts, [], faces)
        layer = mesh.uv_layers.new(name="UVMap")
        for i, loop in enumerate(layer.data):
            loop.uv = uvs[mesh.loops[i].vertex_index]
        mat_key = (tex, tinted)
        if mat_key not in materials:
            file = os.path.join(folder, "textures", tex) if tex else ""
            materials[mat_key] = make_material(tex or "block", file, tinted, shade)
        mesh.materials.append(materials[mat_key])
        mesh.update()
        obj = bpy.data.objects.new(tex or "block", mesh)
        collection.objects.link(obj)
    return len(groups), sum(len(g[1]) for g in groups.values())


def send_back():
    out_dir = os.path.join(SCENE_DIR, "export")
    os.makedirs(out_dir, exist_ok=True)
    done = os.path.join(out_dir, "done.txt")
    if os.path.exists(done):
        os.remove(done)
    target = os.path.join(out_dir, "build.obj")
    bpy.ops.wm.obj_export(filepath=target, export_selected_objects=False, forward_axis="NEGATIVE_Z", up_axis="Y",
                          path_mode="ABSOLUTE", export_materials=True, apply_modifiers=True, export_uv=True)
    with open(done, "w", encoding="utf-8") as f:
        f.write(str(time.time()))


class BLAUNCHER_OT_send(bpy.types.Operator):
    bl_idname = "blauncher.send_back"
    bl_label = "Send back to BLauncher"

    def execute(self, context):
        send_back()
        self.report({"INFO"}, TEXT["sent"])
        return {"FINISHED"}


class BLAUNCHER_PT_panel(bpy.types.Panel):
    bl_label = "BLauncher"
    bl_idname = "BLAUNCHER_PT_panel"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "BLauncher"

    def draw(self, context):
        self.layout.operator("blauncher.send_back", text=TEXT["send"], icon="EXPORT")
        for line in TEXT["hint"].split(". "):
            self.layout.label(text=line)


FRAME_TRIES = [12]


def frame_view():
    """В окне Blender: текстуры без тяжёлого рендера и вся постройка в кадре. Окно появляется не сразу: пробуем ещё."""
    done = False
    for window in bpy.context.window_manager.windows:
        for area in window.screen.areas:
            if area.type != "VIEW_3D":
                continue
            space = area.spaces.active
            space.shading.type = "SOLID"
            space.shading.color_type = "TEXTURE"
            space.shading.light = "FLAT"
            space.clip_end = 5000
            space.show_region_ui = True
            for region in area.regions:
                if region.type != "WINDOW" or region.width < 50:
                    continue
                try:
                    with bpy.context.temp_override(window=window, area=area, region=region, space_data=space):
                        bpy.ops.view3d.view_all(center=False)
                    done = True
                except Exception as e:
                    print("[BLAUNCHER] view:", e, flush=True)   # вид не главное: сцена уже на месте
    FRAME_TRIES[0] -= 1
    # Второй раз тоже: первая попытка часто попадает в окно, у которого ещё нет настоящего размера.
    return None if FRAME_TRIES[0] <= 0 else (1.0 if not done or FRAME_TRIES[0] > 10 else None)


def main():
    global SCENE_DIR, TEXT
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if not argv:
        print("[BLAUNCHER] no scene folder")
        return
    SCENE_DIR = argv[0]
    with open(os.path.join(SCENE_DIR, "scene.json"), encoding="utf-8") as f:
        TEXT = TEXTS.get(json.load(f).get("lang"), TEXTS["en"])
    bpy.utils.register_class(BLAUNCHER_OT_send)
    bpy.utils.register_class(BLAUNCHER_PT_panel)
    started = time.time()
    objects, faces = build(SCENE_DIR)
    print("[BLAUNCHER] built objects=%d faces=%d sec=%.1f" % (objects, faces, time.time() - started), flush=True)
    if not bpy.app.background:
        bpy.app.timers.register(frame_view, first_interval=0.5)


main()
