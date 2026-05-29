# kyo-playwright

`kyo-playwright` is a Kyo effect for driving a real Chromium browser via Microsoft Playwright. The `Browser` effect describes a sequence of page interactions (navigate, click, fill, scroll, screenshot, evaluate JavaScript, intercept requests) as an `ArrowEffect`, and `Browser.run` discharges it by launching Playwright, attaching to a `Page`, and executing the suspended operations against that page.

Because Playwright's `Page` is single-threaded, a `Browser` computation must execute sequentially. The effect intentionally does not interoperate with fork-based combinators like `Async.foreach`; every operation runs in order against the one underlying page. Navigation operations (`goto`, `back`, `forward`, `reload`) panic via `Abort` on non-OK HTTP responses; all other operations only carry the `Browser` effect.

```scala
import kyo.*

val title: String < Sync = Browser.run {
    for
        _ <- Browser.goto("https://example.com")
        s <- Browser.status
    yield s.title
}
```

## Getting started

The `Browser` effect appears in the row of every operation. To run a script you call `Browser.run`, which discharges `Browser` into a `Sync` computation by launching Chromium, attaching a fresh page, and threading the operations through it. The default lifecycle is fire-and-forget: Playwright is created on entry and closed on exit via `Sync.ensure`, so the browser only lives for the duration of the block.

```scala
import kyo.*

val script: String < (Browser & Abort[Nothing]) =
    for
        _    <- Browser.goto("https://example.com")
        text <- Browser.innerText("h1")
    yield text

val out: String < Sync = Browser.run(script)
```

### Browser.run

`Browser.run` has three overloads.

```scala
import kyo.*

// Default 5-second timeout; owns the Playwright lifecycle.
val a: String < Sync = Browser.run {
    Browser.innerText("h1")
}

// Custom timeout; still owns the Playwright lifecycle.
val b: String < Sync = Browser.run(30.seconds) {
    Browser.innerText("h1")
}

// Caller-provided Page; caller owns lifecycle.
def withPage(page: com.microsoft.playwright.Page): String < Sync =
    Browser.run(page, 30.seconds) {
        Browser.innerText("h1")
    }
```

The first two overloads launch and close Chromium themselves. The third overload accepts an existing Playwright `Page` and only sets timeouts on it; the caller is responsible for creating, reusing, and closing the underlying browser. Use the no-page overloads for one-shot scripts and the page overload when you want a page to outlive a single `Browser.run` block.

> **Caution:** the no-page overloads of `Browser.run` close Playwright unconditionally when the block exits. A nested `Browser.run` inside another `Browser.run` will tear down its own Playwright on exit, then the outer one will too. To keep the same browser across composed blocks, create the `Page` yourself and pass it to the page-accepting overload.

### Default timeout

`Browser.defaultTimeout` is `5.seconds`. It is applied as the default per-operation timeout (`page.setDefaultTimeout`) and the default navigation timeout (`page.setDefaultNavigationTimeout`) inside `Browser.run`. Override it per-block by passing a `Duration` to `Browser.run`, or change it from inside a block with `Browser.setTimeout` (see "Waiting and synchronization").

## Driving the page

These are the verbs of a browser script: move the page, click things, type things, fill forms. Each takes a CSS selector or coordinate pair, returns `Unit < Browser` (or `Unit < (Browser & Abort[Nothing])` for navigation), and runs synchronously against the underlying page.

### Navigation

```scala
import kyo.*

val nav: Unit < (Browser & Abort[Nothing]) =
    for
        _ <- Browser.goto("https://example.com/a")
        _ <- Browser.goto("https://example.com/b")
        _ <- Browser.back
        _ <- Browser.forward
        _ <- Browser.reload
    yield ()
```

`goto`, `back`, `forward`, and `reload` all wait for `LOAD` and then check the HTTP response. If the response is not OK the effect panics via `Abort.panic(KyoException(...))`.

