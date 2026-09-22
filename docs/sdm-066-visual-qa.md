# SDM-066 visual QA

The approved Open Design HTML was rendered at a 393 × 873 logical viewport and compared with screenshots from the connected Xiaomi 2107113SG at the same logical size (1080 × 2400 physical pixels, 440 dpi). The physical phone was not rotated or rebooted. Real download data differs from the prototype's sample content, so layout, type hierarchy, colors, control shape, state copy, and interactions were compared separately from sample values.

## Screen and state matrix

| Screen / state | Dark | Light | Outcome |
| --- | --- | --- | --- |
| Downloads dashboard and empty Downloading state | Device/reference capture | Device/reference capture | Header, card, tabs, and dock geometry aligned after safe-area fix. Real counts replace sample counts. |
| Completed records and details | Device capture plus reference/source audit | Theme-aware source audit | Completed cards show a check; completed details no longer offer a nonsensical Pause/Cancel transfer action. Real file metrics and paths are retained. |
| Files list, storage, filters, and selected row | Device/reference capture | Device capture plus reference/source audit | Header/storage/list alignment verified. Completion labels now use muted text; selected rows have the 3 dp gold stripe. |
| Browser landing and toolbar | Device/reference capture | Device capture plus reference/source audit | Header, toolbar, private badge, quick access, and dock placement verified. |
| Browser tabs sheet | Device/reference capture after fix | Theme-aware source audit | Panel bounds, two preview cards, dashed New tab tile, and private-tab control follow the reference. |
| Settings main screen and theme sheet | Device/reference capture | Device/reference capture after fix | Main card and settings groups align. Sheet now ends at the reference bottom inset. |
| Add sheet | Device capture and reference/source audit | Device/reference capture | Sheet bottom inset corrected; source reference contains a prefilled sample URL while the real app correctly starts empty. |

The common header previously began about 21–24 dp too high and the dock ended about 27–28 dp too low. `DesignSafeArea.kt` now fills the reference's 52 dp top and 38 dp dock-safe spacing while accounting for each Android device's actual insets. Fresh device captures show the Downloads header at y≈67, status card at y≈134, and dock at y≈767–835, matching the reference viewport. Bottom sheets use a 68 dp effective bottom inset, matching the reference panel bottom at y≈805. Files More options and Settings Search now give the reference's visible feedback instead of doing nothing.

An Astra design review identified the safe-area, Browser tabs, completion-label, selected-row, and no-op control discrepancies; each avoidable issue above was fixed and rechecked. The user explicitly prefers a horizontal slide for main-tab transitions, so that later instruction remains authoritative over the prototype's fade-only transition.

The prototype uses Avenir Next for some display text. The app bundles the licensed IBM Plex Sans body family but cannot bundle Apple's Avenir Next without redistribution rights. Minor glyph-shape/width differences remain in display text; they are documented rather than claiming exact typography. System status-bar icons and live device data also necessarily differ from the static prototype.
