# kyo-browser

Drive a real browser from Kyo. The module launches a managed Chrome process (or attaches to any CDP-capable browser) and exposes a single `Browser` effect. Every action, assertion, and read goes through the Chrome DevTools Protocol directly, with no WebDriver layer in between. The same code compiles and runs on JVM, JavaScript, and Scala Native.

Every observable operation waits for the page to be ready before it returns, so call sites never need explicit `sleep` or `wait` calls:

```scala
import kyo.*

Browser.run {
    for
        _ <- Browser.goto("https://example.com")
        _ <- Browser.fill(Browser.Selector.label("Email"), "alice@example.com")
        _ <- Browser.click(Browser.Selector.button("Sign in"))
        _ <- Browser.assertText(Browser.Selector.heading, "Welcome, Alice")
    yield ()
}
```

In this example `Browser.click` blocks until its target is attached to the DOM, visible, geometrically stable, hittable, and enabled; `Browser.assertText` re-checks against the active retry schedule until the heading reads `"Welcome, Alice"` or the schedule exhausts. No part of the call site picks a timeout, and no part writes a polling loop: settlement is part of every operation's contract.

Every entry point below lives on the `Browser` companion object (so `run` means `Browser.run`, `click` means `Browser.click`, and so on):

| Entry point | Purpose |
|-------------|---------|
| `run` / `runShared` | Launch (or attach to) a browser and scope a tab to the body |
| `Selector` | Build locators by role, text, label, placeholder, test-id, CSS, or id |
| `goto` / `back` / `forward` / `reload` / `expectNavigation` / `history` | Navigation, auto-settled to a configurable readiness mode |
| `click` / `fill` / `check` / `select` / `press` / `hover` / `dragAndDrop` / `setFiles` | Interactions, gated by the actionability check |
| `assertText` / `assertVisible` / `assertCount` / `assertChecked` / `assertNoVisibleText` / etc. | Auto-retrying assertions |
| `text` / `attribute` / `html` / `value` / `count` / `countNow` / `boundingBox` / `accessibilityNodes` | Reads of page state |
| `isVisible` / `isEnabled` / `isChecked` / `isFocused` / `exists` / `hasAttribute` / `hasNoVisibleText` / `hasEmptyValue` | Boolean point-in-time predicates |
| `waitForText` / `waitForUrl` / `waitForTitle` / `waitForCount` / `waitForVisible` / `waitForExists` / `waitForNetworkIdle` / `waitForRequestUrl` / `waitFor` | Explicit polling waits |
| `eval` / `evalJson` / `evalBoolean` / `evalInt` / `evalLong` / `evalDouble` / `evalDiscard` | Run arbitrary JS in the page |
| `screenshot` / `pdf` / `readableContent` | Snapshot artefacts |
| `mockFetchResponse` / `clearMocks` | In-page `fetch` interception |
| `cookies` / `setCookie` / `deleteCookie` / `tryAcceptCookies` | Cookie jar |
| `withDownloads` / `onDownload` / `recordDownloads` | Download capture |
| `iframe` / `iframes` / `mainFrame` / `withIFrame` | Cross-frame access |
| `consoleLogs` | Captured console messages (`ConsoleMessage` / `ConsoleLevel`) |
| `withConfig` / `withTimeout` / `withViewport` / `withDialogs` | Scoped configuration |
| `withNewTab` / `withFork` / `withPopup` / `isolate.fresh` / `isolate.clone` | Sub-tab and concurrent isolation |
| `dataUrl` | Build a `data:text/html` URL for inline-HTML fixtures |