> **Caution:** the signature is `Unit < (Browser & Abort[Nothing])`. The `Abort[Nothing]` row is misleading: a non-OK response is reported as a panic, not a typed failure. Handle navigation errors with `Abort.run` at the top of your script if you need to recover; a try/catch on a typed error variant will not catch it.

### Mouse, by selector

```scala
import kyo.*

val clicks: Unit < Browser =
    for
        _ <- Browser.click("#submit")
        _ <- Browser.clickRight("#menu")
        _ <- Browser.doubleClick("#row-3")
        _ <- Browser.doubleClickRight("#row-3")
    yield ()
```

`click` takes an optional `clickCount` parameter that defaults to `1`. `doubleClick` and `doubleClickRight` are convenience forms with `clickCount = 2`.

### Mouse, by coordinate

```scala
import kyo.*

val coords: Unit < Browser =
    for
        _ <- Browser.clickAt(120.0, 80.0)
        _ <- Browser.rightClickAt(120.0, 80.0)
        _ <- Browser.doubleClickAt(120.0, 80.0)
    yield ()
```

Use coordinate clicks when you need to interact with canvas-rendered UIs or PDF viewers that don't expose DOM nodes.

### Pointer

```scala
import kyo.*

val pointer: Unit < Browser =
    for
        _ <- Browser.hover(".tooltip-trigger")
        _ <- Browser.mouseMoveToElement(".target")
        _ <- Browser.dragAndDrop("#source", "#destination")
        _ <- Browser.focus("#search-input")
    yield ()
```

`mouseMoveToElement` resolves the element's bounding box and moves the cursor to its centre, which differs from `hover` only in that the latter goes through Playwright's `hover` helper (with auto-wait).

### Keyboard

```scala
import kyo.*

val keys: Unit < Browser =
    for
        _ <- Browser.focus("#search-input")
        _ <- Browser.typeText("kyo")
        _ <- Browser.keyDown("Shift")
        _ <- Browser.keyUp("Shift")
    yield ()
```

`typeText` types into whichever element is currently focused. `keyDown` and `keyUp` are paired: a press without a release leaves the modifier held until you release it (or the page is closed).

### Forms

```scala
import kyo.*

case class Signup(name: String, email: String, password: String, categories: List[String])

val signup = Signup("Ada", "ada@example.com", "lovelace", List("math", "engineering"))

val form: Unit < Browser =
    for
        _ <- Browser.fill("#name", signup.name)
        _ <- Browser.fill("#email", signup.email)
        _ <- Browser.fill("#password", signup.password)
        _ <- Browser.press("#password", "Tab")
        _ <- Browser.check("#newsletter")
        _ <- Browser.uncheck("#tracking")
        _ <- Browser.select("#country", "us")
        _ <- Browser.selectMultiple("#categories", signup.categories)
    yield ()
```

`fill` clears the field and sets new text; `press` dispatches a single key event on the focused selector; `check` and `uncheck` toggle checkboxes and radio buttons.

> **Note:** `select` and `selectMultiple` return `List[String] < Browser`. The list contains the values Playwright actually applied, not the values you requested. If a requested option is missing, the list comes back shorter (or empty for `select`) with no error raised. Check the length if you need to know whether every option was applied.

## Reading the page

After navigating and interacting, read content back: DOM text, HTML, attributes, presence, count, position, page-level metadata.

### Text and HTML

```scala
import kyo.*

val read: (String, String, Maybe[String], Maybe[String]) < Browser =
    for
        text <- Browser.innerText("article h1")
        html <- Browser.innerHTML("article")
        tc   <- Browser.textContent(".note")
        href <- Browser.attribute("a.docs", "href")
    yield (text, html, tc, href)
```

`innerText` and `innerHTML` return raw `String`; the element must exist or Playwright raises a panic. `textContent` and `attribute` return `Maybe[String]`: `Absent` when the element or attribute is missing, `Present(value)` otherwise.

> **Note:** `innerText` panics if the selector does not match. Use `exists` first or guard the call with `Abort.run` if the element may be absent. `textContent` is the missing-friendly variant when you have the element but the attribute or text may not be there.

### Lookup

