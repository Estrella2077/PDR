# Latest Update

Updated: 2026-04-27 17:44:26

## This turn

The user asked for a project `.md` document to serve as the basis for a PPT, with the overall presentation rhythm roughly matching `D:\大学\位置服务与应用\第4小组(1).pptx`, but without generating the PPT itself.

Touched files:

- `PPT汇报文稿.md`
- `review/LATEST_UPDATE.md`

Work completed:

- read the local `pptx` skill instructions because the task referenced a `.pptx` file
- inspected the reference presentation structure indirectly using existing extracted materials under `review/pdf_extract/`
- attempted `python -m markitdown "D:\大学\位置服务与应用\第4小组(1).pptx"` but local `markitdown` was not installed, so fell back to local extracted presentation text already present in the repo context
- reviewed the current project architecture and algorithm chain from `MainActivity.kt`, `PdrProcessor.kt`, `AhrsEstimator.kt`, `ImportedTrackParser.kt`, and `CsvSessionWriter.kt`
- created a slide-oriented markdown draft at `PPT汇报文稿.md`

Document characteristics:

- organized for direct PPT拆页 rather than for developer documentation
- keeps the same broad course-report flow as the reference presentation:
  - 软件介绍
  - 传感器采集
  - 实时 PDR
  - 结果与总结
- reflects the current project state, including:
  - 九轴 AHRS
  - 磁偏角修正
  - 实时地图显示
  - 数据保存与离线后处理
  - current `PDR-main` step-length formula path
  - configurable `stepLengthScale`

Remaining risks:

- the reference `pptx` text extraction available in the repo is partially garbled, so the new markdown matches its structure and reporting rhythm rather than reproducing its wording
- the markdown is ready for PPT production, but it does not yet include actual screenshots or experiment figures

Next step:

- if needed, refine `PPT汇报文稿.md` into a shorter答辩版, a longer课程汇报版, or a version with explicit “每页标题 + 讲稿提示”

## Prior turn

The user asked me to build a shareable APK after exposing `stepLengthScale` in the settings page.

Touched files:

- `review/LATEST_UPDATE.md`

Build result:

- built debug APK successfully with `:app:assembleDebug`
- output file: `app/build/outputs/apk/debug/app-debug.apk`
- output size: `47,629,710` bytes

Verification:

- Gradle task `:app:assembleDebug` completed successfully using `D:\Android Studio\Android Studio\jbr`

Notes:

- this is a debug-signed APK and can be installed manually on other Android phones
- if the target phone already has the same package installed from a different signature, Android will require uninstalling the old app first

Next step:

- send `app/build/outputs/apk/debug/app-debug.apk` to the target phone and install it from the file manager

## Prior turn

The user asked to expose `stepLengthScale` on the settings page under `PDR 模型`, while keeping the app in the current state of git-baseline behavior plus only the retained `PDR-main` step-length formula path.

Touched files:

- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `app/src/main/java/com/example/imupdr/CsvSessionWriter.kt`
- `app/src/main/java/com/example/imupdr/ImportedTrackParser.kt`
- `app/src/main/java/com/example/imupdr/PdrModelPreset.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`
- `review/LATEST_UPDATE.md`

Behavior changes:

- settings page `PDR 模型` now includes a visible `stepLengthScale` input alongside height
- the apply button now stores and restores both height and step-length scale from shared preferences
- live collection now writes `step_length_scale` into CSV metadata so saved sessions carry the exact tuning value used during recording
- offline import now reads `step_length_scale` from file metadata, with current UI value as fallback, so imported replay uses the same step-length scaling logic
- `createHeightModelConfig(...)` now explicitly accepts `stepLengthScale` and clamps it to `0.30..1.50`

Non-changes:

- no heading, compass, arrow-rotation, AHRS, or direction-judgment logic was changed in this turn
- the retained `PDR-main` step-length formula in `PdrProcessor.kt` remains the only step-length computation path change

Verification:

- `:app:compileDebugKotlin` completed successfully using `D:\Android Studio\Android Studio\jbr`

Remaining risks:

- `0.67` is still an empirical default for the current phone comparison and may need per-device tuning
- old CSV files that do not contain `step_length_scale` will still import, but they will use the current settings-page value as fallback

Next step:

- install this build, adjust `步长缩放` in the settings page, and compare the same route against `PDR-main` until the total distance aligns
## Prior turn

The user reported that on another phone the direction problem disappeared, but with the `PDR-main` step-length formula the same route was still about 1.5 times longer than `PDR-main`.

