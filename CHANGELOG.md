# Changelog

Notable changes to public Caravan Leveler releases are documented here.

## Unreleased — Initial public release

- Add Expert settings to inspect, edit and reset device/selected-sensor calibration.
- Add local sensor snapshots with JSON export for reproducing calibration and
  leveling errors, including settings, timestamps and calculated corrections.

- Fix BLE vehicle calibration rotating subsequent tilt corrections into the wrong axes
  when the phone and external sensor report different headings. Existing vehicle
  calibrations must be repeated once; device calibration is retained.
- Add simulated WT901BLECL integration tests covering notification decoding, all
  24 axis mappings, calibration, persistence and sensor selection.
- First public version of the project.
- Includes built-in and supported BLE orientation sensors, guided calibration,
  3D leveling visualization, correction values, and English/German interfaces.