```scala
import kyo.*

val lookup: (String, String) < Browser =
    for
        byText <- Browser.findByText("Sign in")
        byRole <- Browser.findByRole("button", "Submit")
    yield (byText, byRole)
```

`findByText` and `findByRole` return a selector `String` derived from the located element's locator. The string is opaque (Playwright's `Locator.first().toString()`); to actually act on the element, pass the returned string to other selector-taking operations like `click` or `innerText`.

### Presence, count, and geometry

```scala
import kyo.*

val geom: (Boolean, Int, Browser.ElementPosition) < Browser =
    for
        present <- Browser.exists("#hero")
        rows    <- Browser.count(".row")
        pos     <- Browser.getElementPosition("#hero")
    yield (present, rows, pos)
```

`exists` returns `true` if the selector resolves within the active timeout, `false` if it raises `PlaywrightException`. `count` returns the number of matching elements (0 if none). `getElementPosition` returns an `ElementPosition(x, y, width, height, centerX, centerY)`.

> **Caution:** `exists` waits up to the configured timeout (default `5.seconds`) before returning `false`. For fast negative checks (e.g. polling whether something has gone away) lower the timeout with `Browser.setTimeout` first, then restore it.

### Page status

```scala
import kyo.*

val s: Browser.Status < Browser = Browser.status
```

`Browser.status` returns `Status(url, title)`, capturing the page's current URL and document title. Useful as a sanity check after navigation, or when redirects make the final URL uncertain.

## Capturing content

Two surfaces produce artifacts from the page: `screenshot` returns an `Image` (a JPEG byte container with file and terminal helpers), and `readableContent` extracts the page's main content as Markdown.

### Screenshots

```scala
import kyo.*

val viewport: Browser.Image < Browser = Browser.screenshot()
val fullPage: Browser.Image < Browser = Browser.screenshot(Int.MaxValue, Int.MaxValue)
val highQ:    Browser.Image < Browser = Browser.screenshot(1920, 1080, quality = 95)
```

The default `screenshot()` resizes the viewport to 1280x720 (then restores it), takes a JPEG at quality 90, and returns the bytes. Passing both `Int.MaxValue` for `width` and `height` is a sentinel for full-page mode: the viewport is left alone and Playwright's `setFullPage(true)` is used instead.

> **Caution:** the `(Int.MaxValue, Int.MaxValue)` pair is a magic sentinel. Any other use of `Int.MaxValue` (e.g. one of the two but not both) is treated as a real viewport size and will be passed to `page.setViewportSize`, which fails. Use the sentinel only when you actually want full-page capture.

### Readable content

```scala
import kyo.*

val md: String < Browser = Browser.readableContent
```

`readableContent` injects bundled `Readability.js` and `turndown.js` into the page, runs Mozilla's Readability extraction over a clone of the document, and converts the result to Markdown.

> **Caution:** when Readability fails to find a main article, the JavaScript returns the literal string `"No readable content found"` (or `"Error extracting readable content: ..."`), and this is returned as the value of `readableContent`. You get a non-empty `String` that is actually an error indicator. If you depend on the output, test for these sentinel strings before treating it as content.

### Image

`screenshot` returns a `Browser.Image`, a thin wrapper around the JPEG bytes with helpers for writing, encoding, and rendering. The same type is also constructible from arbitrary bytes via `Browser.Image.fromBinary` and `Browser.Image.fromBase64`.

```scala
import kyo.*

val saveBinary: Unit < (Sync & Abort[FileWriteException]) =
    Browser.run {
        for
            shot <- Browser.screenshot()
            _    <- shot.writeFileBinary("/tmp/page.jpg")
        yield ()
    }

val saveBase64: Unit < (Sync & Abort[FileWriteException]) =
    Browser.run {
        for
            shot <- Browser.screenshot()
            _    <- shot.writeFileBase64("/tmp/page.b64")
        yield ()
    }
```

`writeFileBinary` writes the raw JPEG bytes; `writeFileBase64` writes the base64-encoded text. Both have overloads taking a `kyo.Path` instead of a `String`. Both lift their write through `Sync & Abort[FileWriteException]`.