Touched files:

- `app/src/main/java/com/example/imupdr/PdrModelPreset.kt`
- `review/LATEST_UPDATE.md`

Findings:

- the app remains in the requested state: git-baseline behavior plus only the `PDR-main` step-length-formula change
- a route that is about `1.5x` too long is most directly compensated by scaling step length by about `1 / 1.5 = 0.67`
- updated the hidden `stepLengthScale` default from `1.0` to `0.67` for the existing presets
- this change does not alter step detection, cadence estimation, heading, or display behavior; it only shortens the final step length produced by the retained `PDR-main` formula path

Verification:

- `:app:compileDebugKotlin` completed successfully using `D:\Android Studio\Android Studio\jbr`

Remaining risks:

- the static-arrow issue is intentionally not addressed in this state because the user requested git-baseline behavior plus only the step-length-formula change
- real-device validation is still required to confirm whether `0.67` is the right compensation factor on the user's new phone
- the hidden `stepLengthScale` is present in the model config, but no UI is exposed for tuning it yet

Next step:

- install this build and compare the same route again against `PDR-main`; if needed, continue tuning `stepLengthScale` around `0.60` to `0.75`

## Prior turn

The user asked how step frequency is computed in this project and in `D:\Android Studio\AndroidStudioProjects\PDR-main`.

The user asked for the step-length algorithm in this project and in `D:\Android Studio\AndroidStudioProjects\PDR-main`.

The user asked me to read the `review/` directory.

The user asked where to find the saved sensor data files on the phone.

Touched files:

- `review/LATEST_UPDATE.md`

Findings:

- saved sessions are written under the app-specific external documents directory with subfolder `imu_pdr_sessions`
- the expected Android path is `Android/data/com.example.imupdr/files/Documents/imu_pdr_sessions/<timestamp>/`
- each session directory contains `imu_raw.csv` and `pdr_steps.csv`
- on many phones, regular file managers hide `Android/data`, so Android Studio Device Explorer or a file manager with app-specific directory access may be needed

Verification:

- conclusion is based on the existing `CsvSessionWriter.kt` path logic already inspected in this session
- no code behavior changed in that turn

Remaining risks:

- vendor file managers differ; some devices block browsing `Android/data` from the stock file app

Next step:

- if needed, guide the user through locating the folder with Android Studio Device Explorer or importing the CSV back into the app

## Earlier turn

The user asked me to read the `review/` directory before continuing.

Touched files:

- `review/LATEST_UPDATE.md`

Findings:

- `review/CONTEXT.md` confirms this repo is the independent working project and should not write back to `PDR-main` or `SensorCollectorWatch-main`
- `review/CURRENT_STATUS.md` confirms the app currently includes map, sensor, reference, and settings pages plus CSV save/import and offline post-processing
- `review/KEY_FILES.md` confirms the main logic is centered in `MainActivity.kt`, with PDR in `PdrProcessor.kt`, AHRS in `AhrsEstimator.kt`, import parsing in `ImportedTrackParser.kt`, and CSV persistence in `CsvSessionWriter.kt`
- `review/NEXT_STEPS.md` shows real-device validation and parameter tuning remain the highest-priority follow-up
- `review/REVIEW_POLICY.md` confirms `review/` should be updated after each substantive conversation turn

Verification:

- read and re-read the review files with UTF-8 decoding to avoid PowerShell console mojibake
- no code behavior changed in this turn

Remaining risks:

- the review summary is now loaded into context, but real-device behavior still needs validation for heading stability and forward-track consistency

Next step:

- continue with the user's next requested inspection or code change using the loaded `review/` context

## Prior turn

The user reported that after heading and arrow rotation looked correct during collection, walking forward still produced a trajectory that moved backward.

Touched files:

- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `review/LATEST_UPDATE.md`

Root cause:

- the previous `180°` correction was applied only in the display layer so the arrow looked right
- the actual heading value used by step integration remained inverted
- that meant map display and trajectory propagation were no longer using the same physical forward direction

Behavior changes:

- `MainActivity.kt`
  - the real heading source now gets the `180°` correction at attitude computation time
  - the map marker display no longer applies a separate extra `180°` compensation
  - display heading and trajectory heading are now aligned to the same corrected forward direction

Verification:

- `:app:compileDebugKotlin` completed successfully using Android Studio bundled JBR

Remaining risks:

- code-level consistency is restored, but a short real walking test is still needed to confirm forward walking now advances the trajectory forward under the user's actual phone-holding posture

