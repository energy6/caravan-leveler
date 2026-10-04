# Caravan Leveler

<p align="center">
  <img src="app/svg/caravan-leveler.svg" width="128" alt="Caravan Leveler icon">
</p>

Caravan Leveler is an Android app that visualizes a caravan's orientation and
calculates the height corrections required at the wheels and stabilizers. It
can use the phone's built-in motion sensors or a supported Bluetooth Low Energy
(BLE) orientation sensor.

## Features

- Live 3D visualization of caravan pitch and roll.
- Wheel-to-wheel and axle-to-jockey-wheel correction values.
- Guided calibration for the Android device and an installed vehicle sensor.
- Built-in sensor support without additional hardware.
- BLE support for the WITMOTION WT901BLECL sensor.
- Configurable sensor-axis mapping and caravan dimensions.
- Optional compass display, light/dark themes, and themed launcher icon.
- English and German user interfaces.

## Requirements

### Device

- Android 14 (API 34) or newer.
- OpenGL ES 3.0.
- Bluetooth Low Energy hardware.
- Suitable motion sensors when using the built-in sensor mode.

The manifest currently marks BLE and OpenGL ES 3.0 as required, even when the
built-in orientation sensor is selected.

### Build environment

- JDK 17.
- Android SDK 37.
- Git, including release tags.

The Gradle wrapper downloads Gradle 9.6 automatically. Dependency downloads
require access to Google Maven and Maven Central.

## Building

Clone the repository including its tags, then run:

```shell
./gradlew assembleDebug
```

The debug APK is written below `app/build/outputs/apk/debug/`.

The version name and code are derived from `git describe`. A build directly
from a semantic version tag such as `v1.2.3` uses that tag unchanged. Later
commits increment the patch component and add their distance and abbreviated
commit ID, for example `v1.2.4-p3+g1a2b3c4`.

Run the local verification suite with:

```shell
./gradlew testDebugUnitTest assembleDebug lintDebug
```

## Release signing

Copy `keystore.properties.example` to `keystore.properties` and configure the
keystore path and alias. Keep `keystore.properties` and the keystore private.
Passwords can be placed in that local file or supplied through
`LEVELER_STORE_PASSWORD` and `LEVELER_KEY_PASSWORD`.

Create a signed release APK with:

```shell
./gradlew assembleRelease
```

## Basic use

1. Enter the caravan width and the distance from axle to jockey wheel.
2. Select the built-in sensor or scan for a supported BLE sensor.
3. Configure the sensor axes so they match the caravan orientation.
4. Follow the calibration workflow on a firm, stable surface.
5. Place the calibrated device or sensor in its operating position and read
   the displayed corrections.

## Safety

This app is a measuring aid, not a safety device. Verify measurements before
lifting or stabilizing a caravan, follow the vehicle and equipment manuals,
chock the wheels, and never exceed the rated load of jacks or supports.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before opening a pull request.
Security issues should be reported as described in [SECURITY.md](SECURITY.md).

## Licensing

The project-authored source code and artwork are licensed under the
[GNU General Public License version 3 only](LICENSE).

Third-party components and assets retain their own licenses. In particular,
the bundled caravan model is licensed under CC BY 4.0. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for attribution and details.

Copyright © 2022–2026 Jonatan Antoni and contributors.