Convert to bytes or text directly:

```scala
import kyo.*

def shape(img: Browser.Image): (Int, Int) =
    (img.binary.length, img.base64.length)

def reconstruct(bytes: Array[Byte], text: String): (Browser.Image, Browser.Image) =
    (Browser.Image.fromBinary(bytes), Browser.Image.fromBase64(text))
```

`binary` returns an `IArray[Byte]` and `base64` returns the encoded string.

> **Caution:** `Image.binary` shares the underlying byte array with the `Image` instance via `IArray.unsafeFromArray`. Mutating that array (or anyone holding the raw `data`) mutates the supposedly-immutable `IArray`. Treat the result as read-only.

#### Rendering inline in supported terminals

```scala
import kyo.*

val show: Maybe[String] = Browser.Image.fromBinary(Array.emptyByteArray).renderToConsole()
```

`renderToConsole` emits an ANSI control sequence for iTerm2 or Kitty that displays the image inline in the terminal, returning `Present(sequence)` if the terminal is detected and `Absent` otherwise. Detection reads `TERM_PROGRAM` and `TERM`; tmux, remote terminals, and other emulators silently return `Absent`. Override with the `consoleType` parameter when you want to force a protocol:

```scala
import kyo.*

val forced: Maybe[String] =
    Browser.Image.fromBinary(Array.emptyByteArray)
         .renderToConsole(charsWidth = 40, consoleType = Present(Browser.Image.ConsoleType.iterm))
```

`Browser.Image.ConsoleType` is an `enum` with cases `iterm` and `kitty`, derived from `CanEqual`. `Browser.Image.ConsoleType.get` runs the detection without rendering.

## Waiting and synchronization

Scripts that drive real pages need to wait for asynchronous behaviour: XHR fetches, route resolution, infinite scroll. Two primitives cover this: `waitForNetworkIdle` for in-flight requests, and `setTimeout` for tuning how long every other operation will wait before failing.

### waitForNetworkIdle

```scala
import kyo.*

val settle: Unit < (Abort[Nothing] & Browser) =
    for
        _ <- Browser.goto("https://example.com/results")
        _ <- Browser.waitForNetworkIdle(2000)
    yield ()
```

`waitForNetworkIdle(millis)` blocks until the page reaches the `NETWORKIDLE` load state, capped at `millis` milliseconds. Use it after navigation or interaction that triggers background fetches.

### setTimeout

```scala
import kyo.*

val fastNegative: Boolean < Browser =
    for
        _      <- Browser.setTimeout(200.millis)
        absent <- Browser.exists("#never-appears")
        _      <- Browser.setTimeout(Browser.defaultTimeout)
    yield absent
```

`Browser.setTimeout(duration)` changes the page's default operation timeout from inside a `Browser.run` block. The effect lasts for the rest of the block; reset it explicitly if you only want a temporary reduction.

This is the lever to pull when `exists` is too slow for fast negative checks (see the caution in "Reading the page"). It also affects every other selector-based operation: a tight timeout makes failing operations fail faster.

### Scrolling

Scrolling helpers double as pacing for infinite-scroll pages.

```scala
import kyo.*

val scroll: Unit < Browser =
    for
        _ <- Browser.scrollToElement("#footer")
        _ <- Browser.scrollToTop()
        _ <- Browser.scrollToBottom()
    yield ()

val paginate: Unit < Browser =
    for
        _ <- Browser.scrollToNextPage()
        _ <- Browser.waitForNetworkIdle(1000)
        _ <- Browser.scrollToNextPage(overlap = 0.1)
    yield ()

val rewind: Unit < Browser = Browser.scrollToPreviousPage()
```

`scrollToElement` centres the matching element. `scrollToTop` and `scrollToBottom` jump to the edges. `scrollToNextPage(overlap)` and `scrollToPreviousPage(overlap)` shift by one viewport height, keeping a fraction of the previous frame visible (default `0.2`, meaning 20% overlap). Combine with `waitForNetworkIdle` to drive infinite scroll without racing the loader.