Next step:

- have the user walk straight forward for several meters and verify that both arrow direction and track advance direction remain consistent before and after pressing `开始采集`

## Prior turn

The user reported a state-switch bug: after app launch the arrow direction and rotation were correct, but clicking `开始采集` immediately rotated the arrow by about `180°`.

Touched files:

- `app/src/main/java/com/example/imupdr/PdrProcessor.kt`
- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `review/LATEST_UPDATE.md`

Root cause:

- the app was using two different heading sources
- before collection, the map arrow used the continuously running display-side AHRS heading
- after collection started, the app switched to `PdrProcessor`'s freshly initialized heading state
- that source switch caused the apparent instant `180°` flip when entering collection mode

Behavior changes:

- `PdrProcessor.kt`
  - added `setExternalAttitude(...)` so PDR can consume a caller-provided heading/pitch/roll
  - `updateAttitude()` now prefers the externally synchronized attitude when it is available
- `MainActivity.kt`
  - the display AHRS attitude is now synchronized into `PdrProcessor` during collection
  - collection startup explicitly seeds PDR with the already-correct display attitude

Verification:

- `:app:compileDebugKotlin` completed successfully using Android Studio bundled JBR

Remaining risks:

- the heading-source split is fixed for collection startup, but real-device validation is still needed to confirm there is no visible jump after longer runs or stop/start cycles

Next step:

- have the user verify that arrow heading stays continuous across the `未采集 -> 开始采集 -> 停止采集` transitions

## Prior turn

The user reported that arrow rotation direction is now correct, but the displayed arrow still points about `180°` away from the real direction.

Touched files:

- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `review/LATEST_UPDATE.md`

Root cause:

- after fixing rotation direction, the remaining issue was a pure display-phase heading offset
- the current marker asset and Baidu marker rotation reference still required an additional `180°` display compensation

Behavior changes:

- `MainActivity.kt`
  - map marker heading display now applies a `180°` offset on top of the corrected rotation sign

Verification:

- `:app:compileDebugKotlin` completed successfully using Android Studio bundled JBR

Remaining risks:

- this should fix the remaining display inversion, but final confirmation still depends on a real-device heading check

Next step:

- have the user verify one static heading plus one full turn to confirm both absolute direction and rotation direction are now correct

## Prior turn

The user confirmed the large heading offset is mostly fixed, but the arrow rotation direction on the map is still opposite to the phone.

Touched files:

- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `review/LATEST_UPDATE.md`

Root cause:

- after the heading convention fix, the remaining mismatch was isolated to Baidu marker display rotation direction
- the heading value itself was now close to correct, but UI rotation still needed the opposite sign for this SDK display path

Behavior changes:

- `MainActivity.kt`
  - map marker rotation sign was flipped back so the arrow turns in the same direction as the phone

Verification:

- `:app:compileDebugKotlin` completed successfully using Android Studio bundled JBR

Remaining risks:

- numeric heading is now close to correct and display rotation sign has been adjusted, but a short real-device check is still needed to confirm both stay correct during continuous turns

Next step:

- have the user do a slow clockwise and counterclockwise in-place turn and confirm the arrow now follows in the same direction throughout

## Prior turn

The user reported that heading was still wrong:

- approximately `90°` away from the real heading
- arrow rotation moved opposite to the phone
- usage posture is mainly face-up portrait

Touched files:

- `app/src/main/java/com/example/imupdr/AhrsEstimator.kt`
- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `review/LATEST_UPDATE.md`

Root cause:

- the AHRS quaternion path was internally using a standard yaw convention closer to an `x-axis forward` body frame
- Android phone heading in face-up portrait is naturally interpreted from the device `y-axis` (top edge) as azimuth
- that convention mismatch introduced a combined `90°` offset and reversed-turn behavior in UI heading usage
- the map marker rotation still used a negated angle, which further flipped the displayed arrow direction

Behavior changes:

- `AhrsEstimator.kt`
  - quaternion initialization now converts Android azimuth into the estimator's internal standard-yaw convention before seeding the quaternion
  - quaternion snapshot now converts internal standard yaw back into Android-style azimuth before exposing heading to the rest of the app
- `MainActivity.kt`
  - map arrow rotation now uses the corrected heading directly instead of negating it

Verification:

- `:app:compileDebugKotlin` completed successfully using Android Studio bundled JBR

Remaining risks:

- this fix corrects the convention mismatch in code, but a real-device check is still needed to confirm Baidu marker rotation matches the corrected azimuth exactly on the target phone
- if the physical device sensor stack has manufacturer-specific axis quirks, a small residual offset may still need one final calibration tweak

Next step:

- have the user verify four headings in place: north, east, south, west, and report whether any residual offset is constant or direction-dependent

## Earlier turn

The user explicitly requested that `review/` be updated after every conversation.

Touched files:

- `review/REVIEW_POLICY.md`
- `review/LATEST_UPDATE.md`

Behavior/process changes:

- the review workflow is now explicitly defined as a per-conversation requirement, not only a code-change follow-up task
- future turns should update `review/` before replying, even when the main outcome is analysis, status, or process guidance

Verification:

- policy text updated successfully
- no app code changed in this turn

Remaining risks:

- this turn only updated the review policy and latest log; broader review summaries were not rewritten because project behavior did not change

Next step:

- continue appending a fresh review entry at the end of each subsequent conversation turn

## Earlier turn

The map's current-person marker was changed from a circular dot to a directional arrow so heading is easier to read visually.

Additional touched files:

- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `app/src/main/res/drawable/map_arrow_marker.xml`

Additional behavior changes:

- current map position now uses a `MarkerOptions` arrow icon instead of `DotOptions`
- the arrow rotates with the current PDR heading when heading is ready
- the anchor point remains a dot so it is still visually distinct from the live position

Additional verification:

- `assembleDebug` completed successfully after the arrow-marker change

## Crash follow-up

After the arrow-marker change, the user reported that pressing `开始采集` caused an app crash.

Likely cause:

- the previous implementation used `BitmapDescriptorFactory.fromResource(R.drawable.map_arrow_marker)` directly on a vector drawable resource
- that path compiles, but can fail at runtime when Baidu Map creates the overlay

Additional touched files:

- `app/src/main/java/com/example/imupdr/MainActivity.kt`

Additional behavior changes:

- arrow marker creation now renders the drawable into a `Bitmap` first and then calls `BitmapDescriptorFactory.fromBitmap(...)`
- this avoids direct runtime decoding of the vector resource by the map SDK

Additional verification:

- `assembleDebug` completed successfully after the bitmap-descriptor fix

## This turn

The user asked whether the current single-PDR implementation follows the algorithm in `位置服务与实践-课程汇报ppt.pdf`, especially initialization, and pointed out two observed issues:

- single PDR heading is unstable, especially at the initial moment
- the map page does not keep running before data collection starts

The user also asked to use `D:\Android Studio\AndroidStudioProjects\PDR-main` as a reference.

## Findings

The current project was only partially aligned with the PDF algorithm.

- The PDF requires initial heading to be established while the device is static, using horizontal attitude, horizontal magnetic output, magnetic north heading, and local declination to obtain an initial true-north heading.
- The previous implementation initialized attitude immediately after the first available `accelerometer + magnetometer` pair.
- The previous implementation did not include a restart-like heading correction path after walking interruption.
- `PDR-main` keeps heading more stable because its heading path is continuously smoothed from `acc + mag`, while the current project had a more abrupt startup path.
- `PDR-main` map behavior before collection comes from map and location services being active independently of PDR collection state.

## Code changes

Touched files:

- `app/src/main/java/com/example/imupdr/AhrsEstimator.kt`
- `app/src/main/java/com/example/imupdr/PdrProcessor.kt`
- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `review/pdf_extract/pdr_algorithm_notes.md`

Behavior changes:

- `AhrsEstimator.kt`
  - added a static-enough warmup window before initial AHRS alignment
  - initialization now waits for stable `acc + mag + gyro` observations and uses averaged samples
- `PdrProcessor.kt`
  - enabled heading smoothing through the existing `headingWeight` parameter
- `MainActivity.kt`
  - GNSS/location services now stay active with the map page even when collection is not running
  - `PDR` mode display now falls back to GNSS position when no anchor or PDR point is available yet

## Verification

- PDF text extraction completed and saved under `review/pdf_extract/`
- `assembleDebug` completed successfully after setting `JAVA_HOME` to Android Studio bundled JBR for the command session
- No real-device walk test has been run after this change yet

## Remaining risks

- Interruption-triggered heading reinitialization from the PDF is still not implemented
- Initialization thresholds may still need per-device tuning
- Real-device validation is still required for startup heading stability

## Follow-up

Recommended next implementation step:

1. Add interruption detection and magnetic heading reinitialization when step gaps exceed the configured threshold.
