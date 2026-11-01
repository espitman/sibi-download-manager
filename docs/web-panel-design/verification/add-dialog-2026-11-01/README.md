# Add downloads fixes — 2026-11-01

Release 0.2.79 (81) installed on the connected Xiaomi Android 14 phone.

- Grouped destination summary and folder actions; equal Start now / Queue selection cards.
- Added the missing clipboard SVG used by Paste.
- Close the HTTP add dialog after every link is confirmed accepted, before snapshot refresh. Keep failed links and uncertain submissions available; ignore stale responses when deciding which dialog to close.

Validation: 65 JavaScript tests passed, including five submission regression cases. Release assembly and release lint passed. Live phone-served UI inspected in Chrome at desktop and 390 × 844; no horizontal overflow at mobile width. Screenshots: desktop.png and mobile.png. The existing active transfer resumed after the upgrade and advanced from 573.2 MB to 578.2 MB with eight connections. No new test download was added to the user queue.