## Add the dependency

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-browser" % "<latest version>"
```

All public types live in the `kyo` package:

```scala
import kyo.*
```

Browser-side helpers (selectors, settle modes, page lifecycle, configuration types) live under the `Browser.*` namespace, so a single import line is enough.

## Browser lifecycle: process, context, tab, iframe

A `Browser.run` body operates inside a four-layer hierarchy. Each layer contains the next, and each layer is cleaned up when its parent ends. Most call sites only think about the tab; the deeper layers exist so the library can attach resources to the right cleanup boundary.

```
process    one Chrome process; launched by `Browser.run(launch)` or shared across the JVM via `Browser.runShared`
  └─ context    a fresh, cookie- and storage-isolated browser context (Chrome's incognito-equivalent), one per `Browser.run` call
       └─ tab       an attached target with its own session id; this is what the `Browser` effect carries
            └─ iframe    a nested document inside the tab, scoped via `Browser.withIFrame`
```

There are three entry points for materialising a browser:

`Browser.run(launch, session)` is the everyday form. It launches a fresh Chrome, runs the body, and shuts the process down when the body completes (whether by success, failure, or interruption). The no-argument overload downloads `chrome-headless-shell` (the lightweight headless build Google publishes alongside full Chrome for testing) on first use, caches the binary under the platform cache directory (override via `KYO_BROWSER_CACHE`), and launches it.

Auto-download covers five platforms: macOS Intel, macOS Apple Silicon, Linux x86_64, Windows 64-bit, Windows 32-bit. Google does not publish a Linux ARM (or Windows ARM) build, so the zero-arg overload aborts on those platforms with a [`BrowserSetupException`](shared/src/main/scala/kyo/BrowserException.scala) whose message points you at a system-installed Chromium: install it via your package manager (e.g. `apt install chromium-browser`) and pass `Browser.LaunchConfig.chromium("chromium-browser")` (or another absolute path) to `Browser.run(config) { ... }` explicitly.

To download a specific build variant or pin to a particular Chrome version before calling `Browser.run`, use `Browser.chromeForTestingLaunchConfig`. It accepts a `Browser.ChromeForTestingBuild` (`HeadlessShell` or `Chrome`) and an optional version string, downloads and caches the binary, and returns a `LaunchConfig` ready to pass to `Browser.run`. The `Chrome` variant (~190 MB) is required for headed mode (`headless = false`); `HeadlessShell` (~120 MB) is sufficient for all headless use.

```scala
Browser.run {
    Browser.goto("https://example.com").andThen(Browser.title)
}
```

`Browser.runShared` keeps one Chrome alive for the lifetime of the JVM and tears it down through a shutdown hook. Each call still gets a fresh tab in a fresh browser context, so per-call isolation matches `Browser.run`; the difference is that you only pay Chrome's 2-3 second boot cost once per JVM. This is the form to reach for in test suites that drive many short sessions.

```scala
Browser.runShared() {
    Browser.goto("https://example.com").andThen(Browser.title)
}
```

`Browser.run(wsUrl)` attaches to a browser that is already running (an existing Chrome, a Playwright launcher, a Docker container) by its DevTools WebSocket URL, instead of launching a new process.

```scala
Browser.run("ws://localhost:9222/devtools/browser/<uuid>") { /* ... */ }
```

Inside the body, four scopers carve smaller scopes out of the same tab or context:

- `Browser.withNewTab` opens a sibling tab in the same browser context (cookies and storage shared, page state fresh).
- `Browser.withFork` opens a child tab in a fresh context that has been snapshotted from the parent (URL, cookies, storage, form values, scroll, focus), runs the body, and discards every mutation when the body exits.
- `Browser.withPopup(trigger)(handler)` arms a handler that runs against whichever popup tab the trigger opens.
- `Browser.withIFrame(frame)` scopes every subsequent operation to a child document.

To take full control of how Chrome is launched (custom binary, additional flags, non-headless mode for debugging), build a `Browser.LaunchConfig` and pass it to `Browser.run`:

```scala
val launch = Browser.LaunchConfig.chrome("/usr/bin/google-chrome")
    .headless(false)
    .extraArgs(Seq("--disable-extensions"))

Browser.run(launch) { /* ... */ }
```

## Selectors

A `Selector` describes how to locate an element on the page. Reach for the semantic constructors first; they match how a user perceives the page and survive most layout / styling changes. Fall back to CSS only when there is no semantic option.

```scala
// ARIA role + accessible-name builders (one method per supported role)
Browser.Selector.button("Sign in") // role=button, accessible name "Sign in"
Browser.Selector.textbox("Email")  // role=textbox, accessible name "Email"
Browser.Selector.link("Documentation")
Browser.Selector.heading("Welcome")
Browser.Selector.checkbox("I agree")
Browser.Selector.combobox("Country")
Browser.Selector.radio("Yes")
Browser.Selector.tab("Settings")
Browser.Selector.menuitem("Delete")
Browser.Selector.dialog("Confirm")
// Pass no name to match any element with the role: Browser.Selector.button

// Locators by visible content or form-control attributes
Browser.Selector.text("Sign in")      // visible text (case-insensitive substring; pass exact = true for strict)
Browser.Selector.label("Email")       // labelled control whose associated <label> text is "Email"
Browser.Selector.placeholder("you@…") // [placeholder="you@…"]
Browser.Selector.title("More info")   // [title="More info"]
Browser.Selector.testId("login-form") // [data-testid="login-form"]

// Direct locators
Browser.Selector.id("submit-btn") // #submit-btn
Browser.Selector.css("form button.primary")
```

### String is a Selector

Every public API in this module that takes a `Selector` also accepts a raw `String`. An implicit `Conversion[String, Selector]` recognises a small prefix DSL (`text=`, `testid=`, `label=`, `id=`, `css=`) and routes each form to the corresponding typed constructor; anything without a recognised prefix is treated as a raw CSS selector:

```scala
for
    _ <- Browser.click("text=Sign in") // == Browser.click(Browser.Selector.text("Sign in"))
    _ <- Browser.click("#go")          // == Browser.click(Browser.Selector.css("#go")) (no prefix, verbatim CSS)
    _ <- Browser.fill("label=Email", "alice@example.com")
    _ <- Browser.assertExists("testid=login-form")
yield ()
end for
```

Strings with an unrecognised prefix (for example `"abc=def"`) fall back to CSS verbatim, including the prefix text itself. `role=` is intentionally NOT a prefix; reach for the typed `Browser.Selector.button(name)` / `Selector.textbox(name)` / etc. constructors when the locator describes a role.

### Composition

`or` chains fallbacks (the first alternative that matches wins). `find` scopes a child selector to the elements matched by the parent:

```scala
import Browser.Selector.*

// Try a semantic role first; fall back to the legacy CSS id if the role isn't set.
val signIn = Browser.Selector.button("Sign in").or(Browser.Selector.id("legacy-signin"))

// Locate the "Email" textbox inside the "Login" dialog.
val emailInLoginDialog = Browser.Selector.dialog("Login")
    .find(Browser.Selector.textbox("Email"))
```

## Why operations don't need explicit waits

Browser automation usually fails because the action fires before the page is ready, not because the action itself is wrong. kyo-browser absorbs that class of failure with three settlement gates that run automatically.

**Navigation settlement** is the gate for `goto`, `back`, `forward`, `reload`, and `expectNavigation`. Each of those calls returns only once the page has reached the requested `Browser.Settle` mode. The default mode is `Browser.Settle.NetworkIdle`: the `load` event must fire AND in-flight fetch / XHR traffic must stay quiet for the configured window (500 ms by default). This hides flake from third-party telemetry at the cost of a few hundred milliseconds on chatty pages. To return earlier, pass `Browser.Settle.Load` or `Browser.Settle.DomContentLoaded`.

**Mutation settlement** is the gate for `click`, `fill`, `press`, `check`, and `select`. The library first waits until the target element is actionable (attached, visible, geometrically stable, hittable at its centre point, not disabled), then dispatches the action, then waits for a quiet DOM window. The window is `mutationQuiescenceWindow` of no observed mutations (default 50 ms), bounded above by `mutationSettlementTimeout` (default 2 s). This eliminates the "I clicked but the handler hadn't run yet" class of failure. `hover` and `press` skip the disabled check by design; the rest of the actionability gate still runs. To shorten the windows, override them via `Browser.withConfig`; to bypass the gate entirely, drop to `Browser.eval` for raw JS.

**Assertion settlement** is the gate for `assertText`, `assertVisible`, `assertCount`, `waitForText`, and friends. Each of those re-evaluates its predicate against the active retry schedule (default `100 ms × maxDuration(8 s)`) until either the predicate accepts or the schedule exhausts. An exhausted schedule raises a typed `BrowserAssertion*` exception rather than returning `false`. For a point-in-time read use `Browser.text` / `attribute` / `count`; for arbitrary JS predicates use `Browser.waitFor(jsCondition)`. Every retrying method also accepts a `schedule: Maybe[Schedule]` argument for a one-off override of the active schedule.

Some flows need a separate dance: an interaction whose follow-on navigation is driven by JS (a button whose click handler calls `location.assign`, a link that opens via `window.open` and then redirects, …) does not surface a navigation event to CDP at the moment of the click. `Browser.expectNavigation(settle) { trigger }` arms the navigation watcher around the trigger so the click and its follow-on load settle together.

## Interactions and assertions

Every interaction returns a `Unit < (Browser & Abort[BrowserReadException])` computation. Each read returns its typed value lifted into the same effect row. Compose them with `for`, `.map`, or `.andThen` like any other Kyo effect:

```scala
for
    _ <- Browser.fill(Browser.Selector.label("Email"), "alice@example.com")
    _ <- Browser.fill(Browser.Selector.label("Password"), "hunter2")
    _ <- Browser.check(Browser.Selector.checkbox("Remember me"))
    _ <- Browser.click(Browser.Selector.button("Sign in"))
    _ <- Browser.assertText(Browser.Selector.heading, "Welcome, Alice")
yield ()
end for
```

The interaction surface:

| Method | Effect | Description |
|---|---|---|
| `click(selector)` / `doubleClick(selector)` | `Unit` | Mouse click at the centre of the actionable target |
| `fill(selector, text)` | `Unit` | Replace the value of an `<input>`, `<textarea>`, or `contentEditable` element |
| `check(selector)` / `uncheck(selector)` | `Unit` | Set a checkbox to the desired state (no-op if already there) |
| `select(selector, value)` | `Unit` | Choose an `<option>` by `value` in a `<select>` |
| `press(selector, key, modifiers)` / `press(key, modifiers)` | `Unit` | Send a keystroke to a focused selector or to the page-level active element. `modifiers: KeyModifiers` carries Shift / Ctrl / Alt / Meta as a typed value; defaults to `KeyModifiers.none` |
| `keyDown(key)` | `Unit` | Dispatch a raw keyDown CDP event for `key` (low-level; prefer `press` unless split down/up timing is required) |
| `keyUp(key)` | `Unit` | Dispatch a raw keyUp CDP event for `key` |
| `hover(selector)` | `Unit` | Move the mouse to the target's centre |
| `dragAndDrop(source, target)` | `Unit` | Press at source, move to target, release |
| `setFiles(selector, paths)` | `Unit` | Attach a `Seq[Path]` to an `<input type="file">` without opening a native picker |
| `scrollTo(selector)` / `scrollToTop` / `scrollToBottom` | `Unit` | Scroll the element (or the page) into view |
| `typeText(text)` | `Unit` | Send a character sequence to the page-level active element |
| `focus(selector)` | `Unit` | Move keyboard focus to the target |

### Browser.Key

`press`, `keyDown`, and `keyUp` accept a `Browser.Key` value. The named constants cover all common non-printable keys; `Key(char)` wraps a single character for printable input:

```scala
import kyo.*

Browser.press(Browser.Key.Enter)     // submit / confirm
Browser.press(Browser.Key.Tab)       // focus next element
Browser.press(Browser.Key.Escape)    // dismiss
Browser.press(Browser.Key.ArrowDown) // ArrowUp / ArrowDown / ArrowLeft / ArrowRight
Browser.press(Browser.Key.Home)      // Home / End / PageUp / PageDown
Browser.press(Browser.Key.Backspace) // delete backwards
Browser.press(Browser.Key.Delete)    // delete forwards
Browser.press(Browser.Key('a'))      // printable character
```

The full set of named constants: `Enter`, `Tab`, `Backspace`, `Escape`, `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`, `Home`, `End`, `PageUp`, `PageDown`, `Delete`, `Space`, `Shift`, `Control`, `Alt`, `Meta`.

The assertion family. Each entry is auto-retried against the active retry schedule and raises a typed `BrowserAssertion*` exception when the schedule exhausts.

| Method | Description |
|---|---|
| `assertExists(selector)` / `assertNotExists(selector)` | DOM presence |
| `assertVisible(selector)` / `assertNotVisible(selector)` | Visibility per the standard visibility ladder |
| `assertEnabled(selector)` / `assertDisabled(selector)` | Form-control enabled state |
| `assertChecked(selector)` / `assertNotChecked(selector)` | Checkbox / radio state |
| `assertFocused(selector)` / `assertNotFocused(selector)` | Keyboard focus |
| `assertText(selector, expected)` / `assertTextSatisfies(selector)(predicate)` | Visible-text equality or predicate match |
| `assertAttribute(selector, name, expected)` / `assertAttributeSatisfies(selector, name)(predicate)` / `assertNoAttribute(selector, name)` | DOM attribute presence, equality, or predicate match |
| `assertCount(selector, expected)` / `assertCountSatisfies(selector)(predicate)` / `assertCount(selector, message, schedule)(predicate)` | Matched-element count, with an overload accepting an explicit per-call retry `Schedule` |
| `assertRole(selector, expected)` / `assertAccessibleName(selector, expected)` | ARIA role / accessible name |
| `assertUrl(expected)` / `assertUrlSatisfies(predicate)` / `assertTitle(expected)` / `assertTitleSatisfies(predicate)` | Page-level URL / `<title>` |
| `assertPageTextOrder(substrings)` / `assertSelectorOrder(selectors)` | Sequenced presence within `document.body.innerText` (or on the page) |
| `assertNoVisibleText(selector)` | `textContent.trim()` is empty (use for non-input elements) |
| `assertValueEmpty(selector)` | `.value` is empty (use for `<input>` / `<textarea>`) |

The two predicates are distinct because `textContent` and `value` carry different data for form controls: pick the one that names what you actually want to check.

Every retrying method also has a trailing `schedule: Maybe[Schedule]` argument so a single call can override the active retry schedule without reaching for `Browser.withConfig`.

## Reading page state

When you need the actual value of something rather than to assert on it, use the read APIs. Element-bound reads retry on the active schedule when the element is not yet attached; page-level reads (`url`, `title`, `readableContent`) return immediately. Each call returns its typed value in `Browser & Abort[BrowserReadException]` and composes with the rest of the surface:

```scala
for
    title   <- Browser.title
    heading <- Browser.text(Browser.Selector.heading)
    items   <- Browser.textAll(Browser.Selector.css("li"))
yield (title, heading, items)
end for
```

The read surface:

| Method | Returns | Notes |
|---|---|---|
| `text(selector)` | `String` | `innerText` of one element |
| `textAll(selector)` | `Chunk[String]` | `innerText` of every match |
| `value(selector)` | `String` | `.value` of an `<input>` / `<textarea>` / `<select>` |
| `attribute(selector, name)` | `String` | DOM attribute value (`""` if absent) |
| `attributeAll(selector, name)` | `Chunk[String]` | The attribute on every match |
| `html(selector)` / `outerHtml(selector)` | `String` | `innerHTML` / `outerHTML` of one element |
| `count(selector)` | `Int` | Matched-element count, retried on the active schedule |
| `countNow(selector)` | `Int` | Point-in-time matched-element count (no retry, returns `0` when nothing matches) |
| `selectionStart(selector)` | `Int` | Cursor position inside an `<input>` / `<textarea>` |
| `boundingBox(selector)` | `Maybe[BoundingBox]` | Page-relative geometry |
| `role(selector)` / `accessibleName(selector)` | `Maybe[String]` | ARIA role / accessible name from the AX tree |
| `url` / `title` | `String` | Page-level location and `<title>` |
| `readableContent` | `String` | Mozilla Readability extraction of the page's main content |
| `accessibilityNodes` | `Chunk[Browser.AxNode]` | Flat AX tree of the current frame |
| `consoleLogs` / `consoleLogs(level)` | `Chunk[Browser.ConsoleMessage]` | Console buffer captured since the tab attached. `ConsoleMessage` carries `level: Browser.ConsoleLevel`, `text: String`, and the source frame; the level-filtered overload returns only matching messages |

`screenshot` and `screenshotElement` return a `Browser.Image`. The type exposes: `binary: Span[Byte]` (raw bytes), `base64: String` (Base64-encoded), `writeFileBinary(path)` / `writeFileBase64(path)` (write to disk), and `renderToConsole(charsWidth, charsHeight)` (terminal sixel/block rendering for debugging). `pdf` returns raw `Span[Byte]` PDF bytes directly (not a `Browser.Image`); it only works in headless Chrome. The capture methods take a format and quality: `screenshot(width, height, format, quality)` and `screenshotElement(selector, format, quality)`, where `format` is a `Browser.ScreenshotFormat` (`Png`, `Jpeg`, or `Webp`, default `Png`) and `quality` (0 to 100) applies only to the lossy formats (`Jpeg` and `Webp`) and is ignored for `Png`.

### Boolean predicates

Point-in-time `Boolean` reads for branching logic. Unlike the `assertX` family, these do NOT retry: they answer the question "is this true right now?" and let the caller decide what to do with `false`.

| Method | Returns | Notes |
|---|---|---|
| `exists(selector)` | `Boolean` | `true` if at least one element matches |
| `isVisible(selector)` | `Boolean` | Visibility ladder predicate |
| `isEnabled(selector)` | `Boolean` | `true` if the form control is not disabled |
| `isChecked(selector)` | `Boolean` | `true` if the checkbox / radio is checked |
| `isFocused(selector)` | `Boolean` | `true` if the element is the active element |
| `hasAttribute(selector, name)` | `Boolean` | `true` if the attribute is present |
| `hasNoVisibleText(selector)` | `Boolean` | `textContent.trim().isEmpty` |
| `hasEmptyValue(selector)` | `Boolean` | `.value.isEmpty` for an `<input>` / `<textarea>` |

## Wait helpers

Reach for an explicit wait only when the condition is genuinely external (network state, a predicate over evolving DOM state) and not already covered by an `assertX` method. Each helper polls and returns the value it matched on:

```scala
for
    _      <- Browser.waitForNetworkIdle
    status <- Browser.waitForText(Browser.Selector.id("status"), _.contains("Ready"))
yield status
end for
```

| Method | Returns | Description |
|---|---|---|
| `waitForText(selector, predicate)` / `waitForText(selector, expected)` | `String` | Polls `text(selector)` until the predicate accepts (or text equals `expected`) and returns the matched text |
| `waitForAttribute(selector, name, predicate)` / `waitForAttribute(selector, name, expected)` | `String` | Same shape, on a DOM attribute |
| `waitForUrl(predicate)` / `waitForUrl(expected)` | `String` | Polls the active page URL |
| `waitForTitle(predicate)` / `waitForTitle(expected)` | `String` | Polls the active page `<title>` |
| `waitForCount(selector, predicate)` | `Int` | Polls `countNow(selector)` until the predicate accepts |
| `waitForVisible(selector)` | `Unit` | Polls until the element is visible per the visibility ladder |
| `waitForExists(selector)` | `Unit` | Polls until the element is attached |
| `waitForNetworkIdle` / `waitForNetworkIdle(idle)` | `Unit` | Settles once `pending == 0` and the idle window has elapsed |
| `waitForRequestUrl(urlPattern)` | `String` | Waits for a matching request to fire and returns the matched URL |
| `waitFor(js)` | `String` | Polls an arbitrary JS predicate and returns the value of the last evaluation |

Each `waitForX` accepts an optional trailing `schedule: Maybe[Schedule]` so a single call can override the active retry schedule.

### Navigation history

`Browser.history` returns the in-tab `NavigationHistory`: the ordered list of entries and a current pointer. The companion accessors answer the natural questions without manual index arithmetic.

| Method | Returns | Description |
|---|---|---|
| `Browser.history` | `NavigationHistory` | Snapshot of the current tab's navigation list |
| `NavigationHistory.entries` | `Chunk[NavigationEntry]` | All entries in chronological order |
| `NavigationHistory.currentIndex` | `Int` | Index of the active entry |
| `NavigationHistory.current` | `NavigationEntry` | The active entry |
| `NavigationHistory.canGoBack` | `Boolean` | `true` if `back` would succeed |
| `NavigationHistory.canGoForward` | `Boolean` | `true` if `forward` would succeed |

`Browser.expectNavigation(settle)(trigger)` arms a navigation watcher around `trigger` so the trigger's follow-on navigation is settled before the call returns. Use it when a click handler calls `location.assign`, a form posts JS-side, or `window.open` redirects after open.

## Configuration: launch-time vs per-session

`Browser.SessionConfig` holds every per-session setting: retry schedule, load schedule, network-idle window, mutation-quiescence windows, and assertion stability. Most call sites do not construct one explicitly; instead they install a scoped override:

```scala
// Cap the total retry budget for every enclosed operation at 5 seconds.
Browser.withTimeout(5.seconds) {
    Browser.assertText(Browser.Selector.id("status"), "Done")
}

// Single-field override that preserves the rest of the enclosing config.
Browser.withConfig(_.retrySchedule(Schedule.exponential(100.millis, 2.0).take(8))) {
    Browser.assertCount(Browser.Selector.css("tr"), 10)
}

// Per-scope viewport, restored on exit.
Browser.withViewport(width = 1440, height = 900) {
    Browser.screenshot
}
```

`Browser.SessionConfig` fields (settable individually via `withConfig`):

| Field | Purpose |
|---|---|
| `retrySchedule` | Assertion / wait retry schedule (default `100 ms × maxDuration(8 s)`) |
| `loadSchedule` | Per-load settle retry schedule |
| `networkIdleWindow` | Idle window for `Settle.NetworkIdle` |
| `mutationQuiescenceWindow` | Quiet-DOM window after an interaction |
| `mutationSettlementTimeout` | Upper bound on the quiet-DOM wait |
| `mutationFirstMutationGrace` | Grace period before the first DOM mutation is required after an interaction |
| `assertionStabilityWindow` | Extra quiet window after an assertion matches before it is accepted (set to `Duration.Zero` for first-match behaviour) |
| `mutationPollInterval` | Polling interval for the quiet-DOM window |
| `navigationPostSettleWindow` | Extra grace window after navigation settle |
| `navigationPollInterval` | Polling interval during navigation settle |
| `navigationGraceWindow` | Pre-navigation grace before the watcher arms |
| `stabilitySampleInterval` | Sample period for the actionability stability check |
| `defaultActionTimeout` | Default timeout for interactions |
| `defaultAssertionTimeout` | Default timeout for assertions |

Viewport is not a `SessionConfig` field; use `Browser.withViewport(width, height)` for a scoped viewport override.

`Browser.LaunchConfig` holds the launch-time settings (executable path, headless flag, extra Chromium args, launch timeout, plus the new fields below). It is consumed once when Chrome starts and is frozen for the lifetime of that process. `Browser.withConfig` does not touch launch-time fields; only `Browser.run(launch, …)` does. Chrome / Chromium is the supported target.

`Browser.LaunchConfig` fields:

| Field | Purpose |
|---|---|
| `executable` | Path to the Chrome binary; defaults to the downloaded `chrome-headless-shell` |
| `headless` | Headless mode toggle (default `true`) |
| `extraArgs` | Extra Chromium command-line args |
| `launchTimeout` | Upper bound on the launch handshake |
| `requestTimeout` | Default per-CDP-request timeout |
| `closeGrace` | Grace period for clean Chrome shutdown |
| `tmpDirRemovalSchedule` | Retry schedule for cleaning up the per-launch tmp dir |
| `devToolsActivePortPollInterval` | Polling interval while waiting for `DevToolsActivePort` |
| `chromeDownloaderConfig` | Config for the bundled `chrome-headless-shell` downloader |

The split is compiler-enforced: `Browser.withConfig` accepts only a `SessionConfig`, so launch-time fields (executable, headless, extra args) cannot be changed after Chrome starts. They are settable only through `Browser.run(launch, ...)`.

## Isolation: sequential and concurrent

For a sequential sub-computation, three single-purpose helpers each open one new scope and clean it up on exit:

- `Browser.withNewTab(body)` opens a sibling tab in the same browser context (cookies and storage shared with the parent). The new tab starts on `about:blank`.
- `Browser.withFork(body)` opens a child tab in a fresh context, restores a snapshot of the parent (URL, cookies, storage, form values, scroll, focus), runs the body, and discards every mutation when the body exits. The parent is unchanged. `withFork` enforces total isolation: cookies, storage, mocks, dialog handlers, and download settings inside the fork do not leak back, and the parent's settings do not bleed into the fork.
- `Browser.withPopup(trigger)(handler)` / `Browser.withPopup(schedule)(trigger)(handler)` arms a handler that runs against the popup tab the trigger is expected to open. The `schedule` overload customises how long to wait for the popup to materialise.

```scala
for
    _ <- Browser.fill(Browser.Selector.id("draft"), "original")
    _ <- Browser.withFork {
        for
            _ <- Browser.fill(Browser.Selector.id("draft"), "experiment")
            _ <- Browser.click(Browser.Selector.button("Save"))
        yield ()
    }
    // Back in the parent: the draft field still says "original".
    draft <- Browser.attribute(Browser.Selector.id("draft"), "value")
yield draft
end for
```

`withFork` is total isolation: the parent tab is not reachable from inside the body, and cookies, storage, mocks, dialog handlers, and download settings set inside the fork never leak back out. Callers who need parent state must capture it into a `val` before entering `withFork`.

### Concurrent forks via `Browser.isolate`

`Browser` is single-tab by construction, so two fibers cannot accidentally share one tab. Concurrent combinators like `Async.zip`, `Async.parallel`, and `Loop.foreach` therefore demand an `Isolate[Browser, …]` value before they will fork the `Browser` effect across fibers. The compiler refuses to derive one automatically because there is no safe default split for a single CDP session, so `Browser.isolate` offers the two safe choices and forces the call site to pick:

- `Browser.isolate.fresh` gives each fork its own blank tab in a fresh browser context. Cookies, localStorage, and sessionStorage all start empty per fork. Use it for "N independent searches", per-page scraping, parallel smoke tests against unrelated URLs.
- `Browser.isolate.clone` snapshots the parent tab (URL, cookies, storage, form values, scroll, focus) and gives each fork a fresh browser context restored from that snapshot. Use it when the per-fork work depends on the parent's logged-in state, current route, or in-flight form.

Either choice isolates the cookie / storage jar at the browser-context boundary, so writes inside a fork never leak back to the parent and forks cannot observe each other. Either choice also tears the forked context down when the surrounding scope completes (by success, failure, or interruption).

```scala
val urlA = "https://example.com/a"
val urlB = "https://example.com/b"
val urlC = "https://example.com/c"

Browser.isolate.fresh.use {
    Async.zip(
        Browser.goto(urlA).andThen(Browser.title),
        Browser.goto(urlB).andThen(Browser.title),
        Browser.goto(urlC).andThen(Browser.title)
    )
}
```

## Network mocking and downloads

```scala
// Intercept fetch() for a specific URL and return a canned response.
for
    _ <- Browser.mockFetchResponse(
        url = "https://api.example.com/users",
        status = 200,
        body = """[{"id":1,"name":"Alice"}]""",
        headers = Seq("Content-Type" -> "application/json")
    )
    _ <- Browser.click(Browser.Selector.button("Load users"))
    _ <- Browser.assertText(Browser.Selector.css("li"), "Alice")
    _ <- Browser.clearMocks
yield ()
end for

// Capture downloads triggered by the page (e.g. clicking an anchor with `download`).
Browser.withDownloads(toPath = "/tmp/dl") {
    Browser.click(Browser.Selector.link("Export CSV"))
}
```

Download capture has three public entry points; pick the one that matches what the test needs to do:

| Method | Description |
|---|---|
| `withDownloads(toPath)(body)` | Scoped variant: enables capture for `body`, restores the previous setting on exit. |
| `onDownload(handler)(body)` | Register an event handler invoked once per download begun inside `body`. The handler receives a `Browser.DownloadEvent` (URL, suggested filename, destination path on disk). Use for tests that need to observe downloads as a stream of events rather than just check that one file landed. |
| `recordDownloads(body)` | Record every download started inside `body` into a `Chunk[Browser.DownloadEvent]` returned alongside the body's result. Equivalent to a passive `onDownload` that buffers. |

## Cookies, iframes, and accessibility

Cookies:

| Method | Returns |
|---|---|
| `cookies` / `cookies(forUrl)` | `Chunk[Cookie]` |
| `setCookie(name, value, domain, path)` / `setCookie(cookie)` | `Unit` |
| `deleteCookie(name)` / `deleteCookie(name, domain)` | `Unit` |
| `tryAcceptCookies` / `tryAcceptCookies(schedule)` | `Maybe[Selector]` (the dismissed selector, when a cookie banner was matched). The overload accepts a per-call retry `Schedule` for slow-loading banners |

IFrames: resolve a handle to a child frame, then scope a body to it with `withIFrame`. Every `Browser` operation inside that body targets the frame:

```scala
Browser.iframe(Browser.Selector.css("iframe#payment")).map { frame =>
    Browser.withIFrame(frame) {
        Browser.click(Browser.Selector.button("Pay"))
    }
}
```

Accessibility: `Browser.accessibilityNodes` returns `Chunk[Browser.AxNode]` for the current frame. The return shape is a flat sequence, not a tree. The per-element helpers `assertRole`, `assertAccessibleName`, `role`, and `accessibleName` consult the same sequence.

## Custom JavaScript

For anything outside the standard surface, `eval` runs an arbitrary JS expression in the page and returns its result as a `String`. For a typed JSON result use `evalJson[T]`: it wraps the expression with `JSON.stringify` on the page side and decodes the response via the in-scope `Schema[T]`. For ad-hoc primitive results without a full Schema dance, the per-type helpers parse the result directly:

```scala
case class Point(x: Int, y: Int) derives Schema

for
    flag    <- Browser.eval("localStorage.getItem('flag')")              // String
    point   <- Browser.evalJson[Point]("({ x: 10, y: 20 })")             // Point
    isReady <- Browser.evalBoolean("document.readyState === 'complete'") // Boolean
    n       <- Browser.evalInt("document.querySelectorAll('li').length") // Int
    ts      <- Browser.evalLong("Date.now()")                            // Long
    ratio   <- Browser.evalDouble("window.devicePixelRatio")             // Double
    _       <- Browser.evalDiscard("window.__flag = true")               // Unit; ignores return value
yield (flag, point, isReady, n, ts, ratio)
end for
```

Each `evalX` returns its value inside `Browser & Abort[BrowserReadException]`.

The `dataUrl(html)` utility builds a `data:text/html;charset=utf-8,...` URL containing percent-encoded `html`; convenient for inline-HTML fixtures (`Browser.goto(Browser.dataUrl("<h1>hi</h1>"))`) that don't need a real origin.

JS dialogs (`alert`, `confirm`, `prompt`) are auto-dismissed by default, so they never block evaluation. To intercept them, wrap a scope in `Browser.withDialogs.accept`, `Browser.withDialogs.dismiss`, `Browser.withDialogs.prompt("answer")`, or `Browser.withDialogs.recorded` (passive observer that captures every dialog opened in the body into a `Chunk[Browser.DialogEvent]` without changing the auto-handler's behaviour). Every dialog opened inside the scope is then handled accordingly:

```scala
Browser.withDialogs.accept {
    Browser.click(Browser.Selector.button("Delete")) // confirm() returns true
}

Browser.withDialogs.recorded {
    Browser.click(Browser.Selector.button("Delete")) // dismissed by the default handler;
    // the event is also captured for assertion
}
```

## Errors

Every failure surfaces as a typed `Abort` channel. `BrowserException` is the sealed root. Concrete failures are `final case class`es that mix in marker traits along two axes:

- **Operation-row markers** group failures by the kind of operation that can raise them, ordered by widening containment: `BrowserReadException` is the narrowest (anything that observes page state), `BrowserMutationException` widens it to interactions, and `BrowserAssertionException` widens further to assertions and waited-for conditions.
- **Topical markers** group failures by domain: `BrowserConnectionException` for CDP transport, `BrowserElementException` for element lookup and interaction, `BrowserNavigationException` for navigation, `BrowserScriptException` for in-page JS, `BrowserAssertionException` for assertion timeouts, `BrowserIFrameException` for iframe scoping, `BrowserInvalidArgumentException` for API misuse, and `BrowserSetupException` for the lifecycle (launch, attach, Chrome download).

A concrete failure carries one marker from each axis. For example `BrowserConnectionLostException` mixes `BrowserConnectionException` and `BrowserReadException`, so an `Abort.recover[BrowserConnectionException]` handler catches it whether the failure surfaced during a read, a click, or an assertion. The umbrella row used by almost every public method is `Browser & Abort[BrowserReadException]`; setup-time failures are confined to `Browser.run` and never need to be threaded through the body.

Several exceptions carry a typed `Reason` payload nested under the exception: `BrowserElementNotActionableException.Reason` and `BrowserIFrameInvalidException.Reason` are the load-bearing examples. Match on the `Reason` to branch on the specific failure cause (e.g. `Reason.NotVisible(NotVisibleCause.DisplayNone)` vs `Reason.NotInViewport(rect, viewport)`).

## Effect row

`Browser` is the effect row of every operation in this module. Spell it directly in the signature of any function that performs Browser operations:

```scala
def signIn(email: String, pw: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
    for
        _ <- Browser.fill(Browser.Selector.label("Email"), email)
        _ <- Browser.fill(Browser.Selector.label("Password"), pw)
        _ <- Browser.click(Browser.Selector.button("Sign in"))
        _ <- Browser.assertUrl("https://example.com/dashboard")
    yield ()
```

`Browser.run` discharges the `Browser` effect and the internal `Scope` it manages. The caller's residual effect row is `Async & Abort[BrowserReadException | BrowserSetupException]`.

## Cross-platform

kyo-browser compiles and runs on JVM, JavaScript, and Scala Native. The CDP client uses `kyo-http`'s WebSocket and the Chrome process is spawned through `kyo.Command.spawn`; both pieces are cross-platform, so the same `Browser` body works against the same Chrome regardless of which target the application is compiled for.

A single CDP WebSocket carries every command and event for a Chrome process. A `Meter` bounds in-flight commands so the single inbound reader fiber (the only one on JS and Native) is never flooded into a Chrome-initiated connection teardown, and `requestTimeout` turns a silently stalled Chrome into a typed `BrowserConnectionLostException`.

## Demos

Runnable demos live in [`shared/src/test/scala/demo`](shared/src/test/scala/demo). Run any with `sbt 'kyo-browser/Test/runMain demo.<Name>'`.

- [**QuickstartApp**](shared/src/test/scala/demo/QuickstartDemo.scala): minimal quickstart against a self-contained page, exercising `goto`, `fill`, `click`, `assertText`, and `title`.
- [**GitHubTrendingDemoApp**](shared/src/test/scala/demo/GitHubTrendingDemo.scala): SPA navigation that waits for client-rendered content, clicks into a repo, and switches the time range.
- [**GitHubNotFoundRecoveryDemoApp**](shared/src/test/scala/demo/GitHubNotFoundRecoveryDemo.scala): typed recovery from a 4xx navigation, the lenient `failOnHttpError = false` escape hatch, and back/forward history.
- [**HackerNewsDemoApp**](shared/src/test/scala/demo/HackerNewsDemo.scala): top-stories scraper that pages through "More" and uses `evalJson` to align sibling-row records.
- [**HttpBinFormDemoApp**](shared/src/test/scala/demo/HttpBinFormDemo.scala): full form lifecycle (text, textarea, `select`, `check`) verified against the server's JSON echo.
- [**WikipediaSearchDemoApp**](shared/src/test/scala/demo/WikipediaSearchDemo.scala): observes a typeahead XHR with `waitForRequestUrl`, submits via the Enter key, and asserts element order.
- [**WikipediaKitDemoApp**](shared/src/test/scala/demo/WikipediaKitDemo.scala): turns an article into an offline kit (text, infobox PNG, full-page PDF) via `screenshotElement` and `pdf`.
- [**CookieDanceDemoApp**](shared/src/test/scala/demo/CookieDanceDemo.scala): cookie lifecycle (set, reload, verify, delete) and the shared-context `withNewTab` tab model.
- [**RegistryRaceDemoApp**](shared/src/test/scala/demo/RegistryRaceDemo.scala): five concurrent fibers, each in its own tab via `Browser.isolate.fresh`, searching package registries in parallel.
