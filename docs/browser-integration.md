# Browser integration

The Browser uses Android WebView for ordinary HTTP and HTTPS pages. Address input accepts a full URL, adds HTTPS to a hostname without a scheme, and searches Google for other text. Back and reload operate on the active WebView. Main-frame network and HTTP failures show a retry action.

The tab counter reflects the actual number of in-process tabs. Each tab has its own WebView and navigation history while Browser remains open. The tab sheet supports switching, adding, and closing tabs while keeping at least one tab. The Browser menu provides Downloads, find-in-page, desktop layout, and browser privacy controls.

Only a download explicitly selected in a page is handed to Add through WebView's DownloadListener. Add receives the URL, suggested filename, MIME type, length when known, User-Agent, and a scoped request context. The shared download engine handles the transfer. The Browser does not scan pages for media or provide special access to any video site.

Cookies and Referer are attached only when the request destination has the same scheme, host, and port as the selected download URL. This rule is applied separately to metadata requests and each transfer redirect. User-Agent may follow redirects. Authorization and proxy authorization headers from a browser request are stripped before transfer. Normal download grants can be retained with explicit consent. They are AES-GCM encrypted with an Android Keystore key and stored under `noBackupFilesDir`, outside the download database, exports, and Android backup. Successful completion and deletion remove the grant. Diagnostics redact cookies, Referer paths, and URL queries.

Normal tabs can retain their browsing session according to Browser settings. Private tabs are excluded from saved tab history. Download grants from private tabs, and normal grants with retention disabled, keep credentials only in process memory; their saved marker contains no cookies, User-Agent, or referring page. After process death the download requires sign-in instead of sending an unauthenticated transfer silently. Clearing browser data and approving a separate retained download grant are distinct choices.

An authenticated transfer receiving 401/403 stops with “Sign in again” and preserves partial bytes. A binary transfer redirected to an HTML page also stops rather than saving the sign-in page as the file. The card or download details opens the sign-in page in Browser. Selecting the download again updates the existing item through a reviewed link/session sheet. Matching strong ETag or checksum evidence preserves progress; otherwise deleting partial data requires explicit restart confirmation. HTTP Basic authentication is excluded from the plan.

See [Browser session validation](browser-session-validation.md) for the 0.2.72 verification.

Verified on the connected Xiaomi Android 14 device on 2026-09-22: an HTTP page opened, its `sample.bin` link entered Add with the correct filename, the file completed in `/sdcard/Download/SDM-QA/`, and its SHA-256 matched the source. The unavailable-page retry state, Back behavior, tab counter, tab sheet, and Browser menu were inspected on device. JVM redirect tests and device WebView/privacy tests passed.
