# Contributing to kyo-browser

This is module-specific guidance for kyo-browser. It complements, and does not
replace, the root `CONTRIBUTING.md`.

Before working on kyo-browser, read `CONTRIBUTING.md` at the repo root for global
conventions covering naming, types, method signatures, `using`-clause ordering,
inline guidelines, scaladoc structure, visibility tiers, overload delegation, the
unsafe boundary, and the general test framework (base-trait hierarchy, `run {}`
wrapper, `untilTrue`). Everything in this guide is specific to the CDP driver, the
settlement model, and the Chrome test infrastructure that make this module
different from the rest of the codebase.

**The one thing to internalize:** kyo-browser's selling point is transparent
settlement. Interactions and assertions auto-wait for an observable settled state,
so callers never write explicit sleeps (`Browser.scala:37-39`). The waits that must
catch a fast in-page transient run their entire loop inside one `awaitPromise=true`
eval, because cross-CDP polling aliases or desyncs the in-page observer
(`MutationSettlement.scala:126-131`, `StabilitySampler.scala:8-19`). If you break
that constraint, the module silently stops catching the flickers it exists to
catch. The settlement section below is the deepest in this guide for that reason.

## Table of contents

1. [Architecture at a glance](#architecture-at-a-glance)
2. [Transparent settlement (the headline invariant)](#transparent-settlement-the-headline-invariant)
3. [Conventions](#conventions)
4. [Extension recipes](#extension-recipes)
5. [Testing](#testing)
6. [Adding a new method: decision checklist](#adding-a-new-method-decision-checklist)

## Architecture at a glance

The public surface is one opaque type: `opaque type Browser <: Async =
Env[BrowserTab] & Async` (`Browser.scala:149`). The `Env` is hidden so callers
cannot extract or share the tab directly, and so two fibers cannot accidentally
share a single CDP tab (`Browser.scala:107-110, 149`). Because there is no safe
default split for a single CDP session, the compiler refuses to derive an
`Isolate` automatically, so concurrent combinators demand an explicit one.

The module sits on top of `kyo-http`: the CDP transport is built on
`HttpClient.webSocket`, so the layer above kyo-browser in the stack is the
WebSocket client and below it is the Chrome process (`build.sbt:1139`,
`internal/CdpClient.scala:245`).

Internally the stack layers top-down, each layer composing the one below it:

| Layer | Type | Responsibility |
|-------|------|----------------|
| Public API | `Browser` (`Browser.scala:149`) | Actions, reads, assertions, scoped wrappers |
| Settlement / readiness | `Actionability`, `MutationSettlement`, `NavigationWatcher`, `BrowserAssertion` / `StabilitySampler` | The four auto-wait gates |
| Resolution / eval | `Resolver`, `BrowserEval` | `Selector` to typed `NodeRef`; page-side JS eval |
| Typed CDP commands | `CdpBackend` (`internal/CdpBackend.scala:6-13`) | One `private[kyo]` method per CDP endpoint |
| Transport / multiplexing | `CdpClient` (`internal/CdpClient.scala:6-19`) | WebSocket exchange, dialog routing, frame fan-out |
| Process | `BrowserLauncher`, `SharedChrome`, `ChromeDownloader` | Spawn / share / provision Chrome |

`CdpBackend` wraps each CDP endpoint as a raw `sender.send(...)` plus a typed JSON
decode, so call sites in `Browser` never write string-literal CDP calls
(`internal/CdpBackend.scala:6-13`). `CdpClient` is the transport beneath it: it
pairs each outbound command with its response over a single WebSocket via
`Exchange`, routes dialogs, fans out frame events, and bounds in-flight commands
(`internal/CdpClient.scala:6-19`). A single relay fiber inside `CdpClient.init`
owns the WebSocket: an outbound stream pumps the `outbound` channel into `ws.put`,
a receiver pumps `ws.stream` into the `inbound` channel, and `Async.race(sender,
receiver)` runs both (`internal/CdpClient.scala:251-262`).

`BrowserLauncher` is the process layer: it spawns Chrome with
`--remote-debugging-port=0`, polls `${user-data-dir}/DevToolsActivePort` for the
address Chrome writes, and returns the `ws://127.0.0.1:<port><path>` URL the
transport connects to (`internal/BrowserLauncher.scala:12-21`). `SharedChrome`
shares only the WebSocket URL (each caller still makes its own `CdpClient`),
launching Chrome lazily inside a long-lived fiber held open by `Async.never` and
torn down when the kyo scheduler shuts down (`internal/SharedChrome.scala:5-12`).
`ChromeDownloader` is the binary-provisioning layer below launch: it downloads and
caches `chrome-headless-shell` from Google's Chrome-for-Testing archive, derives
the platform tuple from `kyo.System.operatingSystem` / `kyo.System.architecture`,
and aborts on `linux-arm64` because no artifact is published
(`internal/ChromeDownloader.scala:5-18`).

`BrowserTab` is the per-session handle the `Browser` effect carries. It holds the
`targetId`, `sessionId`, the shared `CdpClient`, the isolated `browserContextId`,
and per-tab atomic state (frame contexts, root frame, console/response
registration guards, viewport, download policy). Its `session` field is the
`client.withSession(sessionId)` pair every CDP call issues against
(`internal/BrowserTab.scala:19-35`). `BrowserTabSetup.attachAndSetupTab` is the
single entry point that creates a fresh isolated browser context, attaches a CDP
target, and installs the per-tab trackers; it is the one place the four-layer
containment (process / context / tab / iframe) is assembled from the bottom
(`internal/BrowserTab.scala:38-43`, `internal/BrowserTab.scala:214`). Every action
path depends on four CDP domains (Page, Runtime, DOM, Network) being enabled;
`enableDomains` fires them with `Async.zip` to amortise the round-trip, then
enables focus emulation (`internal/BrowserTab.scala:245-253`).

Element resolution is centralised in `Resolver`: it maps a `Selector` to a typed
`NodeRef` via the `objectId + requestNode` pipeline (`Runtime.evaluate` ->
`DOM.requestNode` -> `DOM.describeNode`), the shared substrate every interaction
method resolves through (`internal/Resolver.scala:7-12`, `internal/Resolver.scala:35`).

**Representative call flow.** `Browser.run(launch)` is the lifecycle end to end:
launch Chrome -> connect a `CdpClient` -> attach and setup a tab -> run the body
bound to that tab, all under one internal `Scope.run` so the caller's effect row
does not carry `Scope` (`Browser.scala:255-266`). `click` is the representative
action flow: dependency direction is top-down through the layers, a public
`Browser.*` action composes the actionability and settlement gates -> `Resolver` /
`BrowserEval` -> `CdpBackend` typed wrappers -> `CdpClient.send` transport ->
Chrome (`Browser.scala:442-454`). Internal callers reach the hidden
`Env[BrowserTab]` only through `Browser.use` (read the tab) and `Browser.runOn`
(bind a tab), which wrap `Env.use` / `Env.run` so internal code never sees the
opaque type's underlying `Env` (`Browser.scala:3453-3462`).

CDP domain wrappers that need extra typing beyond `CdpBackend` live in the
`kyo.internal.cdp` sub-package: `Accessibility` wraps `Accessibility.getFullAXTree`
with a custom polymorphic `Schema`, and `PageDownload` wraps
`Page.setDownloadBehavior` (`internal/cdp/Accessibility.scala:1-16`,
`internal/cdp/PageDownload.scala:1-11`).

### Visibility and cross-platform split

All transport and protocol internals live under `kyo.internal` and are
`private[kyo]`: `CdpBackend`, `CdpClient`, the wire helpers (`CdpWire`'s
`CdpBase64Decode` / `CdpEvalDecoder` / `ExceptionDetailsFormat`), and `BrowserTab`
are visible to `Browser` and internal helpers but not to external callers; only
`kyo.Browser` and the `BrowserException` hierarchy are public
(`internal/CdpBackend.scala:11-12`, `internal/BrowserTab.scala:19`).

Almost all source is cross-platform: every file under
`kyo-browser/shared/src/main/scala/kyo/**` (the entire `Browser` API plus all
`internal` modules) compiles on JVM, JS, and Native; platform code is the
exception, not the rule (`internal/BrowserLauncher.scala:70-73`). The only
platform-specific main source is `BrowserLauncherPlatform`, a one-method seam
(`registerShutdownHook`). JVM and Native share one implementation under
`jvm-native/src/main` that registers a `Runtime.addShutdownHook` thread to
SIGTERM-kill a leaked Chrome; the split exists because that hook is a JVM/Native
runtime ABI with no JS equivalent (`jvm-native/src/main/scala/kyo/internal/BrowserLauncherPlatform.scala:5-25`).
The JS copy is a no-op: JS has no JVM shutdown hooks, so process lifecycle is left
to the JS runtime, which is why the seam is split rather than living in `shared/`
(`js/src/main/scala/kyo/internal/BrowserLauncherPlatform.scala:7-9`).

## Transparent settlement (the headline invariant)

### The goal

Browser automation usually fails not on the action itself but on the action firing
before the page is ready (`Browser.scala:37-39`). `Browser` answers this so that
callers never write `Async.sleep`: every interaction and every assertion
auto-waits for an observable settled state, and the wait happens automatically
inside the operation (`Browser.scala:37-39`). A contributor who adds a new method
without wiring it into the right settlement mechanism reintroduces exactly the
race the module exists to remove.

There are four settlement mechanisms, each tied to one kind of operation. The
first three (navigation, mutation, assertion) are the auto-wait gates the public
docs name (`Browser.scala:37-39`); the actionability gate is the per-attempt
readiness check that fronts every interaction (`Browser.scala:442-454, 504-514`).

### The four mechanisms

**1. Actionability gate (interactions: every `click` / `fill` / `hover` / `press`
/ `check` / `select` / `dragAndDrop` / `focus` / `setFiles`).** Each interaction
is wrapped as `Actionability.withRetry { Actionability.withActionable(selector,
...) { ref => ... } }`. The actionability gate is the per-attempt readiness check;
`withRetry` is the surrounding poll loop (`Browser.scala:442-454, 504-514`).
`Actionability.check` runs seven sub-checks (attached -> fillable -> visible ->
scroll-into-view -> stable -> hittable -> enabled) inside ONE `Runtime.evaluate`,
ordered so the earliest failure wins, so a detached node surfaces as
`Reason.NotAttached`, not `Reason.NotVisible`; success returns an `ActionableRef`
carrying the rect center so the caller does not need a second round-trip
(`Actionability.scala:7-31`). The stability sub-check samples the bounding rect
across two ~16ms ticks driven by `setTimeout`, NOT `requestAnimationFrame`, because
RAF callbacks stall on Chromium right after a JS dialog is dismissed, hanging the
`awaitPromise=true` eval forever (`Actionability.scala:160-165`).

**2. Mutation settlement (state-changing actions: `click` / `fill` / `check` /
`select` / `press` / `uncheck`).** After a state-changing action fires,
`MutationSettlement.afterAction` installs a window-level `MutationObserver`,
records the timestamp of the last mutation, and polls until `now - lastMutation >=
quiescenceWindow`, so the caller sees the settled DOM, not the mid-render snapshot
React/Vue are in (`MutationSettlement.scala:6-12`). The observer is ALWAYS rooted
at `document.body` regardless of `scopeSelector`; the `scopeSelector` arg is
retained for future opt-in scoping but is currently unused, because onclick
handlers commonly mutate sibling DOM a target-scoped observer would never see
(`MutationSettlement.scala:25-28`). Quiescence has two regimes keyed off a
`startCount` snapshot taken at action-complete: if no mutation has landed it keeps
polling only until `mutationFirstMutationGrace` elapses (the fast path for no-op
clicks), otherwise it waits for `quiescenceWindow` of quiet bounded by
`mutationSettlementTimeout`, raising a timeout if the DOM never stops churning
(`MutationSettlement.scala:49-58`). A single window-level observer is shared across
concurrent `afterAction` calls via a reference count (`window.__kyoMutObsRef`):
install bumps, release decrements, and the observer disconnects when the count
hits 0 (`MutationSettlement.scala:16-19`).

**3. Navigation settlement (navigation: `goto` / `back` / `forward` / `reload` /
`expectNavigation`, and nav-intent clicks).** These arm `NavigationWatcher`, which
installs an in-page recorder monkey-patching `history.pushState` / `replaceState`
plus a `beforeunload` listener, then polls readyState (and optionally the
network-idle window) until the requested `Settle` mode is satisfied. Detection is
JS-polling, keeping the CDP event channel free (`NavigationWatcher.scala:5-19`).
The three settle modes form a strictly-increasing strength order:
`DomContentLoaded` (readyState >= interactive) < `Load` (readyState complete) <
`NetworkIdle` (load fired AND no in-flight fetch/XHR for `networkIdleWindow`);
`NetworkIdle` is the default for navigation methods (`Browser.scala:3034-3047`).
`armAround` (nav-neutral clicks) applies a fast path: after the trigger it polls
within `navigationGraceWindow`, and if nothing navigated it returns immediately,
making it safe to wrap around ANY click. `armAroundNavigation` (`goto` /
`expectNavigation`) sets `assumeWillNavigate = true` and unconditionally waits for
the URL to commit, because the caller explicitly asked Chrome to navigate
(`NavigationWatcher.scala:36-47, 216-241`). Network tracking is reinstalled INSIDE
the settle loop, not before the trigger, because a navigation wipes all JS state on
the destination page (`NavigationWatcher.scala:283-299`). When `NetworkIdle` never
quiesces within the load budget, the watcher degrades to `Load` semantics at the
deadline (logs a warning and returns success if the load event fired, URL changed,
and the response is OK) rather than failing; the deadline-exhaustion decision matrix
is split into a pure `decidePending` function so it can be unit-tested without a
live Browser (`NavigationWatcher.scala:340-345, 433-436, 387-395`).

**4. Assertion stability (assertions and retrying reads: `assert*` / `waitFor*`).**
These route through `BrowserAssertion.withStability`: each attempt is one cheap
in-page read, and only if that read matches the predicate does a
`StabilitySampler.sampleWindow` run over the same JS expression; the outer `Retry`
reschedules attempts per `retrySchedule` (`BrowserAssertion.scala:6-9, 26-35`). An
assertion only succeeds when BOTH the first probe and the stability sample satisfy
the predicate AND the value held constant for the entire `assertionStabilityWindow`;
if it changed mid-window the attempt fails and retries, which is what makes a
missing match a typed timeout, not an instant `false` (`BrowserAssertion.scala:29-35`).
A transient JS exception mid-flicker (for example, reading a property of a node
that just detached) is itself instability, so the in-page `read()` maps a thrown
probe to a distinct sentinel that compares unequal to any real value, registering
as a change; making `read()` swallow-and-return-empty would mask genuine flicker
(`StabilitySampler.scala:52-56`).

### The in-page awaitPromise loop constraint

This is the constraint behind the headline. Two mechanisms run their ENTIRE wait
loop inside one `awaitPromise`-backed JS eval, and neither can be rewritten to poll
across CDP round-trips:

- The whole mutation quiescence wait runs inside ONE `awaitPromise`-backed eval.
  Polling the observer's state via repeated `Runtime.evaluate` calls desyncs on
  Chrome: observer callbacks stop delivering after the first batch when CDP
  interleaves between mutations. Driving the entire loop in-page lets the observer,
  `setInterval`, and observer callbacks cooperate on the main thread without CDP
  interruption (`MutationSettlement.scala:126-131`).
- The stability sampler runs the ENTIRE sampling loop inside one `awaitPromise`-backed
  eval. Sampling via N separate CDP round-trips aliases because round-trip latency
  is load-dependent (hundreds of ms under Native full-suite load), so coarse samples
  cannot see a flicker faster than the sample spacing; the in-page loop is
  alias-proof (`StabilitySampler.scala:8-19`).

There is also a hard single-round-trip invariant on the actionability check:
`Actionability.check` must complete in exactly one CDP round trip; every JS bundle
embeds a marker so the round-trip count is observable, and there must be exactly one
evaluate carrying that marker per `check` call (`Actionability.scala:84-90`).

If you add or change a wait that must catch a fast in-page transient, the loop must
live entirely inside one `awaitPromise=true` eval. Do not "simplify" it into a
sequence of separate `Runtime.evaluate` polls.

### Config knobs

All per-operation tuning lives in a single `SessionConfig` carried in a fiber
`Local` (`configLocal`); every retry, settle, and assertion loop reads it via
`configLocal.use`, and `withConfig` installs a scoped override (`Browser.scala:3446,
3257-3258`). Config splits into launch-time (`LaunchConfig`, frozen once Chrome
starts) and session-time (`SessionConfig`, mutable per scope); `withConfig` accepts
ONLY `SessionConfig`, so a contributor cannot write `withConfig(_.executable(...))`
and have it silently no-op. The compiler enforces the split (`Browser.scala:88-90,
200-202`).

| Knob | Default | Role |
|------|---------|------|
| `retrySchedule` | `100ms` x up to `8s` | Outer poll loop for interactions, assertions, retrying reads (`Browser.scala:3238-3253`) |
| `loadSchedule` | `100ms` x up to `5s` | Navigation load polling (`Browser.scala:3238-3253`) |
| `networkIdleWindow` | `500ms` | Quiet window required for `NetworkIdle` (`Browser.scala:3238-3253`) |
| `mutationQuiescenceWindow` | `50ms` | DOM-quiet window before mutation settlement returns (`Browser.scala:3238-3253`) |
| `mutationSettlementTimeout` | `2s` | Upper bound on mutation quiescence (`Browser.scala:3238-3253`) |
| `mutationFirstMutationGrace` | `100ms` | Fast-path grace when no mutation lands (`Browser.scala:3238-3253`) |
| `assertionStabilityWindow` | `100ms` | Window the assertion value must hold constant (`Browser.scala:3238-3253`) |
| `stabilitySampleInterval` | `4ms` | In-page sample spacing (`Browser.scala:3238-3253`) |
| `navigationGraceWindow` | `300ms` | Fast-path window for nav-neutral clicks (`Browser.scala:3238-3253`) |
| `navigationPostSettleWindow` | `300ms` | Post-settle window (`Browser.scala:3238-3253`) |

The `8s` `retrySchedule` budget is deliberately generous to accommodate SPA
hydration (React/Vue/Angular shells painted before content); callsites that want
fail-fast should install a tighter schedule via `withConfig(_.retrySchedule(...))`
(`Browser.scala:3234-3236`). `withTimeout(d)` is the shortcut that caps both
`retrySchedule` and `loadSchedule` at `maxDuration(d)`; it bounds the retry budget,
it does NOT abort the way `Async.timeout` does, so a failure to settle within `d`
aborts with the usual typed assertion timeout (`Browser.scala:216-230`).

Three knobs have `Duration.Zero` opt-outs:

- `mutationQuiescenceWindow = Duration.Zero` opts out of mutation settlement
  entirely (returns immediately after the action); useful in tests where DOM churn
  is expected (`MutationSettlement.scala:31-33, 44`).
- `assertionStabilityWindow = Duration.Zero` skips phase 2 entirely (first-match
  behaviour); the escape hatch for callers needing raw speed via
  `withConfig(_.assertionStabilityWindow(Duration.Zero))`. The `100ms` default is
  one polling interval at the default retry schedule (`BrowserAssertion.scala:36-37`,
  `Browser.scala:3274-3279`).

### The retry-channel-narrowing trap

`Actionability.withRetry` narrows its retry channel to `BrowserElementException`,
NOT `BrowserMutationException`. If you widen it back, the retry will catch
`MutationSettlement.afterAction`'s settlement timeout and loop the whole
interaction, blowing past the `mutationSettlementTimeout` budget. Only
element-not-found and not-actionable are legitimate retry triggers here
(`Actionability.scala:479-495`). This trap matters because the exception hierarchy
is nested by capability: `BrowserMutationException <: BrowserReadException` and
`BrowserAssertionException <: BrowserMutationException`, so a single
`Abort[BrowserReadException]` channel catches every settlement, interaction, and
assertion failure uniformly (`BrowserException.scala:34-41, 71-79`). A
mutation-settlement timeout surfaces as `BrowserAssertionTimedOutException` ("DOM
never quiesced" is an assertion-like failure from the caller's perspective), which
is why it is a `BrowserMutationException` subtype and must NOT be caught by the
interaction retry loop (`MutationSettlement.scala:13-14, 196-204`).

### Decision rules for a new method's settlement

- A new interaction reuses `Actionability.withRetry { Actionability.withActionable(...)
  { ref => MutationSettlement.afterAction(<action>)(scopeSelector = Present(selector))
  } }` (`Browser.scala:457-462, 532-541`).
- `requireEnabled = false` is passed by `hover` and `press` so the disabled probe is
  skipped: real browsers still fire `mouseover` / `keydown` against disabled controls,
  so blocking them would be wrong. Visibility, attached, stability, and hittable still
  run (`Browser.scala:464-474`, `Actionability.scala:46-48`). Decide per method
  whether the disabled probe applies.
- On a nav-intent click, `Browser.click` skips mutation settlement entirely and arms
  the navigation watcher instead, because the destination is a fresh DOM and the old
  page's observer is wiped by the navigation (`Browser.scala:447-452`). A new
  nav-capable action must make the same choice.
- A new navigation entry-point must replicate two traps or it will hang.
  `goto` short-circuits to a no-op when the tab is already at the target URL, because
  Chrome's `Page.navigate` against the current URL does NOT actually navigate, so the
  watcher would wait for a URL-change signal that never arrives and time out;
  refresh callers must use `reload` (`Browser.scala:329-334`). `goto` to a `data:`
  URL auto-downgrades the default `NetworkIdle` to `Load`, because `data:` URLs
  produce no network traffic so the idle window never opens; an explicit non-default
  settle is honored as-is (`Browser.scala:325-328`).

## Conventions

### Effect row and signatures

Every effectful `Browser` method declares an explicit return type of the form
`A < (Browser & Abort[BrowserReadException])` and takes `Frame` as the trailing
`using` parameter (`Browser.scala:1372, 1382`). `BrowserReadException` is the
catch-all error row a new method aborts with; the hierarchy is built so that
`BrowserMutationException` and `BrowserAssertionException` extend it, so an
`Abort[BrowserReadException]` channel catches every browser failure except
lifecycle/setup. New element/assertion exceptions must keep extending up to
`BrowserReadException` (`BrowserException.scala:28-34, 41`).

### Exception hierarchy

The hierarchy layers two axes (`BrowserException.scala:11-17, 107-109`):

- An operation-row spine: `BrowserReadException` <- `BrowserMutationException` <-
  `BrowserAssertionException`. This controls which `Abort[...]` rows catch what.
- Orthogonal topical marker traits: `BrowserConnectionException`,
  `BrowserElementException`, `BrowserNavigationException`, `BrowserScriptException`,
  `BrowserIFrameException`, `BrowserSetupException`. A concrete case mixes one or
  more in to declare its topic.

A new exception picks one row position and one or more topical markers. Every
concrete exception is a `final case class ... (using Frame) extends
BrowserException(<rendered message>) with <markers> derives CanEqual`: the message
is rendered in the `extends` clause from the case fields, and `derives CanEqual` is
mandatory so the typed error can be pattern-matched in `Abort.recover`
(`BrowserException.scala:174-177, 277-279`). An exception with more than one
construction path gets a companion `object` of named smart constructors rather than
overloaded `apply`s with bare strings; each constructor names the failure kind and
formats the message (`BrowserException.scala:146-162, 357-375`).

`BrowserAssertionTimedOutException` is constructed via the `(expected, actual)`
two-arg overload that auto-derives `check` from the enclosing method name through
the implicit `Frame.calleeName`, so `assertVisible` failures already read
`"assertVisible"` without the call site passing the name. New `assert*` / `waitFor*`
methods rely on this rather than repeating the method name as a string literal
(`BrowserException.scala:362-363`, `Browser.scala:947-948`).

A method that only validates an argument before any CDP call aborts
`BrowserInvalidArgumentException("<methodName>", <message>)` with the public method
name as the first field, distinguishing "called the API wrong" from a runtime
failure. `setFiles` and `setDownloadBehavior` both gate absolute-path arguments
this way (`Browser.scala:758-759, 2477-2478`). When a public read decodes wire
JSON, a malformed or unexpected payload is surfaced as a typed `Abort`
(`BrowserProtocolErrorException.decodeFailure` / `BrowserAssertionTimedOutException`)
rather than thrown, with the method name passed as the diagnostic tag; `consoleLogs`
and `value` both do this (`Browser.scala:2087-2088, 1473-1474`).

### The return-type discriminator

The return type of a read encodes what "nothing matched" means. Pick the row by
the question the method asks, not by convenience.

| Method kind | Return type | Selector miss behaviour | Examples / citation |
|-------------|-------------|-------------------------|----------|
| Geometry / accessibility read (absence is legitimate) | `Maybe[...]` | `Absent` / `Maybe.empty`, no abort | `boundingBox`, `role`, `accessibleName` (`Browser.scala:1590-1592, 1631-1632`) |
| Content read requiring the element to exist | bare value (`String`, `Int`) | abort `BrowserElementNotFoundException` via `selectorNodeDescription(Selector.toNode(selector))` | `text`, `attribute`, `selectionStart`, `value` (`Browser.scala:1404-1408, 1461-1464`) |
| Existence question ("is X here?") | `Boolean` | `false`, the negative answer IS the answer | `exists` (`Browser.scala:1517-1525`) |
| Property predicate ("is X visible/enabled?") | `Boolean` | abort `BrowserElementNotFoundException` (property is undefined without an element) | `isVisible`, `isEnabled`, `isChecked` (`Browser.scala:1478-1489`) |
| Count read | `Int` | `0`, not an abort | `count` (retries CDP transients), `countNow` (point-in-time) (`Browser.scala:1431-1436, 1445-1446`) |
| Bulk read (`*All`) | `Chunk[...]` | `Chunk.empty` via a single round-trip empty-fast-path before the retry loop | `textAll`, `attributeAll` (`Browser.scala:1667-1672, 1696-1702`) |

The discriminator twins are the high-value pairs: `exists` returns `false` on miss,
`isVisible` aborts on miss (`Browser.scala:1517-1525, 1478-1489`); `count` retries
`BrowserMutationException` CDP transients, `countNow` is the explicit no-retry
point-in-time variant (`Browser.scala:1431-1436, 1445-1446`). The `<read>` vs
`<read>Now` naming distinguishes "retry CDP transients" from "point-in-time,
surface transients immediately."

Element presence is modeled in the return type via `Maybe[NodeRef]` /
`Chunk[NodeRef]`, never a string sentinel; `Resolver` is the single resolution
pipeline (`Resolver.scala:7-11`). When a single read could mean two things, the API
splits into two intent-revealing methods rather than one ambiguous one, and each
scaladoc points at its sibling: `assertNoVisibleText` / `hasNoVisibleText` read
`textContent`, `assertValueEmpty` / `hasEmptyValue` read the `value` property, so a
single `isEmpty`-style method's dual-semantics footgun is avoided
(`Browser.scala:1539-1549, 1562-1572`).

### Resolution safety

Resolution is mutation-safe via the `objectId + requestNode` pipeline: `requestNode`
is keyed by a stable JS objectId handle, NOT by document tree state, so DOM
mutations between `Runtime.evaluate` and `requestNode` cannot invalidate the handle
and concurrent clicks on the same tab do not race. Do not "simplify" this to
`DOM.describeNode(objectId)` directly: that returns a placeholder backendNodeId that
collides across distinct elements in a fresh tab, where every element comes back as
backendNodeId 3 (`Resolver.scala:12-26`). A CDP "Could not find node with given id"
(stale node) is a transient resolution miss, NOT a protocol fault: `resolveOne`
drops it to `Absent` and `resolveAll` re-raises it as a retryable
`BrowserElementNotFoundException` so the enclosing loop re-resolves a CLEAN
snapshot. Dropping just the stale node in `resolveAll` would return a partial
stable-looking set and mask a flickering page (`Resolver.scala:187-192, 156-167`).

### Scoped wrappers and event APIs

A `with*` method is a scoped wrapper returning `A < (Browser & S)` (or `&
Abort[BrowserReadException] & S`) that takes the body as a trailing by-name `A <
(Browser & S)` parameter and restores the prior state on body exit (success,
failure, OR interruption). Pure-config wrappers use `Local.let`; tab/resource
wrappers use `Scope.run` + `Scope.acquireRelease` / `Scope.ensure`
(`Browser.scala:2237-2239, 1904-1925`). A `with*` wrapper over per-tab sticky CDP
state caches the prior value on the `BrowserTab` (`tab.viewportOverride`,
`tab.downloadPolicy`), and on exit re-applies the prior `Present(...)` override or
clears/resets to the launch-time default on `Absent`, so nested `with*` calls
compose (`Browser.scala:1909-1922, 2446-2456`).

An event-observation API ships as a pair: a callback `on*[A, S](f: Event => Unit <
(Browser & S))(action: ...)(using Frame, Isolate[S, Sync, S])` that registers a
per-session handler before `action` runs and tears it down via `Scope.run` /
`Scope.ensure`, plus a `record*[A, S](body: ...)` convenience implemented on top
that captures events into an `AtomicRef[Chunk[Event]]` and returns `(Chunk[Event],
A)`. New event surfaces add both (`Browser.scala:2569-2579, 2190-2211`). Per-session
handler/recorder registries on the `CdpClient` are updated through `getAndUpdate`
capturing the previous map, and the restore closure re-installs the prior entry
(`Present`) or removes the key (`Absent`), giving LIFO nesting; the restore is wired
with `Scope.run(Scope.ensure(restore).andThen(...))` so it fires on success,
failure, AND interruption (`Browser.scala:2162-2171, 2538-2545`).

A `waitFor*` read is the read-flavour twin of an `assert*` method: it returns the
matched value (`String` / `Int`) instead of `Unit`, takes a `predicate` plus an
optional `schedule`, and provides a convenience overload that uses equality against
`expected` (delegating `_ == expected`). New retrying reads supply both forms
(`Browser.scala:1139-1147, 1167-1170`). An assertion method is named `assert*`,
returns `Unit`, delegates to a `BrowserAssertion.*` helper that retries the
predicate against the active retry schedule, and raises
`BrowserAssertionTimedOutException` on exhaustion; every `assert*` accepts an
optional `schedule: Maybe[Schedule] = Absent` per-call override
(`Browser.scala:970-973, 990-993`).

### Internal-only API

`private[kyo]` is the global convention (root `CONTRIBUTING.md`, "Visibility
Tiers"). The browser-specific case worth naming: raw CDP setters are hidden behind
scoped wrappers as the public-safety guarantee. `setViewport` / `resetViewport`
and `allowDownloads` / `denyDownloads` / `setDownloadBehavior` are `private[kyo]`,
exposed only to tests and to their public scoped wrappers (`withViewport`,
`withDownloads`) (`Browser.scala:1874, 2472-2474`).

## Extension recipes

### Add a new CDP command

Following the cookie/screenshot family (`CdpBackend.scala:155-158`,
`CdpTypes.scala:302-304`):

1. In `CdpTypes.scala`, add a `final private[kyo] case class ...Params(...) derives
   Schema` for the request and (if the reply carries data) a `...Result(...) derives
   Schema`, placed under the comment block for the owning CDP domain. Example pair:
   `GetBoxModelParams(backendNodeId: Int)` and `BoxModel(model: BoxModelContent)`
   (`CdpTypes.scala:302-304`).
2. In `CdpBackend.scala`, add a `private[kyo] def` under the matching `// ---
   <Domain> domain ---` banner. For a command that returns data, decode via
   `.map(decodeOrFail[Result](_, "Domain.method"))` (`CdpBackend.scala:155-158`);
   for a fire-and-forget command, end in `.unit` (`CdpBackend.scala:181-184`).
3. The wrapper's effect row is always `... < (Async & Abort[BrowserReadException])`
   and its first param is `sender: CdpSender` (the trait, never the concrete
   `CdpClient`), with `(using Frame)` (`CdpBackend.scala:155-157`).
4. The `method` string passed to both `send` and `decodeOrFail` is the literal CDP
   method name and must match exactly; it is what `decodeOrFail` reports in
   `BrowserProtocolErrorException` on a CDP error reply (`CdpBackend.scala:37`).

### Add a new public read/action method

Following `history` / `cookies` / `reload` (`Browser.scala:346-354, 2617-2622`):

1. Add the `CdpBackend` wrapper first (see the CDP-command recipe); the public
   method calls it.
2. Write the `Browser` method with return type `T < (Browser &
   Abort[BrowserReadException])` (a read returns a typed value / `Chunk`; an action
   returns `Unit`). Resolve the active tab with `Env.use[BrowserTab] { tab => ... }`
   and issue the call against `tab.session` (the session-scoped client), not
   `tab.client` (`Browser.scala:2617-2622`).
3. Map the CDP wire result into a public value type at the boundary; do not leak
   wire case classes outward (`CookieWire.toCookie` at `Browser.scala:2620`;
   `CdpBase64Decode.decodeScreenshotImage` for `screenshot` at `Browser.scala:1787`).
4. An action method returns `Unit` and ends at the `CdpBackend` call
   (`Browser.scala:2637-2650`).
5. A read that does NOT go through `CdpBackend` (page-side state) goes through
   `BrowserEval.evalJs(...)` and decodes the JSON itself, surfacing decode failures
   via `BrowserProtocolErrorException.decodeFailure` (`Browser.scala:2075-2088`).
6. A filtering/convenience overload delegates to the canonical method rather than
   re-issuing the CDP call (`Browser.scala:2091-2093`).

### Add a new scoped `with*` wrapper

For a wrapper that toggles per-tab CDP state for the duration of a body and restores
it on exit, following `withViewport` (`Browser.scala:1904-1925`):

1. If the wrapper needs to remember prior state, add an `AtomicRef` field to
   `BrowserTab` and initialise it in `mkBrowserTab` (the existing override cache is
   `viewportOverride: AtomicRef[Maybe[(Int, Int)]]` at `BrowserTab.scala:28`,
   initialised at `BrowserTab.scala:63`).
2. Signature shape: `def with...[A, S](args...)(body: A < (Browser & S))(using
   Frame): A < (Browser & Abort[BrowserReadException] & S)` (`Browser.scala:1904-1906`).
3. Body: `Env.use[BrowserTab] { tab => Scope.run { tab.<ref>.get.map { prior =>
   Scope.acquireRelease(<set new state>) { _ => <restore prior> }.andThen(body) } }
   }`. The release branch matches on `prior`: a previously-active value is re-applied
   and `Absent` clears the override, giving correct LIFO nesting
   (`Browser.scala:1907-1924`).
4. Use `Scope.acquireRelease` (or `Scope.ensure`) inside an inner `Scope.run`, NOT
   `Sync.ensure`: `Sync.ensure` does not fire on Abort short-circuits and leaks the
   state; the inner `Scope.run` bounds the unwind to this call so nesting keeps LIFO
   restore (`Browser.scala:2168-2171`).
5. Variant: when restoring a per-session registry entry (not a per-tab `AtomicRef`),
   snapshot the previous map with `getAndUpdate`, then in the restore re-insert
   `Present(prev)` or `remove` the key when it was `Absent` (`Browser.scala:2162-2167`).

### Add a new CDP event with per-session fan-out

For a CDP event Chrome pushes with no request id, routed to per-tab subscribers
without stalling the reader, following the `Page.downloadWillBegin` /
`downloadProgress` path (`CdpClient.scala:33-34, 375-378, 459-491`):

1. Add a per-session dispatcher registry field to `CdpClient`'s constructor and
   initialise it in `CdpClient.init`. The download registry is
   `downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit <
   Sync]]` (`CdpClient.scala:34`), initialised at `CdpClient.scala:293` and threaded
   into the `decodeCdpMessage` call (`CdpClient.scala:298-311`) and both `new
   CdpClient(...)` constructions (`init` at `CdpClient.scala:351-367`, `withSession`
   at `CdpClient.scala:118-136`).
2. Add the event's method name(s) to `eventWhitelist`; un-whitelisted events are
   dropped so the bounded event channel cannot fill when nobody is subscribed
   (`CdpClient.scala:375-378`). Only two CDP event methods
   (`Page.downloadWillBegin`, `Page.downloadProgress`) are currently forwarded to
   `exchange.events`; everything else is dropped at the dispatcher so
   Page/Network/Runtime lifecycle chatter cannot fill the bounded event channel and
   stall the reader (`CdpClient.scala:375-378`). The bounded event channel blocks the
   reader fiber when full, which stalls responses; internal wrappers opt their domain
   in on demand (`CdpClient.scala:369-373`).
3. In `decodeCdpMessage`'s no-id branch, add an arm that routes the event: when a
   per-session dispatcher is registered, call it and return
   `Exchange.Message.Skip`; when none is registered, return `Exchange.Message.Push(ev)`
   so `exchange.events` consumers still see it. The routing must be mutually
   exclusive (dispatch OR push, never both) (`CdpClient.scala:475-491`).
4. Frame-context-style events that should NEVER reach the events channel (consumed
   only inline) use a separate registry (`frameEventDispatchers`) and an
   UNCONDITIONAL dispatch arm placed before the whitelist check, always ending in
   `Exchange.Message.Skip` (`CdpClient.scala:445-458`).
5. Add a typed wire case class for the event's `params` in the relevant file (for
   example `DownloadWillBeginWire` / `DownloadProgressWire` in `PageDownload.scala`
   at `PageDownload.scala:70-92`) and a private `parse...Event` decoder in `Browser`
   that decodes `CdpEventParams[Wire]` from `ev.paramsJson`, returning
   `Maybe[PublicEvent]` (`Absent` on the wrong method or a decode failure, never an
   abort) (`Browser.scala:2588-2603`).
6. Add the public subscriber method (`onDownload`) that registers a
   `CdpEvent.Generic => Unit < Sync` handler into the registry BEFORE the body runs,
   drains events through a bounded unscoped `Channel` plus a forked drainer fiber (to
   isolate the user handler's effect row `S` from the dispatcher's `Sync`-only type),
   and unregisters via `Scope.ensure` inside an inner `Scope.run`. Requires `(using
   Frame, Isolate[S, Sync, S])` (`Browser.scala:2515-2557`).
7. The dispatcher handler must never block the CDP reader fiber: it offers to the
   channel best-effort and swallows `Abort[Closed]` (a full or closed channel drops
   the event rather than parking the reader) (`Browser.scala:2527-2533`).

### Add a new recorder

A `record*` / `.recorded` passive collector snapshots events into an in-memory
`Chunk` and returns `(events, result)`, following `recordDownloads` and
`withDialogs.recorded` (`Browser.scala:2569-2580, 2190-2211`):

1. Preferred form: implement the recorder in terms of the existing subscriber
   (`onDownload`) with a capture handler that appends each event to an internal
   `AtomicRef[Chunk]`, then return `(collected.get, result)`. No new registry or
   channel bookkeeping (`Browser.scala:2569-2580`).
2. Lower-level form (when the event is delivered by the CDP reader fiber rather than
   a subscriber method): register an `AtomicRef[Chunk[Event]]` into a per-session
   recorder registry on `CdpClient` (for example `dialogRecorders` at
   `CdpClient.scala:35`), restore the previous registry entry via `Scope.ensure`, and
   return `(recorder.get, result)`. The reader fiber appends in arrival order
   (`Browser.scala:2190-2211`).
3. For the lower-level form, add the append site in `CdpClient` where the reader
   decodes the event (for example `recordDialogEvent` decodes `CdpEventParams[Wire]`
   and appends a typed `Browser.<Event>` to the per-session recorder, no-op when none
   is registered). The recorder is a passive observer and must NOT influence any
   auto-handler decision (`CdpClient.scala:510-540, 434-435`).
4. A recorder's signature requires `(using Frame, Isolate[S, Sync, S])` and returns
   `(Chunk[Event], A) < (Browser & Async & Abort[BrowserReadException] & S)`; arrival
   order is preserved because the single per-session fiber/reader serialises appends
   (`Browser.scala:2190-2193, 2566-2567`).

## Testing

### Base classes

A suite that needs a working `Browser` effect extends `BrowserTest`, the
integration-test base class (`shared/src/test/scala/kyo/BrowserTest.scala:74`).
`BrowserTest` itself extends the module `Test` base, so every browser suite
transitively gets the `Test` machinery (chrome-platform pre-flight, decode helpers,
`orFail`, `timed`) (`shared/src/test/scala/kyo/BrowserTest.scala:74`). A suite that
does NOT boot Chrome (pure parsing, wire/decoder, schema, percent-encode, snapshot,
downloader) extends `Test` / `kyo.Test` directly, never `BrowserTest`: examples are
`StabilitySamplerTest`, `CdpClientDecoderTest`, `BrowserExceptionHierarchyTest`
(`shared/src/test/scala/kyo/internal/StabilitySamplerTest.scala:12`,
`shared/src/test/scala/kyo/BrowserExceptionHierarchyTest.scala:3`). Runnable demos
live in `shared/src/test/scala/demo` and extend `KyoApp` (not `Test`); they are
example programs, not assertions, so they never go through the `Test` / `run`
harness (`shared/src/test/scala/demo/QuickstartDemo.scala:13`).

### Fixtures

| Fixture | What it boots | When to use |
|---------|---------------|-------------|
| `withBrowser(body)` | shared Chrome, fresh tab via `Browser.run(url)` | universal SUT entry (`BrowserTest.scala:112-117`) |
| `withBrowserOnLocalhost(body)` | tab navigated to `http://localhost:$port/json/version` | cookie/storage tests that need a real origin (`data:` URLs carry no cookies) (`BrowserTest.scala:119-129`) |
| `withBrowserOnLocalhostIframe(outerHtml, innerHtml)(body)` | localhost server serving `/parent` and `/child`, tab on parent | real same-origin iframe lifecycle (srcdoc does not fire the same `Page.frameAttached` events) (`BrowserTest.scala:247-249`) |
| `withLocalhostServer(handlers*)(f)` | localhost HTTP server on an OS-assigned port at `127.0.0.1` (NOT Chrome) | ad-hoc page fixtures, released when the surrounding `Scope` exits (`BrowserTest.scala:136-141`) |
| `page(html)` / `srcdocPage(outer, srcdoc)` / `onPage(html)(body)` | `data:text/html;...` URLs, no I/O | one-shot scenarios that do not need a real origin (`BrowserTest.scala:226-232`) |

`onPage` is sugar for `Browser.goto(page(html)).andThen(body)` with a by-name `body`
so timing measurements inside it run after navigation (`BrowserTest.scala:226-232`).
`withBrowser` returns an effect carrying `Async & Scope & Abort[BrowserReadException
| BrowserSetupException]` (`BrowserTest.scala:112-117`).

### Shared Chrome and the cold-boot reality

The SUT's Chrome is shared across all callers in a run via `SharedChrome.init`,
which returns the WebSocket debug URL and launches Chrome only on first call; only
the URL is shared, each caller makes its own `CdpClient` / `Browser.run(url)`
(`shared/src/main/scala/kyo/internal/SharedChrome.scala:37-38`). Chrome is held open
by a detached fiber that parks on `Async.never`; when the kyo scheduler shuts down
the fiber is interrupted and finalizers destroy Chrome and its temp user-data dir. A
test never tears Chrome down itself (`SharedChrome.scala:104`).

The first integration call pays a ~2.8s cold-Chrome boot. `BrowserTest` absorbs it
with a one-time `warmupGate` (CAS-once `AtomicBoolean`, fires `Browser.eval("1+1")`
exactly once per class instance) so later per-call schedule budgets in
`BrowserPerCallScheduleTest` are not blown by the boot cost. Do not write per-call
timing assertions that include the first call's boot (`BrowserTest.scala:76-91`).

### Timeouts and forking

Default per-test timeout is 60s (from `BaseKyoKernelTest.timeout`), with
`Duration.Infinity` under debug. A Chrome-heavy suite raises it explicitly:
`BrowserRunSharedJvmTest` sets `override def timeout = 3.minutes`,
`CdpClientLifecycleJvmTest` sets `2.minutes`. Override `timeout` when a suite does
multiple sequential Chrome round-trips under full-suite load
(`kyo-kernel/shared/src/main/scala/kyo/internal/BaseKyoKernelTest.scala:61`,
`jvm/src/test/scala/kyo/BrowserRunSharedJvmTest.scala:19`).

kyo-browser JVM tests use per-suite JVM forking: each suite gets its own JVM and its
own `SharedChrome`, via a `Test / testGrouping` that wraps every defined test in its
own `Tests.SubProcess`. The reason is cross-suite Chrome-state degradation over 700+
tests (`build.sbt:1145-1155`). `Test / parallelExecution := false` and `Test /
testForkedParallel := false` on JVM serialize the per-suite forks because running
them concurrently starves the Chrome processes and a dead Chrome cascades; new
browser suites must not assume any cross-suite parallelism (`build.sbt:1149-1154`).
Native tests set only `Test / parallelExecution := false` (no per-suite forking
grouping): suites are serialized so each owns the shared Chrome WebSocket channel in
turn. JS sets the `CommonJSModule` linker kind and no Chrome-serialization knob
(`build.sbt:1177-1186`).

### Platform gates

The pre-flight platform gate lives in `Test.run`: it computes
`chromeUnsupportedReason` from `ChromeDownloader.resolvePlatform` and, on an
unsupported (OS, arch) tuple (linux-arm64, win-arm64), calls ScalaTest
`cancel(reason)` instead of letting every Chrome test fail red. A test author does
nothing extra (`shared/src/test/scala/kyo/Test.scala:69-72`).
`BrowserTest.cancelOnUnsupportedPlatform` is the second cancel seam: it recovers a
`BrowserSetupException` whose message contains the marker `"cannot auto-download
chrome-headless-shell"` and routes it to `cancel(...)`; the `withBrowser*` fixtures
wrap their bodies in it so unsupported platforms cancel with install instructions
rather than fail (`BrowserTest.scala:103-110`).

### Platform-split test suites

A platform-specific test suite lives in `jvm/src/test`, `js/src/test`, or
`native/src/test` ONLY when the behavior is genuinely platform-bound, and the
scaladoc states the reason; a JVM-only suite documents why it cannot move to
`shared/` (`jvm/src/test/scala/kyo/internal/BrowserLauncherJvmTest.scala:9-12`).
JVM-only suites that need a real Chrome subprocess or kyo-http `HttpServer` carry the
`JvmTest` name suffix and live under `jvm/src/test`: `BrowserRunSharedJvmTest`
(Chrome boot is JVM/Native-only), `CdpClientLifecycleJvmTest` (fault-injecting CDP
fixture built on `HttpServer`) (`jvm/src/test/scala/kyo/BrowserRunSharedJvmTest.scala:8-12`,
`jvm/src/test/scala/kyo/internal/CdpClientLifecycleJvmTest.scala:144-148`). The same
logical contract is split across all three platform test trees when its
implementation differs per platform: `BrowserLauncherPlatformTest` exists in `jvm/`,
`js/`, and `native/`, each pinning that platform's distinct `BrowserLauncherPlatform`
(JVM installs a real shutdown hook; JS and Native are no-ops)
(`native/src/test/scala/kyo/internal/BrowserLauncherPlatformTest.scala:7-10`).

### Decode helpers

CDP/wire tests use the `Test` decode helpers rather than re-deriving JSON:
`decode[A]` for a bare payload, `decodeCdpResult[A]` for a full `{id, result,
error}` envelope (the dispatcher carrier is the entire wire frame)
(`shared/src/test/scala/kyo/Test.scala:84-93`). Outer effect results are folded with
the `orFail(label)` extension, which fails the test on `Failure` / `Panic` and
prefixes panics with `PANIC: ` so a programming bug is visually distinct from an
expected typed-failure path. JVM fixture suites wrap the whole body in
`Abort.run[...](...).orFail("Outer")` (`shared/src/test/scala/kyo/Test.scala:107-114`,
`jvm/src/test/scala/kyo/internal/CdpClientLifecycleJvmTest.scala:170`).

### Deterministic timing: gate on barriers, never sleeps

This is the testing counterpart of the headline invariant. Every wait in a test is
gated on an observable signal, not a `Thread.sleep` or `Async.sleep`:

- A `Promise` is the explicit completion latch: complete it only on the path that
  proves the awaited operation finished, then `Promise.get` to synchronise, and
  assert on the resulting state. `NavigationWatcher`'s `waitForNext` test completes
  `donePromise` only because `waitForNext` returned, gets it, then asserts the URL
  (`shared/src/test/scala/kyo/internal/NavigationWatcherTest.scala:210-224`).
- To wait for a JS-side condition, install the state and poll for a truthy JS string
  with `Browser.waitFor("<expr returning '' or non-empty>")` instead of sleeping; the
  network-tracker test waits for `__kyoResponseTrackingInstalled` to be defined
  (`shared/src/test/scala/kyo/BrowserNetworkTest.scala:99`).
- Settlement/navigation outcomes are gated on a real barrier: the slow-image
  `Settle.Load` test asserts `document.readyState === 'complete'` AT RETURN (a
  behavioural contract) rather than on elapsed time, and engineers a server-side
  500ms image delay so `interactive` precedes `complete` by an observable margin
  (`NavigationWatcherTest.scala:251-256, 441-444`).
- Negative/timeout behaviour is asserted on the typed `Abort` SHAPE, not on elapsed
  time: the mutation-quiescence timeout test drives an endless `setInterval` churn
  and asserts the result is `Result.Failure(_: BrowserAssertionTimedOutException)`
  (`shared/src/test/scala/kyo/internal/MutationSettlementTest.scala:185-189`).
- Network completion is observed through a real network-idle barrier plus a DOM
  side-effect: `waitForNetworkIdle` returns only after a scheduled `fetch` resolves,
  and the test asserts `#status` flipped to `"done"`
  (`shared/src/test/scala/kyo/BrowserNetworkTest.scala:38-45`).
- When a test must wait for an internal fiber/atomic to reach a state, it uses a
  bounded `Loop` + `Async.delay` poll on an observable signal (`relay.done`,
  `inFlight.get`, fiber-done) with an attempt cap, NOT a fixed sleep. The relay-crash
  test polls `client.relay.done` at 50ms intervals up to 20 attempts (<=1s)
  (`jvm/src/test/scala/kyo/internal/CdpClientLifecycleJvmTest.scala:189-194`).
- For elapsed-duration checks that ARE legitimate (a behavioural upper bound, e.g.
  "close falls back to closeNow well before the server's 10s delay"), tests use the
  `Test.timed` helper (monotonic clock) and assert a generous margin, not an exact
  value (`shared/src/test/scala/kyo/Test.scala:96-99`,
  `jvm/src/test/scala/kyo/internal/CdpClientLifecycleJvmTest.scala:235-238`).

### Retry schedules in tests

Flaky-but-correct assertions are bounded by a retry SCHEDULE installed via
`Browser.withConfig(_.retrySchedule(...))`, exposed as the `tight` (50ms x 3, ~150ms
cap) and `slow` (200ms x 15, ~3s cap) helpers: use `tight` for assertions that
should settle in an animation frame or for fast negative tests, `slow` for scenarios
gated on Chrome/page warmup. The cap is a ceiling, not a sleep: the assertion fires
as soon as it holds (`BrowserTest.scala:186-192`). Reaching for a longer retry
schedule usually masks a missing settle barrier rather than fixing a real flake; the
fix is asserting on the right gate, not widening the budget (`BrowserTest.scala:177-179`).

## Adding a new method: decision checklist

Run through this before writing a new `Browser` method.

1. **Pick the operation kind.** Read, action (interaction), navigation, assertion,
   or scoped wrapper. The kind determines the settlement mechanism (below) and the
   return type.
2. **Pick the return type from the discriminator table.** `Maybe[...]` when absence
   is legitimate; bare value with `BrowserElementNotFoundException` when the element
   must exist; `Boolean` returning `false` for `exists`-style questions, aborting for
   `is*` property predicates; `Int` returning `0` for counts; `Chunk[...]` with an
   empty-fast-path for `*All` reads (`Browser.scala:1517-1525, 1478-1489, 1431-1436,
   1667-1672`). Use `Maybe` / `Result` / `Chunk` / `Span`, never `null` or a string
   sentinel (`Resolver.scala:7-11`).
3. **Declare the effect row.** `A < (Browser & Abort[BrowserReadException])` with
   `Frame` as the trailing `using` parameter (`Browser.scala:1372, 1382`).
4. **Wire in the right settlement mechanism:**
   - Interaction: `Actionability.withRetry { Actionability.withActionable(selector,
     requireFillable = ..., requireEnabled = ...) { ref =>
     MutationSettlement.afterAction(<action>)(scopeSelector = Present(selector)) } }`
     (`Browser.scala:457-462, 532-541`). Decide `requireEnabled` per the
     hover/press rule (`Actionability.scala:46-48`). Skip mutation settlement and arm
     the navigation watcher on a nav-intent path (`Browser.scala:447-452`).
   - Navigation: arm `NavigationWatcher`, replicate the same-URL no-op and `data:`
     URL auto-downgrade traps (`Browser.scala:329-334, 325-328`).
   - Assertion or retrying read: route through `BrowserAssertion` /
     `StabilitySampler`; name it `assert*` (returns `Unit`) or `waitFor*` (returns
     the matched value), provide the optional `schedule: Maybe[Schedule] = Absent`
     override, and for `waitFor*` supply both the predicate form and the equality
     convenience overload (`Browser.scala:970-973, 1139-1147, 1167-1170`).
   - Pure read: page-side state through `BrowserEval.evalJs`, CDP-backed read through
     a `CdpBackend` wrapper against `tab.session` (`Browser.scala:2075-2088,
     2617-2622`).
5. **If a new wait must catch a fast in-page transient, keep the entire loop inside
   one `awaitPromise=true` eval.** Never split it into separate `Runtime.evaluate`
   polls (`MutationSettlement.scala:126-131`, `StabilitySampler.scala:8-19`).
6. **Do not widen the actionability retry channel.** It is narrowed to
   `BrowserElementException` on purpose; widening it back to
   `BrowserMutationException` blows the mutation timeout budget
   (`Actionability.scala:479-495`).
7. **Pick the exception.** One row position (`BrowserReadException` <-
   `BrowserMutationException` <- `BrowserAssertionException`) plus one or more topical
   markers; `final case class ... derives CanEqual` with the message rendered in the
   `extends` clause; smart constructors for multi-path construction
   (`BrowserException.scala:11-17, 174-177, 146-162`). Validate args before any CDP
   call with `BrowserInvalidArgumentException("<methodName>", ...)`
   (`Browser.scala:758-759`).
8. **Map wire to public types at the boundary.** Do not leak wire case classes;
   surface decode failures as `BrowserProtocolErrorException.decodeFailure`
   (`Browser.scala:2620, 2075-2088`).
9. **Hide raw CDP setters behind a scoped `with*` wrapper** marked `private[kyo]` if
   the operation mutates sticky per-tab state (`Browser.scala:1874, 2472-2474`).
   Follow the cache-and-restore recipe so nested wrappers compose with LIFO restore
   on success, failure, AND interruption (`Browser.scala:1907-1924, 2168-2171`).
10. **Add cross-platform tests under `shared/src/test`.** Extend `BrowserTest` for a
    Chrome-backed suite, `Test` for a pure suite. Gate every wait on a barrier, not a
    sleep; split into a platform tree only when the behavior is genuinely
    platform-bound and document why (`BrowserTest.scala:74`,
    `jvm/src/test/scala/kyo/internal/BrowserLauncherJvmTest.scala:9-12`).
