# Stage 1 visual QA — SDM-007

Scope: repository-backed Downloads and Files, their empty states, zero dashboard values, and real device storage. This is not a whole-application pixel-perfect sign-off.

## Reference and capture

The unchanged source of truth is:

`/Users/espitman/Library/Application Support/Open Design/namespaces/release-stable/data/projects/13ab3c0f-1414-409f-934f-981d4a24c89f/index.html`

Device `30fe7f97` runs Android 14 at `1080×2400`, `440 dpi`: logical viewport `392.727273×872.727273 dp`, scale `2.75`, top system inset `30.545455 dp`, bottom inset `0`. The existing **Light & Gold** preference was preserved. The final reference uses the same light theme, viewport, inset, and embedded IBM Plex Sans font files. Prototype sample rows were removed in a disposable reference copy to expose its existing empty states; the original design was not edited.

Both final image sets are exactly `1080×2400`. Playwright's fractional CSS clip previously produced `1078×2398`. The corrected renderer captures the enclosing `1081×2401` viewport and crops one physical pixel from the right and bottom without resizing. Pixel comparison proved that the earlier common `1078×2398` area was unchanged: truncation was not the cause of the text displacement. Native system status-bar content is excluded from the design comparison.

## Corrections

- Reused one empty-state component with the reference's `16sp` IBM Plex Sans, bold title, regular description, `24.8sp` line height, `32dp` vertical padding, and `16dp` horizontal padding. The earlier description was incorrectly `11sp`.
- Rounded the combined line height and `5dp` interline margin once. At this density, the original independent native rounding produced an `83px` line advance; the corrected advance is `82px`, matching the reference's `81.941406px` advance rounded to physical pixels.
- Included the storage card's `1dp` border in its inner inset: CSS uses `18px` padding inside the border, while Compose draws its border inside the card. The resulting `19dp` inset removes the missing border contribution to the Files empty-state position.
- Replaced the storage legend's sample available space and usage percentage with values calculated from the same real `StatFs` used/total pair as the storage bar. Unavailable data displays `—`.

No screen-specific compensating translation was added. Header, display typography, navigation, splash, and motion were not changed.

## Device findings

All three Downloads filters show the correct reference titles (`No downloading downloads`, `No queued downloads`, `No completed downloads`) and `Finished files will appear here.` Files shows `No matching files` and `Try another search or file type.` No sample cards or sample file paths are visible.

The empty dashboard displays `0 active`, `0 MB/s`, `0 B` downloaded today, `0 B` active remaining, `0` connections, and `0 items`. Files displays live capacity/usage rather than prototype constants. At the earlier capture it showed `190.91 GB` used of `224.18 GB`, `33.28 GB available`, and `85% used`; these values can change between captures. A separate device `df` read confirmed the storage source, but exact equality is not claimed because sampling times and percentage rounding differ.

Source review found no caller of the retained private details prototype. The empty repository value renders the existing page structure with zero/unavailable values; it does not select a blank loading-screen branch. No new cold-start recording was made for this bounded review.

## Geometry verdict and limits

The avoidable empty-state typography, extra interline pixel, and Files border-box discrepancy are corrected. Final content and reference layout values pass this bounded review. The screenshots are **not byte-identical raster output**, and this report does not claim that the entire application matches the prototype pixel for pixel.

Measured final title line-box tops:

| Screen | Android physical y | Chromium physical y | Difference |
| --- | ---: | ---: | ---: |
| Downloads (all three filters) | 1241 | 1239.777344 | +1.222656 px |
| Files | 1095 | 1095.617188 | −0.617188 px |

These small container-coordinate differences remain from cumulative native integer layout rounding above the empty-state component. The component's spacing no longer introduces an extra interline pixel. Temporary native text instrumentation measured a `51px` first baseline in a `69px` paragraph. Chromium measured an `18 CSS px` first baseline (`49.5 physical px`) in its `24.796875 CSS px` line box. This platform font-metric difference, fractional glyph rasterization, and the measured container rounding account for the remaining approximately `2–3px` glyph-edge displacement; it is not the former incorrect font size, interline margin, or capture truncation. Temporary instrumentation was removed before the final build.

Pre-existing differences outside this change include the reference's Avenir Next display typography, some header details, and bottom-navigation rendering/shadow. This review does not mark those as resolved. Initial filter behavior remains intentionally unchanged for stage 1.

## Local evidence and validation

- Final device PNG/XML files: `/Users/espitman/Documents/AndroidApp/SDM/artifacts/actual/stage1-empty-final/`
- Matching reference PNG, geometry JSON, and baseline JSON: `/Users/espitman/Documents/AndroidApp/SDM/artifacts/reference/stage1-empty-light/`
- Reference renderer: `/Users/espitman/Documents/AndroidApp/SDM/artifacts/empty_state_reference_qa.cjs`
- Baseline measurement log: `/Users/espitman/Documents/AndroidApp/SDM/artifacts/actual/stage1-empty-final/native-text-metrics.log`
- Final build/lint log: `/Users/espitman/Documents/AndroidApp/SDM/artifacts/actual/stage1-empty-final/build-lint.log`

The four full-screen captures in each set are `downloading.png`, `queued.png`, `completed.png`, and `files.png`. The device folder also includes actual-above-reference text comparison crops. Artifacts remain local and are not intended for commit.

`assembleDebug` and `lintDebug` passed after the bounded corrections, the final APK installed successfully, and `git diff --check` passed. Persistence and settings test results are recorded separately in [stage-1-verification.md](stage-1-verification.md).
