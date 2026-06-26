# Contributing to kyo-ui

This is the module-specific contributor guide for kyo-ui. It carries only what is particular to
this module. For every general Kyo convention (effect rows, naming, `using` ordering, `inline`,
Kyo types over stdlib, the safe-by-default tier, test base classes, the "fix the code not the
test" and "reproduce before you fix" rules), the [root CONTRIBUTING.md](../CONTRIBUTING.md) is
the authority. When this file and the root differ on a generic rule, the root wins; this file
never restates a root rule, it points at it.

kyo-ui is the declarative UI layer for Kyo: a pure-value AST, `Signal` reactivity, and three
runners (Scala.js DOM, HTTP server-push, HTML stream). Its core model mirrors kyo-threejs: factories
produce plain case-class values; the runner materializes them; reactivity threads through via
`Signal`/`SignalRef`.

## Core model

A `UI` is a **pure immutable value**. Every factory call (`UI.div`, `UI.button`, `UI.input`,
`UI.host`) allocates a plain case class with no effect, so the result is a `val`: shareable,
testable on the JVM, and serializable to HTML. Reactivity is wired at construction time by
referencing a `Signal`; the framework registers subscriptions as the value is built, not at
render time.

The three runners materialize this value into DOM, HTTP, or a stream. A runner is not part of
the API contract; the same `UI` value works on all three targets.

## Platform layout

kyo-ui is a `crossProject(JVMPlatform, JSPlatform, NativePlatform, WasmPlatform)`:

- `shared/src/main`: the pure surface (AST, factories, `Signal` integration, `Style`, `Length`),
  the HTML renderer (`HtmlRenderer`), and the server-push backend (`UIServer`, `UIExchange`).
  None of these touch a browser type, so all of it is testable on the JVM with no browser. The
  server-push backend lives here because it produces HTML strings and drives a `UIExchange`, with
  no JS-only dependency.
- `js-wasm/src/main`: the JS + Wasm DOM backend (`DomBackend`, `DomStyleSheet`), the host-mount
  bridge (`DomHostMount` in `UIHostMount.scala`), and the client entry point (`UIMount`, with
  `UILocation` / `UIWindow`). Compiles under both JS and Wasm. The client entry point is the
  `UI.runMount` extension on `UI.type`, not a top-level export.

The shared source must not import `org.scalajs.dom`, `scala.scalajs.*`, or any JS-only type.
Platform-specific behavior belongs in the appropriate platform subtree.

## The host-bridge discipline

`UI.Ast.Host` is the seam through which external renderers (a 3D canvas, a chart widget, a map)
can mount into a kyo-ui tree as first-class children. The rules below are binding for all code
that touches `HostNode`, `DomHostMount`, `DomBackend.fireHostMounts`, and `UI.host`.

### What a host node is

`UI.Ast.Host` is a `UI.Ast.HostNode` plus a `Maybe[HostMount]`. In the shared source, `HostMount`
is an empty trait; platform implementations supply the concrete type. On JS/Wasm,
`DomHostMount(run: dom.Element => (Unit < (Async & Scope)))` is the only `HostMount`. The host node
renders as a bare `<tag>` in HTML (via `HtmlRenderer`); the mount closure runs later, once the
element is attached to the DOM. The `Scope` in the mount's row is load-bearing: the closure's
releases are bound to the ambient page `Scope`, so they run at page teardown.

### The one-shot mount contract

`DomBackend.fireHostMounts` walks the AST and, for each `HostNode` with a `DomHostMount`, resolves
the live element by `data-kyo-path` and runs the closure exactly once. It does NOT run again on
subsequent signal-driven re-renders. This is the contract: a host element, once mounted, is owned
by its external renderer and must not be replaced or re-created by kyo-ui.

Host survival on re-render is STRUCTURAL, not a special case in a diff. Two facts make it hold:

- **A const host node is not inside a reactive zone.** A reactive zone (`Reactive` / `Foreach`) is
  the only thing rendered as a `<span data-kyo-reactive>` wrapper carrying a `data-kyo-path` (see
  `HtmlRenderer.renderTo`). On a signal emission, `LocalExchange.onChange` replaces only that
  wrapper element's `el.outerHTML`, keyed by the reactive zone's own `data-kyo-path`. A host placed
  in a const subtree renders as a plain `<tag>`, never wrapped in a reactive span, so it is never
  the target of that replace. A sibling reactive zone re-rendering swaps its own wrapper and leaves
  the host's DOM node untouched.
- **`fireHostMounts` does not descend into reactive zones.** Its walk skips the `Reactive` /
  `Foreach` cases (they fall to the `case _` that fires no host), so a host is only ever mounted
  from a const subtree. A host under a signal whose subtree a re-render may replace would be torn
  out from under its renderer, so the walk deliberately never fires one there.

A host element that ended up replaced on a sibling re-render would break the external renderer's
lifetime contract; the structure above is what prevents it.

### The `data-kyo-path` addressing scheme

`HtmlRenderer.renderTo` assigns `data-kyo-path="..."` to every element as it walks the AST.
The root element (rendered with `Seq.empty`) gets `data-kyo-path=""` (the empty string). Its
children get `"0"`, `"1"`, ...; grandchildren get `"0.0"`, `"0.1"`, etc. The path is
`path.mkString(".")` where `path` is the index sequence at each level.

`DomBackend.fireHostMounts` queries: `document.querySelector('[data-kyo-path="<path>"]')`. A
wrong path silently returns `null` and the mount closure never runs. When adding a new probe or a
new mount configuration, verify the path by checking what `HtmlRenderer` would produce for the
given tree depth. For a tree `UI.div(UI.host("canvas"))` mounted via `UI.runMount` into
`document.body`, the div is at path `""` and the host is at `"0"`, not `"0.0"`.

### How external renderers use the host bridge

An external renderer (`kyo-threejs`, a chart library, a map widget) builds a `UI.Ast.Host` by
calling `UI.host(tag)(mount)` where `mount` is a `dom.Element => (Unit < (Async & Scope))` closure.
Inside `mount`:

1. The renderer acquires its resources (a `WebGLRenderer`, a chart library context) under
   `Scope.acquireRelease`. The `Scope` in the mount's row is the ambient page `Scope` of the
   enclosing `UI.runMount` call, so teardown is tied to it: page teardown runs the releases.
2. The renderer starts its own loop or subscription (a `requestAnimationFrame` loop, a
   `Signal` observer) as a forked `Fiber` under the same `Scope`.
3. The renderer must NOT call `fireHostMounts` or any other kyo-ui internal. The host bridge
   is one-directional: kyo-ui calls the mount closure; the external renderer does not call back
   into kyo-ui's DOM machinery.

### Cross-file visibility

`DomHostMount` is `private[kyo]`, not public. An external renderer in a separate module
(e.g., `kyo-threejs`) accesses the host bridge through the public `UI.host(tag)(mount)` factory,
which builds the `DomHostMount` on the caller's behalf. The factory is the public contract;
`DomHostMount` itself is an implementation detail.

`fireHostMounts` is object-private inside `DomBackend` (the `DomBackend` object itself is
`private[kyo]`). It is called only from `DomBackend.mountInto` after the initial render; nothing
outside the object invokes it.

## Invariants

These are the load-bearing properties of the module. A change that makes one of these false is
incomplete until the invariant and its test are restored.

- **Fine-grained updates, no full re-render.** A signal emission patches only the subscribed
  subtree. A host element adjacent to a reactive subtree is never replaced. Guarded by
  `DomBackendTest` (sibling identity) and `HostMountBrowserTest` (real browser).
- **One mount per host, per lifetime.** `fireHostMounts` runs the closure exactly once per host
  element, at the end of `mountInto`. A second `mountInto` call on a page that already has host
  elements would run mounts a second time; avoid re-calling `mountInto` on a live page.
  Guarded by `HostMountBrowserTest` (mount count assertion).
- **Host scope tied to page scope.** A host mount's resources live under the ambient `Scope` of
  `UI.runMount`. Closing that scope releases every host renderer. If a host mount forks a fiber
  under a nested inner `Scope`, that inner scope must close before the page scope, or the
  resources outlive the page. Guarded by `ThreeEmbedBrowserTest` (GL context lost after scope
  close).
- **Addressing is stable.** `data-kyo-path` values are stable across signal-driven re-renders of
  sibling subtrees. A host's path does not change because a neighboring reactive node re-rendered.
  This stability is what lets `fireHostMounts` resolve the element after the initial render, and
  it is guaranteed by the fact that `mountInto` is called once and `fireHostMounts` runs once at
  that point.

## Server-push host-node transport

The same `UI` value runs as a client single-page app or as a server-push page, and a host node (a 3D
canvas, a chart, a map) works under both. The server-push host transport is the seam that lets the
server feed reactive DATA to a client-owned host node over kyo-ui's single existing WebSocket. This
section documents the kyo-ui side of that seam: the `UI.runHandlers` 2-arg overload and
`PageHead.moduleScript`, the wire types (`HtmlOp.HostUpdate` carrying a `HostPayload`, and the
`UIEvent.AppEvent` back-channel), the `UIServer` session hooks a feed runner forks against, and the
invariants that govern them. kyo-threejs's `Three.Feed` is the reference consumer; the kyo-ui side
carries only the transport, never the host's scene.

### The runner selector: client vs server-push

The same `UI` value supports two interactive runners; the `UI` tree and its `Signal` wiring are
identical for both, and the runner is the only choice that determines client vs server-push:

- `UI.runMount` (JS/Wasm): mounts to the live DOM as a single-page app. The client owns signal
  subscriptions; host mounts fire via `DomBackend.fireHostMounts` exactly once.
- `UI.runHandlers` (shared): serves a page GET and a WebSocket route. The server owns signal
  subscriptions; DOM diffs ride the single WS as `HtmlOp.Replace` / `HtmlOp.Remove` ops.

The server-push host feed is a layer on top of the `UI.runHandlers` substrate: a downstream feed
runner (kyo-threejs's `Three.Feed.run`) reuses the same SSR page handler and serves its own
WebSocket session that pushes `HtmlOp.HostUpdate` feed frames on the same socket.

### The public overload and the PageHead.moduleScript field

`UI.runHandlers` has two overloads (both in `kyo-ui/shared/src/main/scala/kyo/UI.scala`):

```scala
// 1-arg form: delegates to the 2-arg form with PageHead("kyo-ui")
def runHandlers(basePath: String)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync

// 2-arg form: the one implementation; accepts a configurable PageHead
def runHandlers(basePath: String, head: UI.PageHead)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync
```

The 1-arg form delegates unconditionally to the 2-arg form:

```scala
def runHandlers(basePath: String)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync =
    runHandlers(basePath, PageHead("kyo-ui"))(ui)
```

This is the overload-delegates-to-canonical pattern: a single implementation, no duplicated logic,
and the 1-arg form's rendered HTML is byte-identical to the 2-arg form's with the default head.

The `head` parameter is a `UI.PageHead`. Its `moduleScript` field (`Maybe[String]`, default
`Absent`) emits a `<script type="module" src="...">` in the served page when `Present`. A
server-push app that needs a Scala.js client island (the browser-side host renderer) sets this field
to the island's bundle path:

```scala
val head = UI.PageHead("My App", moduleScript = Present("/_kyo/island.js"))
UI.runHandlers("/", head)(myUI)
```

The inline server-push client script (the WS reconnect loop and DOM patcher) is always present; the
`moduleScript` link is appended after it. The client island mounts the host renderer and connects
each fed signal id to the WS; it receives every signal's current value on its first feed observation,
so it needs no separate init round-trip.

`PageHead.importMap` (`Seq[(String, String)]`, default empty) is the no-bundler companion to
`moduleScript`: when the island is a plain `fastLinkJS`/`fullLinkJS` ESModule that imports bare npm
specifiers (such as `three`), each `(specifier, url)` entry maps one to a served module URL. The
renderer emits a single `<script type="importmap">{"imports": {...}}</script>` in the head before the
`moduleScript`, so the browser resolves the bare imports without a pre-bundling step. Empty (the
default) emits no import map, the behavior before the field existed.

`UIServer.handlers` (`kyo-ui/shared/src/main/scala/kyo/internal/UIServer.scala`) is the implementation
`UI.runHandlers` delegates to. `UIServer.pageHandler` exposes the SSR page GET in isolation, for a feed
runner that serves its own WebSocket route on the same base path while reusing the identical page.

### The host transport adds no slot to `UI.Ast.Host`

`UI.Ast.Host` (defined in `kyo-ui/shared/src/main/scala/kyo/UI.scala`) carries a single mount slot:

```scala
final case class Host(
    attrs: Attrs = Attrs(),
    hostTag: String = "canvas",
    mount: Maybe[HostMount] = Absent  // client: DomHostMount (JS/Wasm only)
)(using val frame: Frame) extends HostNode
```

`mount` carries a `DomHostMount`; `DomBackend.fireHostMounts` reads it on the client to run the host
renderer once in the live DOM (see "The host-bridge discipline" above). The server-push transport
adds NO slot to the host node: the server never reads `mount`, never builds the host's scene graph,
and learns only the set of string signal ids the feed runner's `ui` builder registered. The host
renders as a plain const `<canvas>` (or other tag) the client owns; everything server-push is carried
by the WS frames below, not by a field on the AST.

### Wire types: HostUpdate out, AppEvent in, no closure on the wire

The host transport reuses kyo-ui's single existing WS codec (`Json.encode[HtmlOp]` /
`Json.decode[UIEvent]` in `UIServer`). No new channel or transport is introduced.

Server-to-client host updates ride `HtmlOp.HostUpdate`
(`kyo-ui/shared/src/main/scala/kyo/internal/HtmlOp.scala`):

```scala
case class HostUpdate(path: Seq[String], payload: HostPayload) extends HtmlOp derives Schema
```

`HostUpdate` carries a `path: Seq[String]` routing key; the feed runner sets it to the fed signal id,
and the inline kyo-ui clientJs routes the frame by that key into `window.__kyoHostChannels` (the per-id
receiver the client island registered). kyo-ui's own DOM patcher ignores `HostUpdate`: it is never a
`Replace` and never a `Remove`, so a const host node is never re-rendered out from under its client
renderer.

`HostPayload` (`kyo-ui/shared/src/main/scala/kyo/internal/HostPayload.scala`) carries two
feed-by-signal-id leaves:

```scala
final case class SignalUpdate(signalId: String, encoded: String) extends HostPayload
final case class SignalChunk(signalId: String, encoded: String) extends HostPayload
```

`SignalUpdate` is a scalar/prop feed: a signal id and the `Json.encode`d string of the
`Schema`-serialized fed value `A`, decoded client-side with the same `Schema[A]`. `SignalChunk` is a
structural feed: a signal id and the `Json.encode`d string of a `Schema`-serialized `Chunk[A]`
snapshot, which the client decodes and writes to a mirror `SignalRef[Chunk[A]]` for its own keyed
reconciler to diff locally. Every leaf derives `Schema`; there is no `js.Dynamic` and no closure on the
wire.

Client-to-server app events ride `UIEvent.AppEvent`
(`kyo-ui/shared/src/main/scala/kyo/internal/UIEvent.scala`):

```scala
case AppEvent(path: Seq[String], eventId: String, encoded: String)
```

A client `onClick` running locally on the live scene posts an `AppEvent` carrying the `Json.encode`d
typed event under an `eventId`. The server routes it by `eventId` to the registered handler, which
decodes with the same `Schema[A]`. The wire carries the typed event DATA as an opaque string, never a
function and never a JS-runtime type.

### The serveSession hooks: afterTree and the app-event router

`UIServer.serveSession` (`kyo-ui/shared/src/main/scala/kyo/internal/UIServer.scala`) runs one
full-duplex session under a `Scope.run`. It has three overloads; the most general takes two hooks the
plain server-push path leaves as no-ops and a feed runner supplies:

1. Evaluates `ui` once to get the tree, normalizes it with `ReactiveUI.normalize`, and subscribes
   reactive zones via `ReactiveUI.subscribe`.
2. Runs the `afterTree(uiTree)` hook, forked under the same session `Scope`. The feed runner's hook
   reads what the single `ui` build registered and forks one observer per fed signal id; each emission
   calls `UIServer.emitHostUpdate`, which encodes one `HtmlOp.HostUpdate` and puts it on the WS.
3. Races the inbound-message loop against `ws.onPeerClose`. `dispatchEvent` decodes each frame: a
   `UIEvent.AppEvent(_, eventId, encoded)` is handed to the `appEvent(eventId, encoded)` router (the
   feed runner routes it by `eventId` to the registered handler); every other event is dispatched
   through the normal DOM event handler path.
4. When the session ends (peer close or scope teardown), `Scope.run` interrupts every fiber forked
   under the session `Scope`: the reactive subscription, the feed observers, and any driver the builder
   forked.

`emitHostUpdate` reuses the same `ws.put(Json.encode[HtmlOp](op))` sink as the DOM-diff path; a
`Closed` (the socket closed mid-push) drops the now-moot op, a `Panic` propagates. App-event handlers
run inline in the message loop: a well-behaved handler that returns feeds its result back over the same
WS; a handler that panics tears the session down, exactly as a panicking DOM-event handler does.

### Invariants for the host transport

These extend the invariants listed above and carry the same weight: a change that makes one false
is incomplete until the invariant and its test are restored.

- **Single transport, end to end.** Host updates and app events ride kyo-ui's single existing WS
  (`Json.encode[HtmlOp]` / `Json.decode[UIEvent]`). No bespoke channel, no second WS, no SSE. Enforced
  by `kyo-threejs/js/src/test/scala/kyo/ThreeFeedRunBrowserTest.scala` ("Three.Feed.run: one cube
  animates client-side AND steps color from the public server feed over the WS"), which drives the
  public serve path over a real WebSocket and asserts the server-fed color reaches the client.
- **Wire carries no closure, no `js.Dynamic`.** Every value on the wire is a typed, `Schema`-derived
  Scala value carrying its payload as an opaque `Json.encode`d string. Enforced by
  `kyo-ui/shared/src/test/scala/kyo/internal/HostPayloadTest.scala` ("SignalUpdate(encoded Int) Schema
  round-trip (feed leaf round-trip)", "SignalChunk(encoded Chunk[Int]) Schema round-trip (structural
  feed leaf round-trip)", and "AppEvent(encoded payload) Schema round-trip (app-event back-channel leaf
  round-trip)").
- **One feed entry per `serverSignal`, registered once per session.** A `serverSignal` call inside a
  feed session registers exactly one observer entry; outside a session it registers nothing and returns
  a bare ref. The feed runner reads the registry once after the single `ui` build and forks one observer
  per id, never re-registering on a re-render. Enforced by
  `kyo-threejs/shared/src/test/scala/kyo/ThreeFeedTest.scala` ("serverSignal[A] inside a run session
  registers one feed entry that emits a SignalUpdate", "serverSignal[Chunk[A]] inside a run session
  registers one structural feed entry", and "serverSignal[A] outside a run session returns a working ref
  and registers nothing").
- **The app-event back-channel routes by `eventId`; a bad event never tears the session down.** An
  inbound `AppEvent` is routed by `eventId` to its registered handler, which decodes with the handler's
  `Schema`; a decode failure or an event for an unregistered id is a log-and-skip. Enforced by
  `kyo-threejs/shared/src/test/scala/kyo/ThreeFeedTest.scala` ("onAppEvent inside a run session registers
  a handler that decodes and runs on the encoded event" and "onAppEvent outside a run session is a no-op
  (no handler recorded)") and end-to-end by
  `kyo-threejs/js/src/test/scala/kyo/ThreeFeedEmitBrowserTest.scala` ("Three.Feed.emit: a client click
  posts an app event the server reflects into a visible color step").
- **`emit` with no feed channel bound fails typed, never silently.** A client `emit` outside a bound
  feed context aborts with a typed `ThreeException.FeedUnavailable` visible in the row, not a silent
  drop. Enforced by `kyo-threejs/shared/src/test/scala/kyo/ThreeFeedTest.scala` ("emit with no feed
  channel bound fails with the typed FeedUnavailable").
- **Session-scoped teardown.** The reactive subscription, the feed observers, and any builder-forked
  driver all bind to the session `Scope` of `UIServer.serveSession`'s `Scope.run` (the feed runner forks
  each observer with `Fiber.init` under that `Scope`). A disconnect interrupts every one; no fiber
  outlives the session. This holds structurally from `Scope.run` plus the under-`Scope` `Fiber.init`
  forks; it has no dedicated unit test.
- **`data-kyo-path` addressing is stable.** The path stability a host element relies on across sibling
  re-renders follows from the existing addressing invariant ("Addressing is stable" above).

### Recipe: a server-push host feed

A module that feeds reactive data to a client-owned host node (kyo-threejs's `Three.Feed` is the
reference implementation) composes the kyo-ui transport seam rather than adding anything to the AST.
The seam is `private[kyo]`, so the consumer lives in package `kyo`:

1. Serve the page with `UIServer.pageHandler(basePath, head)` and link the client island bundle through
   `head.moduleScript`. Serve a WebSocket route on the same base path.

2. In the WS route, run `UIServer.serveSession(ws, ui)(afterTree)(appEvent)`. The `ui` builder runs once
   under the session `Scope`; it registers the fed signal ids (and any app-event handlers) it owns.

3. In `afterTree`, fork one observer per registered fed signal id under the ambient (session) `Scope`
   with `Fiber.init`, and push each emission with `UIServer.emitHostUpdate(ws, Seq(id), payload)`, where
   `payload` is a `HostPayload.SignalUpdate(id, encoded)` or `SignalChunk(id, encoded)`:

   ```scala
   Fiber.init {
       myFedSignal.observe(value => UIServer.emitHostUpdate(ws, Seq(id), encode(id, value)))
   }.unit
   ```

   The fiber binds to the session `Scope` and is interrupted on disconnect.

4. In `appEvent`, route each `(eventId, encoded)` to the matching registered handler, decoding with the
   handler's `Schema`; treat a decode failure or an unregistered id as a log-and-skip.

The client island (linked via `moduleScript`) mounts the host renderer and registers a per-id receiver
on `window.__kyoHostChannels`; the inline kyo-ui clientJs routes each inbound `HostUpdate` to it by key.
Deterministic tests for the registry and the typed surface live in `shared/src/test` (see `ThreeFeedTest`);
the live WS round-trip is proven by the browser tests (`ThreeFeedRunBrowserTest`, `ThreeFeedEmitBrowserTest`,
`ThreeFeedChunkBrowserTest`).

## Test patterns

Cross-platform tests for pure logic (style rules, AST shape, renderer output to HTML) live in
`shared/src/test` and run on JVM, JS, and Native. Browser tests that require a real DOM (the DOM
backend, the host mount seam, reactive update counts) live in `js-wasm/src/test` or, for tests
that need a real Chrome (a host mount fired in a live page, a GL context released), in the
downstream `kyo-threejs/js/src/test`. Follow the root file-naming rule: every test file shares a
name prefix with the source it covers.
