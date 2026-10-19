# Privacy

Last updated: 19 October 2026

Caravan Leveler processes orientation measurements to calculate leveling
corrections. It does not contain advertising or analytics SDKs.

## Data processed

- Motion and orientation sensor readings.
- Calibration values and caravan dimensions.
- Selected sensor settings.
- Optional diagnostic snapshots containing sensor readings and timestamps, sensor
  identifiers, calibration values, dimensions, app version and calculated corrections.
- During BLE discovery and connection, nearby supported sensor names and device
  addresses.

## Storage and transmission

Settings and calibration values are stored locally on the Android device. The
app does not declare the Android internet permission and does not send this data
to a project-operated server. BLE measurements are exchanged locally between
the Android device and the selected sensor. Diagnostic snapshots are recorded only when requested in the Expert
settings section and are stored locally. Export creates a JSON file at the location
you choose; it includes sensor identifiers and calibration values.

Android and device vendors may independently process diagnostic or platform
data according to their own settings and policies.

## Permissions

`BLUETOOTH_SCAN` is used to find supported external sensors.
`BLUETOOTH_CONNECT` is used to connect to and communicate with a selected
sensor. The scan permission is declared with `neverForLocation`; the app does
not request location permission.

## Deleting data

Clear the app's storage in Android settings or uninstall the app to remove its
locally stored settings, calibration data and diagnostic snapshots. The Expert
section also lets you reset calibrations and delete locally recorded snapshots.
Exported files can be deleted separately at the chosen export location.

Questions about this policy can be submitted through the repository's issue
tracker. Do not post sensitive information in a public issue.