## Interacting with the page beyond the verbs

The typed verbs cover most flows, but real pages have dialogs, third-party requests you want to mock, cookie banners that block content, and behaviour that only JavaScript can express. Four escape hatches cover these.

### Dialogs

```scala
import kyo.*

val confirmAccept: Unit < Browser =
    for
        _ <- Browser.dialogAccept()
        _ <- Browser.click("#delete")
    yield ()

val promptFill: Unit < Browser =
    for
        _ <- Browser.dialogFill("Ada Lovelace")
        _ <- Browser.click("#whoami")
    yield ()
```

`dialogAccept`, `dialogDismiss`, and `dialogFill` register a `page.onDialog` listener that responds to the *next* dialog the page raises. Register the handler before the action that triggers the dialog.

> **Caution:** the dialog handlers attach a listener but do not remove it. Calling `dialogAccept` twice in the same `Browser.run` block stacks two listeners, and both fire on the next dialog. If you need to swap handlers mid-script, use the caller-owned `Page` overload and detach listeners through Playwright directly.

### Route interception

```scala
import kyo.*

val mock: Unit < (Abort[Nothing] & Browser) =
    for
        _ <- Browser.reroute("**/api/users", status = 200, body = "[]", contentType = "application/json")
        _ <- Browser.goto("https://example.com/dashboard")
        _ <- Browser.unroute("**/api/users")
    yield ()

val clearAll: Unit < Browser = Browser.unrouteAll()
```

`reroute(url, status, body, contentType)` installs a Playwright route that fulfils every matching request with the same static response. `unroute(url)` removes the rule for a specific pattern; `unrouteAll()` clears every installed rule.

> **Note:** `reroute` is static-response only. There is no callback variant to inspect the incoming request or vary the response per call. For dynamic interception, drop down to a caller-owned `Page` and use Playwright's native `page.route` API.

### Cookie banners

```scala
import kyo.*

val maybeAccepted: Boolean < Browser = Browser.acceptCookies()
```

`acceptCookies` is a heuristic. It iterates a hard-coded list of CSS selectors (`button[id*='cookie' i]`, `button[aria-label*='cookie' i]`, regex-matched buttons mentioning accept/agree/consent, and similar) looking for a clickable cookie-consent button, swallows any exceptions, and returns `true` if it clicked one.

> **Caution:** `acceptCookies` is selector heuristic, not a guarantee. Non-English banners, custom widgets, and shadow-DOM dialogs are common false negatives. Treat the `false` return as "best effort failed" and have a fallback (use `findByText` or `findByRole` with the actual banner copy you can see).

### Raw JavaScript

```scala
import kyo.*

val ua: String < Browser =
    Browser.runJavaScript("return navigator.userAgent")
```

`runJavaScript(js)` wraps `js` as an arrow function body (`() => { $js }`) and evaluates it on the page. The result is stringified via `"" + p.evaluate(...)`.

> **Caution:** non-string return values are converted with JVM `toString`, which gives `[object Object]` shapes for JS objects. Serialise to JSON inside the script (`return JSON.stringify(value)`) and parse on the Scala side if you need structured data. The wrapper also does not JSON-escape `js`: unbalanced braces in the source break the wrapping.

## Putting it together

A typical script combines several clusters: navigate, wait for the page to settle, interact, read, and capture. The following walks through a small local form, fills it, screenshots the result, and asserts a few facts.

