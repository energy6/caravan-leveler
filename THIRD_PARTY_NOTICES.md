# Third-party notices

Caravan Leveler is distributed under GPL-3.0-only, but the following bundled
assets and components retain their respective licenses and terms.

## Large Caravan 3D model

- **Work:** Large Caravan
- **Creator:** Robin Hayward (Maxton Hardrive), published by rhcreations
- **Source:** <https://sketchfab.com/3d-models/bc096d0ad5c64ac1a48647200fd559c2>
- **License:** Creative Commons Attribution 4.0 International (CC BY 4.0)
- **License text:** <https://creativecommons.org/licenses/by/4.0/legalcode>
- **Bundled derivative:** `app/src/main/assets/caravan.glb`

The source model was converted to GLB. Its legacy materials were converted,
textures were embedded, axes were adapted to the app's coordinate convention,
and the origin was moved to the main wheel axle. See `app/blend/README.md` for
re-export instructions.

## Apache License 2.0 components

The project directly uses Apache-2.0-licensed components including:

- AndroidX libraries.
- Material Components for Android.
- Dagger and Hilt.
- Kotlin and kotlinx.coroutines.
- Apache Commons Math.
- Sceneform maintained by the SceneView community.
- The Gradle wrapper scripts and supporting Gradle components.

Exact versions are declared in `build.gradle` and `app/build.gradle`. The
Apache License 2.0 text is included at `LICENSES/Apache-2.0.txt`.

## Google ARCore SDK

The build includes `com.google.ar:core`. Use and redistribution of that SDK are
subject to the
[ARCore Additional Terms of Service](https://developers.google.com/ar/develop/terms)
identified by its published Maven metadata.

## Transitive dependencies

The Android build resolves additional transitive components from Google Maven
and Maven Central. Those components remain under their own licenses and notices.
Before distributing a release, review the resolved dependency graph and the
license metadata bundled with the selected versions.

Test-only dependencies are used to develop and verify the project and are not
packaged in the release APK.
