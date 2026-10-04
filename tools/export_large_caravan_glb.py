"""Export the CC-BY Large Caravan Blender source as the Android GLB asset.

Run with Blender, for example:

    blender -b 'app/blend/large-caravan/source/Large Caravan.blend' \
        --python tools/export_large_caravan_glb.py -- \
        --output app/src/main/assets/caravan.glb

The GLB exporter embeds the model's textures, so the Android app only needs
the resulting single binary asset.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import bpy
from mathutils import Quaternion, Vector


def convert_legacy_materials() -> None:
    """Convert the source file's legacy Diffuse BSDF materials to PBR nodes.

    Blender's glTF exporter does not export images connected to a legacy
    Diffuse BSDF node. Preserve those image nodes and connect them to a
    Principled BSDF so the resulting GLB embeds the source textures.
    """
    for material in bpy.data.materials:
        if not material.use_nodes:
            continue

        nodes = material.node_tree.nodes
        links = material.node_tree.links
        image_nodes = [node for node in nodes if node.type == "TEX_IMAGE" and node.image]
        diffuse = next((node for node in nodes if node.type == "BSDF_DIFFUSE"), None)
        output = next((node for node in nodes if node.type == "OUTPUT_MATERIAL"), None)
        if not output:
            continue

        principled = next((node for node in nodes if node.type == "BSDF_PRINCIPLED"), None)
        if not principled:
            principled = nodes.new("ShaderNodeBsdfPrincipled")
            principled.location = (output.location.x - 260, output.location.y)

        if image_nodes:
            image = image_nodes[0]
            for link in list(principled.inputs["Base Color"].links):
                links.remove(link)
            links.new(image.outputs["Color"], principled.inputs["Base Color"])
        elif diffuse:
            principled.inputs["Base Color"].default_value = diffuse.inputs["Color"].default_value

        # The source images are JPEGs without meaningful alpha. Connecting
        # their generated Alpha output makes glTF mark every textured surface
        # as BLEND, including the caravan shell.
        for link in list(principled.inputs["Alpha"].links):
            links.remove(link)
        principled.inputs["Alpha"].default_value = 1.0
        links.new(principled.outputs["BSDF"], output.inputs["Surface"])


def orient_for_app() -> None:
    """Match the axis convention used by the previous app model.

    The app expects X=width, Y=length and Z=height. After loading the source
    blend, its evaluated axes are X=length, Y=height and Z=width.
    """
    caravan = bpy.data.objects.get("Large Caravan")
    if caravan is None:
        raise RuntimeError("Large Caravan root object not found")

    current_rotation = caravan.rotation_euler.to_quaternion()
    # Account for Blender's Y-up glTF conversion while retaining a right-handed
    # result: width on X, tow hitch toward +Y and height toward +Z.
    axis_rotation = Quaternion((0.5, 0.5, -0.5, 0.5))
    caravan.rotation_mode = "QUATERNION"
    caravan.rotation_quaternion = axis_rotation @ current_rotation


def center_origin_on_axle() -> None:
    """Place the model origin at the midpoint of the main wheel axle."""
    caravan = bpy.data.objects.get("Large Caravan")
    wheels = bpy.data.objects.get("Rear Wheels")
    if caravan is None:
        raise RuntimeError("Large Caravan root object not found")
    if wheels is None:
        raise RuntimeError("Rear Wheels object not found")
    if caravan.parent is not None:
        raise RuntimeError("Large Caravan root object unexpectedly has a parent")

    # Rear Wheels contains both main wheels. Its world-space bounding-box center
    # is therefore the point halfway between them and at the axle's height.
    bpy.context.view_layer.update()
    corners = [wheels.matrix_world @ Vector(corner) for corner in wheels.bound_box]
    lower = Vector(min(corner[axis] for corner in corners) for axis in range(3))
    upper = Vector(max(corner[axis] for corner in corners) for axis in range(3))
    axle_center = (lower + upper) / 2.0

    caravan.location -= axle_center
    bpy.context.view_layer.update()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    script_args = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    args = parser.parse_args(script_args)

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    convert_legacy_materials()
    orient_for_app()
    center_origin_on_axle()
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.export_scene.gltf(
        filepath=str(output),
        export_format="GLB",
        use_selection=False,
        export_apply=True,
        export_texcoords=True,
        export_normals=True,
        export_materials="EXPORT",
        export_image_format="AUTO",
        export_animations=True,
    )
    print(f"Exported CC-BY caravan model: {output}")


if __name__ == "__main__":
    main()
