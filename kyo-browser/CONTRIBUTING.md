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
(`MutationSettlement.scala:177-182`, `StabilitySampler.scala:32-67`). If you break
that constraint, the module silently stops catching the flickers it exists to
catch. The settlement section below is the deepest in this guide for that reason.

The module now also carries a visual-QA surface (captures, geometry/style reads,
element discovery, frame recording, plus viewport/scroll/emulation/highlight/console
controls). It is built on the same settlement spine: the new settling reads
re-sample through the in-page stability loop and map a settled-absent element to the
twin return (`SettleRead.scala:5-15`); captures hold the page still (await fonts,
freeze animations, loop to two byte-identical frames) and are settlement-transparent
because every injected overlay carries `data-kyo-internal` (`HoldStill.scala:5-39`,
`MutationSettlement.scala:124-148`). The settlement section documents both halves.

## Table of contents

1. [Architecture at a glance](#architecture-at-a-glance)
2. [Transparent settlement (the headline invariant)](#transparent-settlement-the-headline-invariant)
3. [The visual-QA surface](#the-visual-qa-surface)
4. [Conventions](#conventions)
5. [Extension recipes](#extension-recipes)
6. [Testing](#testing)
7. [Adding a new method: decision checklist](#adding-a-new-method-decision-checklist)

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
`internal/CdpClient.scala:249`).

Internally the stack layers top-down, each layer composing the one below it:

| Layer | Type | Responsibility |
|-------|------|----------------|
| Public API | `Browser` (`Browser.scala:149`) | Actions, reads, assertions, scoped wrappers, visual-QA surface |
| Settlement / readiness | `Actionability`, `MutationSettlement`, `NavigationWatcher`, `BrowserAssertion` / `StabilitySampler`, `SettleRead`, `HoldStill` | The auto-wait gates plus the settle-this-read and hold-still-capture helpers |
| Resolution / eval | `Resolver`, `BrowserEval`, `DiscoverJs` | `Selector` to typed `NodeRef`; page-side JS eval; the injected element-introspection probe |
| Typed CDP commands | `CdpBackend` (`internal/CdpBackend.scala:6-13`) | One `private[kyo]` method per CDP endpoint |
| Transport / multiplexing | `CdpClient` (`internal/CdpClient.scala:6-23`) | WebSocket exchange, dialog routing, frame fan-out |
| Process | `BrowserLauncher`, `SharedChrome`, `ChromeDownloader` | Spawn / share / provision Chrome |

`CdpBackend` wraps each CDP endpoint as a raw `sender.send(...)` plus a typed JSON
decode, so call sites in `Browser` never write string-literal CDP calls
(`internal/CdpBackend.scala:6-13`). `CdpClient` is the transport beneath it: it
pairs each outbound command with its response over a single WebSocket via
`Exchange`, routes dialogs, fans out frame events, and bounds in-flight commands
(`internal/CdpClient.scala:6-23`). A single relay fiber inside `CdpClient.init`
owns the WebSocket: an outbound stream pumps the `outbound` channel into `ws.put`,
a receiver pumps `ws.stream` into the `inbound` channel, and `Async.race(sender,
receiver)` runs both (`internal/CdpClient.scala:249-262`).

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
registration guards, the viewport and emulation overrides, download policy). Its
`session` field is the `client.withSession(sessionId)` pair every CDP call issues
against (`internal/BrowserTab.scala:19-37`). `BrowserTabSetup.attachAndSetupTab` is
the single entry point that creates a fresh isolated browser context, attaches a CDP
target, and installs the per-tab trackers; it is the one place the four-layer
containment (process / context / tab / iframe) is assembled from the bottom
(`internal/BrowserTab.scala:71`, `internal/BrowserTab.scala:253`). Every action path
depends on four CDP domains (Page, Runtime, DOM, Network) being enabled;
`enableDomains` fires them with `Async.zip` to amortise the round-trip, then
enables focus emulation (`internal/BrowserTab.scala:284`).

Element resolution is centralised in `Resolver`: it maps a `Selector` to a typed
`NodeRef` via the `objectId + requestNode` pipeline (`Runtime.evaluate` ->
`DOM.requestNode` -> `DOM.describeNode`), the shared substrate every interaction
method resolves through (`internal/Resolver.scala:5-32`, `internal/Resolver.scala:35`).

**Representative call flow.** `Browser.run(launch)` is the lifecycle end to end:
launch Chrome -> connect a `CdpClient` -> attach and setup a tab -> run the body
bound to that tab, all under one internal `Scope.run` so the caller's effect row
does not carry `Scope` (`Browser.scala:252-266`). `click` is the representative
action flow: dependency direction is top-down through the layers, a public
`Browser.*` action composes the actionability and settlement gates -> `Resolver` /
`BrowserEval` -> `CdpBackend` typed wrappers -> `CdpClient.send` transport ->
Chrome (`Browser.scala:442-454`). Internal callers reach the hidden
`Env[BrowserTab]` only through `Browser.use` (read the tab) and `Browser.runOn`
(bind a tab), which wrap `Env.use` / `Env.run` so internal code never sees the
opaque type's underlying `Env` (`Browser.scala:4356-4363`).

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
exception, not the rule (`internal/BrowserLauncher.scala:99`). The only
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
`select` / `press` / `uncheck`, plus `setViewport` / `resetViewport` / `scrollTo` /
`scrollToElement` / `withViewport` / `withEmulation` apply).** After a
state-changing action fires, `MutationSettlement.afterAction` installs a
window-level `MutationObserver`, records the timestamp of the last mutation, and
polls until `now - lastMutation >= quiescenceWindow`, so the caller sees the settled
DOM, not the mid-render snapshot React/Vue are in (`MutationSettlement.scala:6-12`).
The observer is ALWAYS rooted at `document.body` regardless of `scopeSelector`; the
`scopeSelector` arg is retained for future opt-in scoping but is currently unused,
because onclick handlers commonly mutate sibling DOM a target-scoped observer would
never see (`MutationSettlement.scala:23-33`). Quiescence has two regimes keyed off a
`startCount` snapshot taken at action-complete: if no mutation has landed it keeps
polling only until `mutationFirstMutationGrace` elapses (the fast path for no-op
clicks), otherwise it waits for `quiescenceWindow` of quiet bounded by
`mutationSettlementTimeout`, raising a timeout if the DOM never stops churning
(`MutationSettlement.scala:49-63`). A single window-level observer is shared across
concurrent `afterAction` calls via a reference count (`window.__kyoMutObsRef`):
install bumps, release decrements, and the observer disconnects when the count
hits 0 (`MutationSettlement.scala:16-19`).

The observer callback filters out `data-kyo-internal` mutations so injected
overlays and the freeze stylesheet are settlement-transparent: see
[The data-kyo-internal filter](#the-data-kyo-internal-filter-overlays-are-settlement-transparent)
below. `afterAction` is not the only entry point. `MutationSettlement.waitForStable`
is the strict no-action wait backing `Browser.waitForStable`: it installs the
observer with no action, runs the same single-eval quiescence loop with
`overallDeadline = timeout`, and ABORTS `BrowserAssertionTimedOutException` on
timeout rather than swallowing it (`MutationSettlement.scala:72-89`).
`MutationSettlement.settleForCapture` is the best-effort sibling: identical loop, but
it recovers the timeout to `()` instead of aborting, so the hold-still capture path
can pre-settle the DOM and proceed even when the page never fully quiesces
(`MutationSettlement.scala:91-100`). Strict `waitForStable` vs best-effort
`settleForCapture` is the load-bearing distinction: a read or assertion must surface
a never-quiesced page as a typed failure; a capture must still produce an image.

**3. Navigation settlement (navigation: `goto` / `back` / `forward` / `reload` /
`expectNavigation`, and nav-intent clicks).** These arm `NavigationWatcher`, which
installs an in-page recorder monkey-patching `history.pushState` / `replaceState`
plus a `beforeunload` listener, then polls readyState (and optionally the
network-idle window) until the requested `Settle` mode is satisfied. Detection is
JS-polling, keeping the CDP event channel free (`NavigationWatcher.scala:5-19`).
The three settle modes form a strictly-increasing strength order:
`DomContentLoaded` (readyState >= interactive) < `Load` (readyState complete) <
`NetworkIdle` (load fired AND no in-flight fetch/XHR for `networkIdleWindow`);
`NetworkIdle` is the default for navigation methods (`Browser.scala:3922-3934, 321`).
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
live Browser (`NavigationWatcher.scala:333-362, 396`).

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
  interruption (`MutationSettlement.scala:177-182`).
- The stability sampler runs the ENTIRE sampling loop inside one `awaitPromise`-backed
  eval. Sampling via N separate CDP round-trips aliases because round-trip latency
  is load-dependent (hundreds of ms under Native full-suite load), so coarse samples
  cannot see a flicker faster than the sample spacing; the in-page loop is
  alias-proof (`StabilitySampler.scala:1-19`).

`SettleRead.settle` (the settle-on-reads helper) and `Browser.waitForStable` both
ride this same in-page loop: `SettleRead` calls `StabilitySampler.sampleWindow`
directly, and `waitForStable` runs the `awaitQuiescence` single-eval loop. Neither
adds a new transient-catching loop, so both inherit the constraint rather than
restating it (`SettleRead.scala:13-14`, `MutationSettlement.scala:177-182`). The
HoldStill capture loop is the deliberate exception: its two-identical-frames
comparison is NOT an in-page transient loop (each iteration is a full CDP capture
round-trip paced by `Clock.sleep`), so it is exempt from the single-eval rule
(`HoldStill.scala:16-21`).

There is also a hard single-round-trip invariant on the actionability check:
`Actionability.check` must complete in exactly one CDP round trip; every JS bundle
embeds a marker so the round-trip count is observable, and there must be exactly one
evaluate carrying that marker per `check` call (`Actionability.scala:84-90`).

If you add or change a wait that must catch a fast in-page transient, the loop must
live entirely inside one `awaitPromise=true` eval. Do not "simplify" it into a
sequence of separate `Runtime.evaluate` polls.

### Settle-on-reads: the SettleRead helper

The visual-QA reads (`boundingRect`, `computedStyle(s)`, `inViewport`,
`scrollPosition`, `element`, `elementAt`, `elements`) are SETTLING reads: each
re-samples its in-page value expression on the active `retrySchedule` until the value
holds constant across `assertionStabilityWindow`, then decodes the stable string
(`SettleRead.scala:5-15`). This is a DEDICATED helper, not a `BrowserAssertion.withStability`
call. `withStability` raises `failure(first)` whenever the first probe fails the
predicate, which is assert/waitFor semantics ("the predicate must hold"); a settling
read instead needs a settled-ABSENT element to produce the read's twin return
(`Absent` / `false` / abort / empty), not a stability timeout. So `SettleRead.settle`
reuses `StabilitySampler.sampleWindow` (the identical in-page loop) but always feeds
the stable string to the read's own `decode`, and absence is just a stable sentinel
string that `decode` maps (`SettleRead.scala:27-40`). "Unstable" (the value changed
mid-window) wraps in a typed `BrowserAssertionTimedOutException` so the outer
`Retry[BrowserReadException]` re-samples; only genuine instability for the whole
budget surfaces as a failure (`SettleRead.scala:18-22, 35-38`). A `decode` that itself
aborts (a must-exist read finding an absent element) is treated as an unstable sample
and retried for the full budget before the failure surfaces (`SettleRead.scala:10-11`).
`assertionStabilityWindow = Duration.Zero` is the first-match escape hatch:
`SettleRead` evaluates once and decodes, matching `withStability`'s zero-window path
(`SettleRead.scala:33`).

### Hold-still capture

Captures (`screenshot`, `screenshotRegion`, `screenshotElement`, `screenshotFullPage`,
`screenshotMarks`) do not settle the way reads do; they HOLD THE PAGE STILL. The
protocol, in order, is: best-effort `settleForCapture` pre-settle, await
`document.fonts.ready`, inject a `data-kyo-internal` freeze `<style>` (animations and
transitions paused, caret hidden), then loop the base capture until two consecutive
frames hash-identical or `captureHoldStillTimeout` elapses, returning the last frame
(`HoldStill.scala:5-39`). It is best-effort: it NEVER aborts on timeout. Equality is
`Image.hashCode` over the DECODED image bytes (a content hash via `MurmurHash3.bytesHash`),
NOT an image-diff algorithm and NOT the base64 wire string; using `img.binary.hashCode`
would compare `Array` reference identity and never converge (`HoldStill.scala:42-51`).

The helper is split into two halves so a multi-band capture freezes ONCE rather than
per band. `withFrozenPage` is the setup half (settle, await fonts, inject the freeze
style under `Scope.acquireRelease`); `holdStillFrame` is the per-capture
two-identical-frames loop assuming the page is already frozen; `withHoldStill` is
`withFrozenPage { holdStillFrame(capture) }` for the single-call case
(`HoldStill.scala:93-153`). `screenshotFullPage` calls `withFrozenPage` around the
whole band loop and `holdStillFrame` per band, so all bands reflect the same frozen
animation state (`Browser.scala:1978-2002`). `screenshotMarks` does the same to inject
its badge overlay once inside the already-frozen scope (`Browser.scala:2121-2136`).

### The data-kyo-internal filter (overlays are settlement-transparent)

The freeze stylesheet, `withHighlights` boxes, and `screenshotMarks` badges all inject
DOM under a live `MutationObserver`. They must NOT arm the mutation gate, or a capture
or a `withEmulation` apply inside the same scope would wait on their own overlay. The
observer callback filters them out via the `data-kyo-internal` attribute
(`MutationSettlement.scala:124-148`). The filter has TWO branches, both load-bearing:

- A record whose target sits inside a `[data-kyo-internal]` subtree is dropped (covers
  attribute / characterData / childList mutations made WITHIN a tagged overlay)
  (`MutationSettlement.scala:129-132`).
- A `childList` record that INSERTS or REMOVES a tagged root is also dropped: the
  target is the untagged parent (`document.body` / `document.head`), and the tagged
  node is in `addedNodes` / `removedNodes`. The record is transparent only when EVERY
  added AND removed node is (or is within) a tagged subtree, so a mixed batch that also
  moves a real node still arms the gate (`MutationSettlement.scala:133-142`).

Both branches matter: the first makes mutations inside an overlay transparent, the
second makes overlay INJECTION and REMOVAL transparent. Without the second branch the
`appendChild` / `remove` of the freeze style or the highlight container would itself be
a settlement-arming `childList` mutation.

`data-kyo-internal` is a RESERVED SENTINEL attribute. A page under test must not stamp
its own nodes with it: any node carrying it (or sitting beneath one) becomes invisible
to mutation settlement, so a self-stamped subtree would silently stop arming the gate.

### Overlay teardown: per-handle token, never a shared global slot

Every scoped in-page overlay (the freeze style, `withHighlights` boxes, `screenshotMarks`
badges) mints a UNIQUE `data-kyo-token` in its inject JS, from an in-page monotonic
counter `window.__kyoOverlayToken`, and tears down by removing ONLY the node carrying
that token (`HoldStill.scala:53-78`, `Browser.scala:2101-2118, 2352-2382`). The inject
eval returns the token to Scala; the `Scope.acquireRelease` release closure closes over
it, so removal fires on success, failure, AND interruption but targets exactly the node
this invocation injected.

This replaced an earlier shared-single-slot design (each overlay wrote its node into one
`window.__kyo*` slot and read it back to remove). Under nesting or interleaving the inner
inject overwrote the slot, the inner exit deleted it, and the outer exit then found the
slot `undefined` and leaked its node permanently. A leaked freeze `<style>` is the worst
case: it pauses the whole page indefinitely. So the recipe for ANY new injected overlay
is: mint a per-handle token in the inject JS, tag the node with both `data-kyo-internal`
(for settlement transparency) and the unique `data-kyo-token`, return the token, and
remove by token in the release. Never a shared global slot. `__kyoOverlayToken` is the
only shared global here and it is append-only (never read back to identify a node), so it
carries none of the single-slot hazard.

### Config knobs

All per-operation tuning lives in a single `SessionConfig` carried in a fiber
`Local` (`configLocal`); every retry, settle, and assertion loop reads it via
`configLocal.use`, and `withConfig` installs a scoped override (`Browser.scala:4348,
213-214`). Config splits into launch-time (`LaunchConfig`, frozen once Chrome
starts) and session-time (`SessionConfig`, mutable per scope); `withConfig` accepts
ONLY `SessionConfig`, so a contributor cannot write `withConfig(_.executable(...))`
and have it silently no-op. The compiler enforces the split (`Browser.scala:203,
4075, 4185`).

| Knob | Default | Role |
|------|---------|------|
| `retrySchedule` | `100ms` x up to `8s` | Outer poll loop for interactions, assertions, retrying reads, settling reads (`Browser.scala:4126`) |
| `loadSchedule` | `100ms` x up to `5s` | Navigation load polling (`Browser.scala:4127`) |
| `networkIdleWindow` | `500ms` | Quiet window required for `NetworkIdle` (`Browser.scala:4128`) |
| `mutationQuiescenceWindow` | `50ms` | DOM-quiet window before mutation settlement returns (`Browser.scala:4129`) |
| `mutationSettlementTimeout` | `2s` | Upper bound on mutation quiescence (`Browser.scala:4130`) |
| `mutationFirstMutationGrace` | `100ms` | Fast-path grace when no mutation lands (`Browser.scala:4131`) |
| `assertionStabilityWindow` | `100ms` | Window the assertion value must hold constant, also the settling-read stability window (`Browser.scala:4132`) |
| `stabilitySampleInterval` | `4ms` | In-page sample spacing (`Browser.scala:4137`) |
| `navigationGraceWindow` | `300ms` | Fast-path window for nav-neutral clicks (`Browser.scala:4136`) |
| `navigationPostSettleWindow` | `300ms` | Post-settle window (`Browser.scala:4134`) |
| `captureHoldStillTimeout` | `1s` | Total best-effort hold-still bound per capture (`Browser.scala:4140`) |
| `captureHoldStillInterval` | `50ms` | Inter-capture pacing in the two-identical-frames loop (`Browser.scala:4141`) |

The two `captureHoldStill*` knobs are the only ones the visual-QA surface added; the
hold-still loop reads both from `configLocal` (`HoldStill.scala:117-119`). The `8s`
`retrySchedule` budget is deliberately generous to accommodate SPA hydration
(React/Vue/Angular shells painted before content); callsites that want fail-fast
should install a tighter schedule via `withConfig(_.retrySchedule(...))`
(`Browser.scala:4204`). `withTimeout(d)` is the shortcut that caps both
`retrySchedule` and `loadSchedule` at `maxDuration(d)`; it bounds the retry budget,
it does NOT abort the way `Async.timeout` does, so a failure to settle within `d`
aborts with the usual typed assertion timeout (`Browser.scala:226-230`).

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
  } }` (`Browser.scala:445-453, 504-514`).
- `requireEnabled = false` is passed by `hover` and `press` so the disabled probe is
  skipped: real browsers still fire `mouseover` / `keydown` against disabled controls,
  so blocking them would be wrong. Visibility, attached, stability, and hittable still
  run (`Browser.scala:469-477, 668-676`, `Actionability.scala:46-48`). Decide per
  method whether the disabled probe applies.
- On a nav-intent click, `Browser.click` skips mutation settlement entirely and arms
  the navigation watcher instead, because the destination is a fresh DOM and the old
  page's observer is wiped by the navigation (`Browser.scala:447-452`). A new
  nav-capable action must make the same choice.
- A new navigation entry-point must replicate two traps or it will hang.
  `goto` short-circuits to a no-op when the tab is already at the target URL, because
  Chrome's `Page.navigate` against the current URL does NOT actually navigate, so the
  watcher would wait for a URL-change signal that never arrives and time out;
  refresh callers must use `reload` (`Browser.scala:329-336`). `goto` to a `data:`
  URL auto-downgrades the default `NetworkIdle` to `Load`, because `data:` URLs
  produce no network traffic so the idle window never opens; an explicit non-default
  settle is honored as-is (`Browser.scala:324-328`).

## The visual-QA surface

The visual-QA surface turns `Browser` into a screenshot / inspection tool. Every
member nests under the existing `Browser` opaque type or the `BrowserException`
hierarchy; the visual-QA surface adds no new top-level types. The surface groups into four
jobs plus controls.

### SEE: captures

| Method | Returns | Settlement | Citation |
|--------|---------|------------|----------|
| `screenshot(format, quality)` | `Image` | hold-still; live viewport, no clip | `Browser.scala:1916-1927` |
| `screenshotRegion(x, y, width, height, format, quality)` | `Image` | hold-still; `captureBeyondViewport = true`; non-positive size aborts pre-CDP | `Browser.scala:1933-1956` |
| `screenshotElement(selector, format, quality, transparentBackground)` | `Image` | `Actionability.withRetry` auto-wait, then hold-still | `Browser.scala:2029-2082` |
| `screenshotFullPage(maxBands, format, quality)` | `Chunk[Image]` | freeze ONCE, hold-still per band; `bandCount > maxBands` aborts pre-capture | `Browser.scala:1963-2005` |

`screenshot` dropped the legacy `1280x720` crop: it now captures whatever viewport
`setViewport` / `withViewport` last established (`Browser.scala:1916-1927`).
`screenshotElement` auto-waits inside `Actionability.withRetry` (channel
`BrowserElementException`, never widened): it resolves, box-stability-checks (two
`getBoundingClientRect` samples ~16ms apart agreeing within 1px), scrolls into view,
re-reads the post-scroll rect for the clip, then captures ONCE outside the retry so a
capture failure never re-enters the retry channel (`Browser.scala:2039-2081`).
`transparentBackground = true` sets `Emulation.setDefaultBackgroundColorOverride` to
fully transparent for the shot, cleared via `Scope.acquireRelease`
(`Browser.scala:2142-2163`). `screenshotFullPage` reads content/viewport height in one
eval, computes `bandCount = ceil(content / viewport)` in CSS px (DPR-independent), and
freezes once around the band loop (`Browser.scala:1963-2002`).
`screenshotMarks(marks, maxMarks, format, quality)` overlays numbered badges at each
element's top-left corner inside a single `data-kyo-internal` token-tagged subtree,
injected once inside the frozen scope; `marks.size > maxMarks` aborts pre-capture
(`Browser.scala:2092-2136`).

### VERIFY: settling reads

These re-sample through `SettleRead.settle` (the settle-on-reads helper) until the
value holds constant, then map a settled-absent element to the read's twin return:

| Method | Returns | Settled-absent | Citation |
|--------|---------|----------------|----------|
| `boundingRect(selector)` | `Maybe[Bounds]` | `Absent` (twin `boundingBox`); authoritative geometry from `DOM.getBoxModel` after the JS rect stabilizes | `Browser.scala:1590-1619` |
| `computedStyles(selector, properties)` | `Map[String, String]` | abort `BrowserElementNotFoundException` (twin `attribute`) | `Browser.scala:1624-1639` |
| `computedStyle(selector, property)` | `String` | inherited; DELEGATES to `computedStyles(selector, Span(property))` | `Browser.scala:1644-1645` |
| `inViewport(selector)` | `Boolean` | abort `BrowserElementNotFoundException` (twin `isVisible`) | `Browser.scala:1650-1662` |
| `scrollPosition` | `ScrollPosition` | n/a (page always has a scroll position) | `Browser.scala:1667-1674` |
| `waitForStable(timeout)` | `Unit` | n/a; STRICT quiescence wait, aborts `BrowserAssertionTimedOutException` on timeout | `Browser.scala:1680-1681` |

`waitForStable` is the strict on-demand quiescence wait (delegates to
`MutationSettlement.waitForStable`); it is the only VERIFY entry that does NOT route
through `SettleRead`, because it waits for the WHOLE-page DOM to stop mutating rather
than re-sampling one value expression (`Browser.scala:1680-1681`,
`MutationSettlement.scala:72-89`). `computedStyle` delegates to `computedStyles` rather
than re-issuing the read (`Browser.scala:1644-1645`).

### DISCOVER: element introspection

| Method | Returns | Settled-absent | Citation |
|--------|---------|----------------|----------|
| `elementAt(x, y)` | `Maybe[ElementInfo]` | `Absent`; negative coords abort `BrowserInvalidArgumentException` pre-eval | `Browser.scala:1687-1695` |
| `element(selector)` | `Maybe[ElementInfo]` | `Absent` (twin `boundingRect`) | `Browser.scala:1700-1707` |
| `elements(selector = Selector.all)` | `Chunk[ElementInfo]` | `Chunk.empty` via `locateCount` empty-fast-path (twin `textAll`) | `Browser.scala:1713-1727` |
| `ElementInfo.leaves(elems)` | `Chunk[ElementInfo]` | pure filter, no `Frame`, no effect row | `Browser.scala:3866-3867` |

All three reads go through one injected idempotent in-page helper, `DiscoverJs`, gated
by `window.__kyoDiscoverInstalled`: it exposes `window.__kyoDiscoverProbe(el)`
(returning the `ElementInfo` wire shape: tag, id, classes, truncated text, rect-derived
`Bounds`, and the `visible` / `inViewport` / `topmost` / `interactive` flags) and
`window.__kyoUniqueSelector(el)` (an id-anchored-else-nth-of-type CSS path)
(`DiscoverJs.scala:5-80`). `elements` defaults to the net-new `Selector.all` constructor
(`SelectorNode.Css("*")`, `internal/Selector.scala:129`). `ElementInfo.leaves` is PURE:
it keeps elements that are not an ancestor of any other in the chunk, decided from the
unique `selector` path (A is an ancestor of B when B's `selector` starts with A's
`selector + " > "`), with no `Frame` or effect row (`Browser.scala:3861-3868`).

`elementAt` returns `Absent` not only when no element occupies the point but also when
the hit element IS `document.documentElement` or `document.body`: a point that lands on
bare page chrome is treated as "no discoverable element there"
(`Browser.scala:1691-1693`).

### REPRODUCE: frame recording

`screenshotFrames(maxDurationMs, maxFrames, format, quality)(body)` records a screencast
WHILE `body` runs and returns `(Chunk[ScreenshotFrame], A)` events-first, the canonical
`record*` shape (`Browser.scala:3279-3366`). It imposes NO settlement: the caller drives
the visual change. It drives `Page.startScreencast` / `Page.screencastFrame` (event) /
`Page.screencastFrameAck` (per frame) / `Page.stopScreencast` through a per-session
dispatcher (see [Recorders](#recorders-onconsole--recordconsole--screenshotframes)).
`Webp` has no screencast codec, so it maps to `jpeg` and the call still succeeds
(`Browser.scala:3292-3295`). Two caps protect against an unbounded recording, checked
frame-count-first: the frame cap reports `(maxFrames, frame-count)` and the duration cap
reports `(maxDurationMs, elapsed-ms)`, so the abort's two numbers always share one unit
(`Browser.scala:3296-3354`).

### Controls

| Method | Visibility | Settlement | Citation |
|--------|-----------|------------|----------|
| `setViewport(width, height, deviceScaleFactor)` | PUBLIC | settles after | `Browser.scala:2190-2199` |
| `resetViewport` | PUBLIC | settles after | `Browser.scala:2206-2213` |
| `withViewport(width, height, deviceScaleFactor)(body)` | PUBLIC | settles on apply, LIFO restore | `Browser.scala:2226-2249` |
| `scrollTo(x, y)` | PUBLIC | settles after | `Browser.scala:2462-2465` |
| `scrollToElement(selector)` | PUBLIC | `Actionability.withRetry`, settles after | `Browser.scala:2473-2486` |
| `withEmulation(colorScheme, media, reducedMotion)(body)` | PUBLIC | settles on apply, LIFO restore | `Browser.scala:2264-2300` |
| `withHighlights(annotations)(body)` | PUBLIC | settlement-transparent overlay | `Browser.scala:2343-2390` |

`setViewport` / `resetViewport` are now PUBLIC and settle after via
`MutationSettlement.afterAction` (`Browser.scala:2190-2213`). `setViewport` gained a
`deviceScaleFactor: Double` (DPR), threaded into `ViewportParams.deviceScaleFactor` (a
`Double` wire field) and cached on the tab as `BrowserTab.ViewportOverride(width,
height, dpr)` so nested `withViewport` calls restore the prior DPR
(`internal/CdpTypes.scala:269`, `internal/BrowserTab.scala:47`,
`Browser.scala:2234-2245`). `scrollTo(x, y)` is the new coordinate form; the old
selector-form scroll is renamed `scrollToElement` and auto-waits via
`Actionability.withRetry` (channel `BrowserElementException`)
(`Browser.scala:2462-2486`). `withEmulation` and `withHighlights` are documented under
[Emulation](#emulation) and the [overlay teardown invariant](#overlay-teardown-per-handle-token-never-a-shared-global-slot)
above.

### New value types and exception leaf

All nest under `Browser`: `Bounds` (with `right` / `bottom` / `area` derived
accessors, `Browser.scala:3782-3786`), `ScrollPosition` (`3791`), `ElementInfo` (+ the
companion `leaves`, `3845-3868`), `Annotation` (`3835-3839`), `ScreenshotFrame`
(`3797`), `ConsoleMessage` (reshaped: `text` / `location: Maybe[String]` / `offsetMs:
Long`, `3892-3897`), `ConsoleLevel` (extended 3 -> 5: `Log, Info, Warn, Error, Debug`,
`3903-3904`), `ColorScheme` (`3804-3805`), `MediaType` (`3818-3819`), `ScreenshotFormat`
(pre-existing, `3977-3978`). The one new exception is
`BrowserCaptureLimitExceededException(operation, limit, reached)`, a read-channel leaf
extending `BrowserReadException` (so an `Abort[BrowserReadException]` row catches a
capture cap exactly like any other read failure), raised by `screenshotFullPage` /
`screenshotMarks` / `screenshotFrames` (`BrowserException.scala:344-357`).

### Recorders: onConsole / recordConsole / screenshotFrames

Console recording mirrors the existing download precedent: `onConsole` is the
subscriber, `recordConsole` is the convenience built on it, exactly as `onDownload` /
`recordDownloads` (`Browser.scala:3157-3216`, `3047-3112`). `screenshotFrames` is a
third recorder over the same template (`Browser.scala:3279-3366`). All four share one
shape:

- A per-session dispatcher on the `CdpClient` keyed by CDP session id
  (`downloadEventDispatchers`, `consoleEventDispatchers`, `screencastEventDispatchers`),
  registered BEFORE the body runs (`internal/CdpClient.scala:33-36`).
- A bounded unscoped `Channel` plus a forked drainer fiber: the dispatcher offers
  best-effort onto the channel (swallowing `Abort[Closed]` so a full or closed channel
  drops the event rather than parking the CDP reader), and the drainer fiber applies the
  user handler `f`, isolating `f`'s effect row `S` from the dispatcher's `Sync`-only
  type (`Browser.scala:3171-3189`).
- `(using Frame, Isolate[S, Sync, S])` and a row of `Browser & Async & Abort[BrowserReadException] & S`
  (the `Async` is for the drainer fiber) (`Browser.scala:3157-3162, 3205-3208`).
- Teardown (dispatcher restore + channel close) wired with `Scope.run { Scope.ensure(restore).andThen(Scope.ensure(channel.close)) }`
  so it fires on success, failure, AND interruption; the caller's row does NOT carry
  `Scope` (`Browser.scala:3185-3191`).

Two recorder-specific invariants are load-bearing. First, structural-abort recovery to
`Absent`: a `consoleAPICalled` whose CDP `type` is one of the 8 structural variants
aborts inside `decodeConsoleApiCalled`, and `consoleEventToMessage` recovers that abort
to a dropped event so it never poisons the reader fiber (`Browser.scala:3224-3232`,
`2603-2632`). Second, the per-frame ack runs in a DETACHED fiber: the screencast
dispatcher issues `Page.screencastFrameAck` from a `Fiber.initUnscoped` so the reader
fiber stays `< Sync` (the ack carries `Async`; Chrome keeps delivering once it sees the
ack) (`Browser.scala:3307-3314`). The awaited `Page.stopScreencast` on teardown is
bounded by the send's own request timeout, so it never hangs a silent Chrome; a future
fire-and-forget refactor of the stop must preserve that bound
(`Browser.scala:3334-3337`).

The two console paths use DIFFERENT spellings in DIFFERENT decoders, which never
collide: the drain path (`consoleLogs` -> `decodeConsoleMessage`) reads the JS shim's
`'warn'` (`Browser.scala:2578-2593`, `internal/BrowserTab.scala:193-194`), the CDP-event
path (`recordConsole` -> `decodeConsoleApiCalled`) reads CDP's `'warning'`
(`Browser.scala:2603-2632`). Both surface `offsetMs` relative to a baseline: the drain
path uses the per-session `window.__kyoConsoleT0 = Date.now()` set at console-capture
install (`internal/BrowserTab.scala:179`, `Browser.scala:2555-2557`); the recorder path
uses the body's `t0` from `Clock.now` (`Browser.scala:3166-3167`).

### Emulation

`withEmulation(colorScheme, media, reducedMotion)(body)` emulates media features for the
body's duration through a single `Emulation.setEmulatedMedia` send, then restores the
prior state (`Browser.scala:2264-2300`). Two behaviors matter:

- It emulates ONLY the requested features. `emulatedMediaParams` builds the feature list
  from what was passed: `prefers-color-scheme` only when `colorScheme` is `Present`,
  `prefers-reduced-motion: reduce` only when `reducedMotion = true`, omitted otherwise;
  `media` set only when `Present` (`Browser.scala:2309-2319`). So a color-scheme-only
  call leaves the page's real `prefers-reduced-motion` untouched, and `reducedMotion =
  false` means "do not emulate reduced motion", not "force no-preference".
  `ColorScheme.NoPreference` maps to the empty-string clear, not a literal value
  (`Browser.scala:3810-3813`).
- It restores to the HOST values on exit. When a prior override was active it re-applies
  that exact prior state via the same `emulatedMediaParams` composer (apply and restore
  stay in lockstep); when none was active it sends `clearEmulatedMediaParams`
  (`SetEmulatedMediaParams(Present(""), Present(Seq.empty))`), an empty-features clear
  that drops every `prefers-*` override back to the environment value rather than
  enumerating each feature (`Browser.scala:2283-2295, 2326-2327`).

Nesting is currently REPLACE-semantics: an inner `withEmulation` replaces the whole
emulated-media state (it does not inherit the outer call's features inside the inner
body), and the outer prior is restored on inner exit. Whether nesting should COMPOSE or
REPLACE is an open design question, not yet resolved; do not assume composition.

## Conventions

### Effect row and signatures

Every effectful `Browser` method declares an explicit return type of the form
`A < (Browser & Abort[BrowserReadException])` and takes `Frame` as the trailing
`using` parameter (`Browser.scala:1372, 1382`). `BrowserReadException` is the
catch-all error row a new method aborts with; the hierarchy is built so that
`BrowserMutationException` and `BrowserAssertionException` extend it, so an
`Abort[BrowserReadException]` channel catches every browser failure except
lifecycle/setup. New element/assertion exceptions must keep extending up to
`BrowserReadException` (`BrowserException.scala:34, 41, 79`). The new capture-cap
leaf `BrowserCaptureLimitExceededException` follows the rule: it extends
`BrowserReadException` directly (`BrowserException.scala:355-357`).

### Exception hierarchy

The hierarchy layers two axes (`BrowserException.scala:34-96`):

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
(`BrowserException.scala:174-177, 355-357`). An exception with more than one
construction path gets a companion `object` of named smart constructors rather than
overloaded `apply`s with bare strings; each constructor names the failure kind and
formats the message (`BrowserException.scala:146-153, 373-389`). A single-construction
leaf like `BrowserCaptureLimitExceededException` needs no companion
(`BrowserException.scala:355-357`).

`BrowserAssertionTimedOutException` is constructed via the `(expected, actual)`
two-arg overload that auto-derives `check` from the enclosing method name through
the implicit `Frame.calleeName`, so `assertVisible` failures already read
`"assertVisible"` without the call site passing the name. New `assert*` / `waitFor*`
methods rely on this rather than repeating the method name as a string literal
(`BrowserException.scala:377-378`, `Browser.scala:950-953`).

A method that only validates an argument before any CDP call aborts
`BrowserInvalidArgumentException("<methodName>", <message>)` with the public method
name as the first field, distinguishing "called the API wrong" from a runtime
failure. `setFiles` and `setDownloadBehavior` gate absolute-path arguments, and the
new captures/discovery gate their numeric arguments the same way: `screenshotRegion`
rejects non-positive size, `elementAt` rejects negative coordinates, both BEFORE any
CDP call (`Browser.scala:759, 3010, 1942, 1688`). When a public read decodes wire
JSON, a malformed or unexpected payload is surfaced as a typed `Abort`
(`BrowserProtocolErrorException.decodeFailure` / `BrowserAssertionTimedOutException`)
rather than thrown, with the method name passed as the diagnostic tag; `consoleLogs`
and the settling reads (`boundingRect`, `elements`, ...) all do this
(`Browser.scala:2570, 1617, 1723`).

### The return-type discriminator

The return type of a read encodes what "nothing matched" means. Pick the row by
the question the method asks, not by convenience.

| Method kind | Return type | Selector miss behaviour | Examples / citation |
|-------------|-------------|-------------------------|----------|
| Geometry / discovery read (absence is legitimate) | `Maybe[...]` | `Absent` / `Maybe.empty`, no abort | `boundingRect`, `element`, `elementAt`, `role`, `accessibleName` (`Browser.scala:1590-1592, 1700-1707, 1782, 1789`) |
| Content read requiring the element to exist | bare value (`String`, `Int`, `Map`) | abort `BrowserElementNotFoundException` via `selectorNodeDescription(Selector.toNode(selector))` | `text`, `attribute`, `value`, `computedStyles`, `inViewport` (`Browser.scala:1372, 1382, 1461, 1624-1639, 1650-1662`) |
| Existence question ("is X here?") | `Boolean` | `false`, the negative answer IS the answer | `exists` (`Browser.scala:1524-1525`) |
| Property predicate ("is X visible/enabled?") | `Boolean` | abort `BrowserElementNotFoundException` (property is undefined without an element) | `isVisible`, `isEnabled`, `isChecked`, `inViewport` (`Browser.scala:1488, 1497, 1506, 1650-1662`) |
| Count read | `Int` | `0`, not an abort | `count` (retries CDP transients), `countNow` (point-in-time) (`Browser.scala:1431, 1445`) |
| Bulk read (`*All` / discovery collection) | `Chunk[...]` | `Chunk.empty` via a single round-trip empty-fast-path before the retry loop | `textAll`, `attributeAll`, `elements` (`Browser.scala:1818, 1847, 1713-1727`) |

The discriminator twins are the high-value pairs: `exists` returns `false` on miss,
`isVisible` aborts on miss (`Browser.scala:1524-1525, 1488`); `count` retries
`BrowserMutationException` CDP transients, `countNow` is the explicit no-retry
point-in-time variant (`Browser.scala:1431, 1445`). The `<read>` vs `<read>Now` naming
distinguishes "retry CDP transients" from "point-in-time, surface transients
immediately." The visual-QA reads extend the SAME twin rule through `SettleRead.settle`:
a settling read maps a stable-absent element to its twin return (settling `Maybe`
-> `Absent`; must-exist -> typed abort; collection -> empty). `boundingRect` is the
settling `Maybe` (twin of the removed `boundingBox`), `inViewport` is the property
predicate that aborts (twin `isVisible`), and `elements` is the settling collection that
empties (twin `textAll`) (`Browser.scala:1590-1592, 1650-1662, 1713-1727`,
`SettleRead.scala:5-15`).

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
stable-looking set and mask a flickering page (`Resolver.scala:74, 158-164, 189-191`).

### Scoped wrappers and event APIs

A `with*` method is a scoped wrapper returning `A < (Browser & S)` (or `&
Abort[BrowserReadException] & S`) that takes the body as a trailing by-name `A <
(Browser & S)` parameter and restores the prior state on body exit (success,
failure, OR interruption). Pure-config wrappers use `Local.let` (`withConfig`,
`Browser.scala:203, 214`); tab/resource wrappers use `Scope.run` +
`Scope.acquireRelease` / `Scope.ensure` (`withViewport`, `Browser.scala:2226-2249`).
A `with*` wrapper over per-tab sticky CDP state caches the prior value on the
`BrowserTab` (`tab.viewportOverride`, `tab.emulationOverride`, `tab.downloadPolicy`),
and on exit re-applies the prior `Present(...)` override or clears/resets to the
launch-time default on `Absent`, so nested `with*` calls compose
(`Browser.scala:2231-2245`, `internal/BrowserTab.scala:28-30`). `withEmulation`
follows the identical cache-and-restore shape; see [Emulation](#emulation) for its
restore-to-host behavior. The scoped overlay wrappers (`withHighlights`, the freeze
style, `screenshotMarks`) are a distinct case: they hold NO `BrowserTab` cache and
instead tear down by per-handle `data-kyo-token` (see the
[overlay teardown invariant](#overlay-teardown-per-handle-token-never-a-shared-global-slot)).

An event-observation API ships as a pair: a callback `on*[A, S](f: Event => Unit <
(Browser & S))(action: ...)(using Frame, Isolate[S, Sync, S])` that registers a
per-session handler before `action` runs and tears it down via `Scope.run` /
`Scope.ensure`, plus a `record*[A, S](body: ...)` convenience implemented on top
that captures events into an `AtomicRef[Chunk[Event]]` and returns `(Chunk[Event],
A)`. The two pairs are `onDownload` / `recordDownloads` and `onConsole` /
`recordConsole`; new event surfaces add both (`Browser.scala:3047-3112, 3157-3216`).
Per-session handler/recorder registries on the `CdpClient` are updated through
`getAndUpdate` capturing the previous map, and the restore closure re-installs the
prior entry (`Present`) or removes the key (`Absent`), giving LIFO nesting; the
restore is wired with `Scope.run(Scope.ensure(restore).andThen(...))` so it fires on
success, failure, AND interruption (`Browser.scala:3070-3077`). See
[Recorders](#recorders-onconsole--recordconsole--screenshotframes) for the full
dispatcher / channel / drainer shape `screenshotFrames` shares.

A `waitFor*` read is the read-flavour twin of an `assert*` method: it returns the
matched value (`String` / `Int`) instead of `Unit`, takes a `predicate` plus an
optional `schedule`, and provides a convenience overload that uses equality against
`expected` (delegating `_ == expected`). New retrying reads supply both forms
(`Browser.scala:1139-1147, 1167-1170`). An assertion method is named `assert*`,
returns `Unit`, delegates to a `BrowserAssertion.*` helper that retries the
predicate against the active retry schedule, and raises
`BrowserAssertionTimedOutException` on exhaustion; every `assert*` accepts an
optional `schedule: Maybe[Schedule] = Absent` per-call override
(`Browser.scala:950-953, 970-973`). Note the distinction from the visual-QA settling
reads: `assert*` / `waitFor*` REQUIRE the predicate to hold (a non-match is a typed
timeout), whereas a `SettleRead`-backed read accepts whatever value settles and maps
absence to its twin return; `waitForStable` is the strict whole-page quiescence wait,
not a settling-value read.

### Internal-only API

`private[kyo]` is the global convention (root `CONTRIBUTING.md`, "Visibility
Tiers"). The browser-specific case worth naming: some raw CDP setters are hidden
behind scoped wrappers as the public-safety guarantee. `allowDownloads` /
`denyDownloads` / `setDownloadBehavior` are `private[kyo]`, exposed only to tests and
to their public scoped wrapper `withDownloads` (`Browser.scala:2952, 2960, 3004`).

`setViewport` / `resetViewport` are NOT in this category: they are PUBLIC. The
visual-QA surface deliberately exposes them (they settle after via
`MutationSettlement.afterAction`, so the sticky state they leave is observably
quiesced) (`Browser.scala:2190, 2206`). `withViewport` remains the scoped form for
callers who want the override bounded to a block, but the bare setters are a
supported public surface, not a test-only seam.

## Extension recipes

### Add a new CDP command

Following the screenshot/viewport family (`CdpBackend.scala:73-76, 97-100`,
`CdpTypes.scala:327-334`):

1. In `CdpTypes.scala`, add a `final private[kyo] case class ...Params(...) derives
   Schema` for the request and (if the reply carries data) a `...Result(...) derives
   Schema`, placed under the comment block for the owning CDP domain. The visual-QA
   surface adds several here: `SetEmulatedMediaParams`, `StartScreencastParams`,
   `ScreencastFrameAckParams`, the screencast/console wires, plus two fields on
   `ScreenshotParams` (`captureBeyondViewport`, `fromSurface`)
   (`CdpTypes.scala:272-316, 327-334`).
2. In `CdpBackend.scala`, add a `private[kyo] def` under the matching `// ---
   <Domain> domain ---` banner. For a command that returns data, decode via
   `.map(decodeOrFail[Result](_, "Domain.method"))` (`CdpBackend.scala:73-76`); for a
   fire-and-forget command, end in `.unit` (the new `setEmulatedMedia` /
   `startScreencast` / `screencastFrameAck` all do, `CdpBackend.scala:110, 124, 134`).
3. The wrapper's effect row is always `... < (Async & Abort[BrowserReadException])`
   and its first param is `sender: CdpSender` (the trait, never the concrete
   `CdpClient`), with `(using Frame)` (`CdpBackend.scala:73-75`).
4. The `method` string passed to both `send` and `decodeOrFail` is the literal CDP
   method name and must match exactly; it is what `decodeOrFail` reports in
   `BrowserProtocolErrorException` on a CDP error reply (`CdpBackend.scala:25-40`).

### Add a new public read/action method

Following `history` / `cookies` / `reload` (`Browser.scala:346-354, 3419-3424`):

1. Add the `CdpBackend` wrapper first (see the CDP-command recipe); the public
   method calls it.
2. Write the `Browser` method with return type `T < (Browser &
   Abort[BrowserReadException])` (a read returns a typed value / `Chunk`; an action
   returns `Unit`). Resolve the active tab with `Env.use[BrowserTab] { tab => ... }`
   and issue the call against `tab.session` (the session-scoped client), not
   `tab.client` (`Browser.scala:3419-3424`).
3. Map the CDP wire result into a public value type at the boundary; do not leak
   wire case classes outward (`CookieWire.toCookie` at `Browser.scala:3422`;
   `CdpBase64Decode.decodeScreenshotImage` for `screenshot` at `Browser.scala:1925`).
4. An action method returns `Unit` and ends at the `CdpBackend` call
   (`Browser.scala:2462-2465`).
5. A read that does NOT go through `CdpBackend` (page-side state) goes through
   `BrowserEval.evalJs(...)` and decodes the JSON itself, surfacing decode failures
   via `BrowserProtocolErrorException.decodeFailure` (`Browser.scala:2555-2571`). A
   settling page-side read instead routes through `SettleRead.settle` and decodes the
   stable string, mapping settled-absent to the twin return (`Browser.scala:1590-1619`,
   `SettleRead.scala:24-40`).
6. A filtering/convenience overload delegates to the canonical method rather than
   re-issuing the CDP call (`consoleLogs(level)` delegates to `consoleLogs`,
   `Browser.scala:2575-2576`; `computedStyle` delegates to `computedStyles`,
   `Browser.scala:1644-1645`).

### Add a new scoped `with*` wrapper

For a wrapper that toggles per-tab CDP state for the duration of a body and restores
it on exit, following `withViewport` (`Browser.scala:2226-2249`):

1. If the wrapper needs to remember prior state, add an `AtomicRef` field to
   `BrowserTab` and initialise it in `mkBrowserTab`. The existing override caches are
   `viewportOverride: AtomicRef[Maybe[ViewportOverride]]` and `emulationOverride:
   AtomicRef[Maybe[EmulatedMediaState]]` (`internal/BrowserTab.scala:28-29`),
   initialised at `internal/BrowserTab.scala:89-90`.
2. Signature shape: `def with...[A, S](args...)(body: A < (Browser & S))(using
   Frame): A < (Browser & Abort[BrowserReadException] & S)` (`Browser.scala:2226-2228`).
3. Body: `Env.use[BrowserTab] { tab => Scope.run { tab.<ref>.get.map { prior =>
   Scope.acquireRelease(<set new state>) { _ => <restore prior> }.andThen(body) } }
   }`. The release branch matches on `prior`: a previously-active value is re-applied
   and `Absent` clears the override, giving correct LIFO nesting
   (`Browser.scala:2231-2245`).
4. Use `Scope.acquireRelease` (or `Scope.ensure`) inside an inner `Scope.run`, NOT
   `Sync.ensure`: `Sync.ensure` does not fire on Abort short-circuits and leaks the
   state; the inner `Scope.run` bounds the unwind to this call so nesting keeps LIFO
   restore (`Browser.scala:2230-2248`).
5. Variant: when restoring a per-session registry entry (not a per-tab `AtomicRef`),
   snapshot the previous map with `getAndUpdate`, then in the restore re-insert
   `Present(prev)` or `remove` the key when it was `Absent` (`Browser.scala:3070-3075`).
6. Variant: a settlement-transparent overlay wrapper (`withHighlights`) caches NO
   tab state. It injects a `data-kyo-internal` node carrying a unique `data-kyo-token`,
   returns the token from the inject eval, and removes by token in the release. Never a
   shared global slot (`Browser.scala:2343-2390`; see the
   [overlay teardown invariant](#overlay-teardown-per-handle-token-never-a-shared-global-slot)).

### Add a new CDP event with per-session fan-out

For a CDP event Chrome pushes with no request id, routed to per-tab subscribers
without stalling the reader, following the `Page.downloadWillBegin` /
`downloadProgress` path and its two siblings (`screencastEventDispatchers` for
`Page.screencastFrame`, `consoleEventDispatchers` for `Runtime.consoleAPICalled` /
`exceptionThrown`) (`CdpClient.scala:33-36, 391-397, 544-552`):

1. Add a per-session dispatcher registry field to `CdpClient`'s constructor and
   initialise it in `CdpClient.init`. The three event registries are
   `downloadEventDispatchers` / `screencastEventDispatchers` / `consoleEventDispatchers`,
   each `AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]`
   (`CdpClient.scala:34-36`), initialised in `init` (`CdpClient.scala:301, 305`) and
   threaded into the `decodeCdpMessage` signature (`CdpClient.scala:477-479`) and both
   `new CdpClient(...)` constructions (`init` at `CdpClient.scala:365`, `withSession`
   at `CdpClient.scala:121`).
2. Add the event's method name(s) to `eventWhitelist`; un-whitelisted events are
   dropped so the bounded event channel cannot fill when nobody is subscribed
   (`CdpClient.scala:391-397`). Only five CDP event methods are whitelisted
   (`Page.downloadWillBegin`, `Page.downloadProgress`, `Page.screencastFrame`,
   `Runtime.consoleAPICalled`, `Runtime.exceptionThrown`); everything else is dropped
   so Page/Network/Runtime lifecycle chatter cannot fill the bounded event channel and
   stall the reader (`CdpClient.scala:391-397`).
3. In `decodeCdpMessage`'s no-id whitelisted branch, route via `dispatchOrPush`: when a
   per-session dispatcher is registered it consumes the event (`Exchange.Message.Skip`),
   else the event is pushed to `exchange.events` (`Exchange.Message.Push`). The routing
   is mutually exclusive (dispatch OR push, never both), and each domain dispatches to
   its own registry map (`CdpClient.scala:409-421, 544-552`).
4. Frame-context-style events that should NEVER reach the events channel (consumed
   only inline) use a separate registry (`frameEventDispatchers`) and an
   UNCONDITIONAL dispatch arm placed before the whitelist check, always ending in
   `Exchange.Message.Skip` (`CdpClient.scala:521-534`).
5. Add a typed wire case class for the event's `params` in the relevant file (for
   example `DownloadWillBeginWire` / `DownloadProgressWire` in `PageDownload.scala`
   at `PageDownload.scala:73-92`, or the screencast/console wires in
   `CdpTypes.scala:279-316`) and a private `parse...Event` decoder in `Browser` that
   decodes `CdpEventParams[Wire]` from `ev.paramsJson`, returning `Maybe[PublicEvent]`
   (`Absent` on the wrong method or a decode failure, never an abort)
   (`Browser.scala:3120-3135, 3373-3405`).
6. Add the public subscriber method (`onDownload` / `onConsole`) that registers a
   `CdpEvent.Generic => Unit < Sync` handler into the registry BEFORE the body runs,
   drains events through a bounded unscoped `Channel` plus a forked drainer fiber (to
   isolate the user handler's effect row `S` from the dispatcher's `Sync`-only type),
   and unregisters via `Scope.ensure` inside an inner `Scope.run`. Requires `(using
   Frame, Isolate[S, Sync, S])` (`Browser.scala:3047-3090, 3157-3196`).
7. The dispatcher handler must never block the CDP reader fiber: it offers to the
   channel best-effort and swallows `Abort[Closed]` (a full or closed channel drops
   the event rather than parking the reader) (`Browser.scala:3059-3065, 3171-3178`).
   When the handler itself needs to issue a CDP command (the screencast per-frame ack),
   it forks a DETACHED fiber so the reader fiber stays `< Sync`
   (`Browser.scala:3310-3314`).

### Add a new recorder

A `record*` / `.recorded` passive collector snapshots events into an in-memory
`Chunk` and returns `(events, result)`, following `recordConsole` / `recordDownloads`
and `withDialogs.recorded` (`Browser.scala:3205-3216, 3101-3112, 2722-2745`):

1. Preferred form: implement the recorder in terms of the existing subscriber
   (`onConsole` / `onDownload`) with a capture handler that appends each event to an
   internal `AtomicRef[Chunk]`, then return `(collected.get, result)`. No new registry
   or channel bookkeeping (`Browser.scala:3205-3216, 3101-3112`).
2. Lower-level form (when the event is delivered by the CDP reader fiber rather than
   a subscriber method): register an `AtomicRef[Chunk[Event]]` into a per-session
   recorder registry on `CdpClient` (for example `dialogRecorders` at
   `CdpClient.scala:480`), restore the previous registry entry via `Scope.ensure`, and
   return `(recorder.get, result)`. The reader fiber appends in arrival order
   (`Browser.scala:2722-2745`).
3. For the lower-level form, add the append site in `CdpClient` where the reader
   decodes the event (for example `recordDialogEvent` decodes `CdpEventParams[Wire]`
   and appends a typed `Browser.<Event>` to the per-session recorder, no-op when none
   is registered). The recorder is a passive observer and must NOT influence any
   auto-handler decision (`CdpClient.scala:571-601, 517`).
4. A recorder's signature requires `(using Frame, Isolate[S, Sync, S])` and returns
   `(Chunk[Event], A) < (Browser & Async & Abort[BrowserReadException] & S)`; arrival
   order is preserved because the single per-session fiber/reader serialises appends
   (`Browser.scala:3205-3216, 3279-3287`). `screenshotFrames` is the screencast variant
   of this shape: it adds a poison cell so a frame/duration cap aborts after `body`
   returns rather than mid-stream (`Browser.scala:3296-3354`).

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
   empty-fast-path for `*All` reads (`Browser.scala:1524-1525, 1488, 1431, 1818`). Use
   `Maybe` / `Result` / `Chunk` / `Span`, never `null` or a string sentinel
   (`Resolver.scala:7-11`). A SETTLING read follows the same twin rule via
   `SettleRead.settle`: stable-`Maybe` -> `Absent`, must-exist -> typed abort,
   collection -> empty (`SettleRead.scala:5-15`).
3. **Declare the effect row.** `A < (Browser & Abort[BrowserReadException])` with
   `Frame` as the trailing `using` parameter (`Browser.scala:1372, 1382`). A recorder
   (`record*` / `screenshotFrames`) adds `& Async & S` and `(using Frame, Isolate[S,
   Sync, S])` for its drainer fiber (`Browser.scala:3205-3208, 3279-3287`).
4. **Wire in the right settlement mechanism:**
   - Interaction: `Actionability.withRetry { Actionability.withActionable(selector,
     requireFillable = ..., requireEnabled = ...) { ref =>
     MutationSettlement.afterAction(<action>)(scopeSelector = Present(selector)) } }`
     (`Browser.scala:445-453, 504-514`). Decide `requireEnabled` per the
     hover/press rule (`Actionability.scala:46-48`). Skip mutation settlement and arm
     the navigation watcher on a nav-intent path (`Browser.scala:447-452`).
   - Navigation: arm `NavigationWatcher`, replicate the same-URL no-op and `data:`
     URL auto-downgrade traps (`Browser.scala:329-336, 324-328`).
   - Assertion or retrying read: route through `BrowserAssertion` /
     `StabilitySampler`; name it `assert*` (returns `Unit`) or `waitFor*` (returns
     the matched value), provide the optional `schedule: Maybe[Schedule] = Absent`
     override, and for `waitFor*` supply both the predicate form and the equality
     convenience overload (`Browser.scala:950-953, 1139-1147, 1167-1170`).
   - Settling read (geometry / style / discovery): route through `SettleRead.settle`,
     mapping settled-absent to the twin return; do NOT call `BrowserAssertion.withStability`
     directly (`Browser.scala:1590-1619`, `SettleRead.scala:24-40`).
   - Capture: wrap the base capture in `HoldStill.withHoldStill` (or
     `withFrozenPage` + `holdStillFrame` for multi-band); it is best-effort and never
     aborts on timeout (`HoldStill.scala:93-153`).
   - Recorder: register a per-session dispatcher, drain through a bounded `Channel` +
     forked drainer fiber, restore via `Scope.ensure`; events-first return
     (`Browser.scala:3157-3216`).
   - Pure read: page-side state through `BrowserEval.evalJs`, CDP-backed read through
     a `CdpBackend` wrapper against `tab.session` (`Browser.scala:2555-2571,
     3419-3424`).
5. **If a new wait must catch a fast in-page transient, keep the entire loop inside
   one `awaitPromise=true` eval.** Never split it into separate `Runtime.evaluate`
   polls (`MutationSettlement.scala:177-182`, `StabilitySampler.scala:1-19`). The
   hold-still capture loop is the documented exception (each iteration is a full CDP
   round-trip) (`HoldStill.scala:16-21`).
6. **Do not widen the actionability retry channel.** It is narrowed to
   `BrowserElementException` on purpose; widening it back to
   `BrowserMutationException` blows the mutation timeout budget
   (`Actionability.scala:479-495`). This binds `screenshotElement` and
   `scrollToElement` too: both wrap `Actionability.withRetry` (channel
   `BrowserElementException`) (`Browser.scala:2039, 2476`).
7. **Pick the exception.** One row position (`BrowserReadException` <-
   `BrowserMutationException` <- `BrowserAssertionException`) plus one or more topical
   markers; `final case class ... derives CanEqual` with the message rendered in the
   `extends` clause; smart constructors for multi-path construction, none for a
   single-construction leaf (`BrowserException.scala:34-96, 174-177, 146-153,
   355-357`). Validate args before any CDP call with
   `BrowserInvalidArgumentException("<methodName>", ...)` (`Browser.scala:759, 1688,
   1942`).
8. **Map wire to public types at the boundary.** Do not leak wire case classes;
   surface decode failures as `BrowserProtocolErrorException.decodeFailure`
   (`Browser.scala:3422, 2555-2571`).
9. **Decide visibility deliberately.** Hide a raw CDP setter behind a scoped `with*`
   wrapper and mark it `private[kyo]` ONLY when the bare setter is genuinely test-only
   (the download setters, `Browser.scala:2952, 3004`). `setViewport` / `resetViewport`
   are the counter-example: they are PUBLIC because they settle after, so the sticky
   state they leave is observably quiesced (`Browser.scala:2190, 2206`). Either way,
   follow the cache-and-restore recipe so a scoped wrapper composes with LIFO restore
   on success, failure, AND interruption (`Browser.scala:2231-2245`). For an injected
   overlay, use the per-handle `data-kyo-token` teardown, never a shared global slot
   (`Browser.scala:2343-2390`).
10. **Add cross-platform tests under `shared/src/test`.** Extend `BrowserTest` for a
    Chrome-backed suite, `Test` for a pure suite. Gate every wait on a barrier, not a
    sleep; split into a platform tree only when the behavior is genuinely
    platform-bound and document why (`BrowserTest.scala:74`,
    `jvm/src/test/scala/kyo/internal/BrowserLauncherJvmTest.scala:9-12`).
