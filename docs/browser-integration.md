# Browser integration

The Browser uses Android WebView for ordinary HTTP and HTTPS pages. Address input accepts a full URL, adds HTTPS to a hostname without a scheme, and searches Google for other text. Back and reload operate on the active WebView. Main-frame network and HTTP failures show a retry action.

The tab counter reflects the actual number of in-process tabs. Each tab has its own WebView and navigation history while Browser remains open. The tab sheet supports switching, adding, and closing tabs while keeping at least one tab. The Browser menu provides Downloads, find-in-page, desktop layout, and browser privacy controls.

Only a download explicitly selected in a page is handed to Add through WebView's DownloadListener. Add receives the URL, suggested filename, MIME type, length when known, User-Agent, and a scoped request context. The shared download engine handles the transfer. The Browser does not scan pages for media or provide special access to any video site.

Cookies and Referer are attached only when the request destination has the same scheme, host, and port as the selected download URL. This rule is applied separately to metadata requests and each transfer redirect. User-Agent may follow redirects. Authorization and proxy authorization headers from a browser request are stripped before transfer. The cookie context is held in memory and removed after successful transfer; it is never saved in the download database. A protected download may need to be selected again if the app process dies before completion.

All Browser tabs run in a private session. Browser does not persist tab history. WebViews use no-cache mode, known tracking endpoints are blocked, and cookies, DOM storage, form data, cache, and WebView state are cleared when the Browser session ends. Stale cookies and DOM storage are cleared before a new session can load a page. This private-session behavior applies to the Browser; files explicitly downloaded to the user's chosen storage location remain available.

Verified on the connected Xiaomi Android 14 device on 2026-09-22: an HTTP page opened, its `sample.bin` link entered Add with the correct filename, the file completed in `/sdcard/Download/SDM-QA/`, and its SHA-256 matched the source. The unavailable-page retry state, Back behavior, tab counter, tab sheet, and Browser menu were inspected on device. JVM redirect tests and device WebView/privacy tests passed.
