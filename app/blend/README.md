# Caravan model

The app uses the **Large Caravan** model by **Robin Hayward (Maxton
Hardrive)**, published by [rhcreations on
Sketchfab](https://sketchfab.com/3d-models/bc096d0ad5c64ac1a48647200fd559c2)
under the [Creative Commons Attribution 4.0
license](https://creativecommons.org/licenses/by/4.0/).

The generated `app/src/main/assets/caravan.glb` is committed to this repository
and contains all required textures. Building the Android app therefore does not
require Blender or the original model archive.

## Re-exporting the model

The original Blender file and textures are not redistributed in this
repository. Download the original archive from the
[Sketchfab model page](https://sketchfab.com/3d-models/bc096d0ad5c64ac1a48647200fd559c2)
and extract it below this directory so that the Blender file is located at:

```text
app/blend/large-caravan/source/Large Caravan.blend
```

With Blender installed, regenerate the committed GLB from the repository root:

```shell
blender -b 'app/blend/large-caravan/source/Large Caravan.blend' \
    --python tools/export_large_caravan_glb.py -- \
    --output app/src/main/assets/caravan.glb
```

The export script converts the legacy materials, embeds the source textures,
matches the app's axis convention, and places the model origin at the main
wheel axle. The downloaded archive and extracted sources are ignored by Git.

When redistributing the GLB outside this repository, retain the model name,
creator attribution, source link, and CC BY 4.0 license notice.