```scala
import kyo.*

case class Signup(name: String, email: String, password: String, categories: List[String])

val signup = Signup("Ada", "ada@example.com", "lovelace", List("math", "engineering"))

val flow: (Browser.Status, String, Boolean, Browser.Image) < (Browser & Abort[Nothing]) =
    for
        _        <- Browser.goto("file:///tmp/signup.html")
        _        <- Browser.waitForNetworkIdle(1000)
        _        <- Browser.fill("#name", signup.name)
        _        <- Browser.fill("#email", signup.email)
        _        <- Browser.fill("#password", signup.password)
        _        <- Browser.check("#newsletter")
        _        <- Browser.selectMultiple("#categories", signup.categories)
        _        <- Browser.click("#submit")
        _        <- Browser.waitForNetworkIdle(500)
        status   <- Browser.status
        result   <- Browser.innerText("#formResult")
        accepted <- Browser.exists(".confirmation")
        shot     <- Browser.screenshot(Int.MaxValue, Int.MaxValue)
    yield (status, result, accepted, shot)

val program: Unit < (Sync & Abort[Nothing] & Abort[FileWriteException]) =
    Browser.run(30.seconds) {
        for
            tup            <- flow
            (st, msg, ok, shot) = tup
            _              <- shot.writeFileBinary("/tmp/signup.jpg")
        yield ()
    }
```

The script runs sequentially: the page loads, the form fills, the submit happens, and the screenshot is taken only after the network settles. Because `Browser` operations cannot fork, every step is observable in order and the final `Image` is taken against the page in the exact state described.

## Extension and internals

`Browser.Op[+A]` is the wire-level base for every browser operation. Each `Browser.<verb>` convenience method constructs a concrete `Op` case class (e.g. `Click(selector, true, 1)`, `Fill(selector, text)`, `Screenshot(width, height, quality)`) and suspends it into the effect row. Most users never name `Op` directly. Reach for it when you are writing a custom interpreter for the effect (e.g. recording, replay, snapshot testing) that reinterprets the existing variants. `Browser.Op` is sealed; custom interpreters consume the built-in variants but cannot add new ones.

```scala
import kyo.*

// Construct an op without suspending it (e.g. for inspection in tests).
val op: Browser.Op[Unit] = Browser.Op.Click("#submit", left = true, clickCount = 1)
```

`Op` provides three suspension helpers:

- `.suspend: A < Browser`, which injects the op and yields its result.
- `.suspendDiscard: Unit < Browser`, which injects and discards the result (used by every void verb).
- `.suspendWith(f: A => B < S): B < (Browser & S)`, which injects and continues with `f` (used by navigation to call `handleResponse`).

The `unsafeRun: Page => A` parameter is `private[Browser]`; the standard handler in `Browser.run` is the only entry point that calls it. The full catalog of built-in ops lives in `Browser.Op`: navigation (`Goto`, `Back`, `Forward`, `Reload`), mouse (`Click`, `ClickAt`, `Hover`, `MouseMoveToElement`, `DragAndDrop`, `Focus`), keyboard (`TypeText`, `KeyDown`, `KeyUp`), dialogs (`DialogAccept`, `DialogDismiss`, `DialogFill`), routing (`Reroute`, `Unroute`, `UnrouteAll`), forms (`Fill`, `Press`, `Check`, `Uncheck`, `Select`, `SelectMultiple`), DOM (`InnerText`, `InnerHTML`, `TextContent`, `Attribute`, `FindByText`, `FindByRole`, `GetElementPosition`, `Exists`, `Count`), content (`GetStatus`, `ReadableContent`, `Screenshot`), scroll (`ScrollToElement`, `ScrollToTop`, `ScrollToBottom`, `ScrollToNextPage`, `ScrollToPreviousPage`), and utils (`WaitForNetworkIdle`, `AcceptCookies`, `RunJavaScript`, `SetTimeout`).

### Op.HttpResponse and Response.toHttpResponse

Navigation ops produce `Browser.Op.HttpResponse(ok, code, text)` and pass it to `handleResponse`, which panics when `!ok`. The bridge from Playwright's `Response` is an extension method:

```scala
import com.microsoft.playwright.Response
import kyo.Browser.Op.toHttpResponse

def toKyo(r: Response): Browser.Op.HttpResponse = r.toHttpResponse
```

This is the surface you extend if you want different error semantics on navigation (for example, materialising failures as typed `Abort[E]` instead of panics). Subclass `Browser.Op` with your own navigation op that calls `toHttpResponse` and `suspendWith` against a handler of your choosing.
