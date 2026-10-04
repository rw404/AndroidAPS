# Native Healfi screenshots

These are raw Android screenshots, captured with `adb screencap` and no image
postprocessing. The isolated Android 15/API 35 x86_64 emulator uses software TCG
without KVM, at 720 × 1440 pixels and 320 dpi. Its timing does not represent
OnePlus performance.

- [Before: light overview](healfi-before-native.png), source `7545cba1bf53ea85f4aa04a7cda2a6b9818d2b70`.
- [After: dark overview](healfi-after-dark-native.png), source `f54bf1b761acb1e9ac77282a159a19d798ae2108`.

The screenshots show synthetic glucose readings and an isolated test profile;
VirtualPump is selected and the loop is disabled. The yellow data-quality
indicator reflects the gap in this test history; the red loop indicator shows
the disabled loop. Neither warning was removed for a screenshot. No physical
sensor, radio bridge or pump is connected. No insulin was delivered.

The captured build is `healfiDebug`, signed with the environment debug key.
The signed Actions release is verified separately. The [native design guide](../../docs/healfi-native-design.md)
describes the layout and the remaining pages that retain their original structure.

## Final normal comparison

- [After: light overview](healfi-after-light-native.png).
- [All three metric cards at 360 dp](f54-light-metrics-settled.png).
- [Whole glucose 121 at landscape 200% after scroll](f54-font200-land-bg.png).
- [Landscape 200% axis via native accessibility scroll](f54-font200-land-axis-native.png).
- [Portrait 200%: scoped axes/metric/button evidence](f54-font200-portrait.png).

[The evidence report](../../docs/verification/healfi-native-ui-2026-10-04.json)
records hashes, fixture adjustments, source commits and what was not verified.
Portrait 200% has touching outer time ticks. Full portrait 200% glucose was not
verified; normal touch scrolling in landscape 200% was inconclusive on TCG.
The final metric screenshot preserves the actual unavailable COB value (n/a).
The database snapshot contains 45 synthetic readings, zero boluses and zero
carbohydrate events. Insulin/food dialog captures are from intermediate `3b3b7f7`,
separately identified in the report; final layout captures are from `f54bf1b`.
