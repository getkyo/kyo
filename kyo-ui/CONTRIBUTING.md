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

This section documents the server-push variant of the host bridge, added by the kyo-threejs-reactive
campaign. It covers the one new public symbol (`UI.runHandlers` 2-arg overload), the private host
transport (the two mount slots on `UI.Ast.Host`, the wire types, and the `UIServer` session loop),
and the invariants that govern it. Everything here is grounded in actual source under
`kyo-ui/shared/src/main/scala/kyo/`.

### The runner selector: client vs server-push

The same `UI` value supports two interactive runners:

- `UI.runMount` (JS/Wasm): mounts to the live DOM as a single-page app; the client owns signal
  subscriptions; host mounts fire via `DomBackend.fireHostMounts` exactly once.
- `UI.runHandlers` (shared): serves a page GET and a WebSocket route; the server owns signal
  subscriptions; host updates ride the single WS.

Choosing the runner is the only decision that determines client vs server-push. The `UI` tree and
its `Signal` wiring are identical in both cases.

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
and the rendered HTML is byte-identical to what the 1-arg form produced before the overload existed.

The `head` parameter is a `UI.PageHead`. Its `moduleScript` field (`Maybe[String]`, default
`Absent`) emits a `<script type="module" src="...">` in the served page when `Present`. A
server-push app that needs a Scala.js client island (the browser-side host mount reconciler) sets
this field to the island's bundle path:

```scala
val head = UI.PageHead("My App", moduleScript = Present("/_kyo/island.js"))
UI.runHandlers("/", head)(myUI)
```

The inline server-push client script (the WS reconnect loop and DOM patcher) is always present; the
`moduleScript` link is appended after it. The client island reads the per-host init data islands
(described below) on load and sets up host-specific state without a WS round-trip.

`UIServer.handlers` (`kyo-ui/shared/src/main/scala/kyo/internal/UIServer.scala`) is the sole
implementation; `UI.runHandlers` delegates to it directly.

### The two mount slots on `UI.Ast.Host`

`UI.Ast.Host` (defined in `kyo-ui/shared/src/main/scala/kyo/UI.scala`) carries two independent
mount slots:

```scala
final case class Host(
    attrs: Attrs = Attrs(),
    hostTag: String = "canvas",
    mount: Maybe[HostMount] = Absent,        // client: DomHostMount (JS/Wasm only)
    serverBridge: Maybe[HostMount] = Absent  // server-push: HostBridge (shared)
)(using val frame: Frame) extends HostNode
```

The two slots are disjoint by design:

- `mount` carries a `DomHostMount`; `DomBackend.fireHostMounts` reads it on the client to run the
  external renderer once in the live DOM. The server-push runner never reads `mount`.
- `serverBridge` carries a `UI.Ast.HostBridge`; `UIServer.serveSession` reads it on the server to
  fork signal subscriptions and route picks. The client runner (`fireHostMounts`) never reads
  `serverBridge`.

A host node for the server-push path supplies a `serverBridge` and typically also a `mount` (so
the same `UI` tree works under both runners). The two slots do not interact.

Both slots are written through `private[kyo]` setters (`withMount` / `withServerBridge`); external
modules (kyo-threejs) call these via the platform's `UI.host` factory, which lives in
`kyo-ui/jvm/src/main/scala/kyo/UIHost.scala` (and the JS/Native equivalents).

### The HostBridge contract

`UI.Ast.HostBridge` (`kyo-ui/shared/src/main/scala/kyo/UI.scala`) is a `private[kyo]` trait that
`serverBridge` slots carry. Its three methods form the server-push seam:

```scala
private[kyo] trait HostBridge extends HostMount:
    private[kyo] def serverInit(path: Seq[String]): HostPayload < Sync
    private[kyo] def subscriptions(
        path: Seq[String],
        emit: HostPayload => Unit < Async
    )(using Frame): Unit < (Async & Scope)
    private[kyo] def onPick(
        path: Seq[String],
        nodeId: String,
        pointer: PointerData
    )(using Frame): Unit < Async
```

- `serverInit` returns the initial `HostPayload` for the host at `path`. `UIServer.getPage` calls
  this once per host per page GET to build a per-host boot island (`<script
  type="application/json" data-kyo-host-init>`) embedded in the served HTML; the client island
  reads it to seed its initial scene without a WS round-trip.
- `subscriptions` registers server-side signal observers that call `emit(payload)` whenever a
  server-owned signal changes. The `UIServer.registerHosts` loop forks each host's subscriptions
  under the session `Scope` (so teardown interrupts the observers). `subscriptions` MUST return
  without parking; if it needs an observe loop, it forks it under the ambient `Scope` and returns.
- `onPick` handles an inbound `UIEvent.HostPick` from the client. `UIServer.registerHosts` forks
  each invocation on its own session-`Scope`-bound fiber so a long-running or parked pick does not
  block the WS message loop. `onPick` MUST NOT call back into any kyo-ui DOM or WS method; the
  seam is one-directional.

kyo-ui never calls any other method on the bridge. The test in
`kyo-ui/shared/src/test/scala/kyo/internal/UIServerHostBridgeTest.scala` asserts this contract
directly ("only subscriptions/onPick are invoked on the bridge").

### Wire types: no js.Dynamic, no closure on the wire

The host transport reuses kyo-ui's single existing WS codec (`Json.encode[HtmlOp]` /
`Json.decode[UIEvent]` in `UIServer`). No new channel or transport is introduced.

Server-to-client host updates ride `HtmlOp.HostUpdate` (`kyo-ui/shared/src/main/scala/kyo/internal/HtmlOp.scala`):

```scala
case class HostUpdate(path: Seq[String], payload: HostPayload) extends HtmlOp derives Schema
```

Client-to-server picks ride `UIEvent.HostPick` (`kyo-ui/shared/src/main/scala/kyo/internal/UIEvent.scala`):

```scala
case HostPick(path: Seq[String], nodeId: String, pointer: PointerData)
```

Both are addressed by `data-kyo-path`, the same scheme every `HtmlOp.Replace` / `HtmlOp.Remove`
uses. A `HostUpdate` is never a replace and never a remove: it carries a typed `HostPayload` the
client island interprets; kyo-ui's own DOM patcher ignores it.

`HostPayload` (`kyo-ui/shared/src/main/scala/kyo/internal/HostPayload.scala`) carries two leaf
kinds: `Prop` (a single targeted prop push: node id, slot name, typed `HostValue`) and `Structural`
(a keyed splice instruction for structural reactivity). Every leaf derives `Schema`; there is no
`js.Dynamic` and no closure on the wire. The codec is the same `Json.encode[HtmlOp]` /
`Json.decode[UIEvent]` path the rest of the server-push transport uses.

`PointerData` is the FFI-free wire form of a raycast hit (point in world space, camera distance,
normalized-device-coordinate cursor, all plain `Double`s). No three.js object crosses the wire.

### The serveSession session loop

`UIServer.serveSession` (`kyo-ui/shared/src/main/scala/kyo/internal/UIServer.scala`) runs one
full-duplex session under a `Scope.run`:

1. Evaluates `ui` to get the tree, normalizes it with `ReactiveUI.normalize`, subscribes reactive
   zones via `ReactiveUI.subscribe` (unchanged from before this campaign).
2. Calls `registerHosts(uiTree, ws)`: walks the tree for host nodes carrying a `HostBridge` via
   `ReactiveUI.hostBridges`, forks each host's `subscriptions` under the session `Scope`, and
   returns a pick-router closure. Each signal emission from a host's subscriptions calls
   `emitHostUpdate`, which encodes a `HtmlOp.HostUpdate` and puts it on the WS.
3. Races the inbound-message loop against `ws.onPeerClose`. For each inbound frame:
   - A `UIEvent.HostPick` is routed to the pick-router closure, which puts the pick on a bounded
     `Channel`; a consumer fiber drains the channel and forks each `onPick` call on its own
     session-`Scope`-bound fiber. A parked `onPick` never blocks the WS message loop.
   - Every other event is dispatched through the normal DOM event handler path (unchanged).
4. When the session ends (peer close or scope teardown), every forked fiber is interrupted.

The pick-router channel is unbounded (`Int.MaxValue`) so a burst of picks does not back-pressure
the WS loop; each pick then runs independently without head-of-line blocking.

### The init-island emission at page GET

`UIServer.getPage` calls `injectHostInit(uiTree, html)` after the initial render. For each host
carrying a `HostBridge`, `injectHostInit` calls `bridge.serverInit(path)`, encodes the returned
`HostPayload` as JSON, and calls `HtmlRenderer.injectHostIsland` to:

- Add a `data-kyo-host` marker attribute to the host element's opening tag.
- Nest a `<script type="application/json" data-kyo-host-init>` island inside the element (before
  its closing tag), carrying the encoded payload.

The client island scans `[data-kyo-host]` on load, reads each element's nested init island, and
seeds its local state without a WS round-trip. Hosts with no `HostBridge` (`serverBridge = Absent`)
are left untouched; the plain cross-platform `<canvas>` (or other tag) is emitted as before.

The JSON body is escaped via `HtmlRenderer.escScript` so a `</script>` substring in the payload
cannot close the element early.

### Invariants added by this section

These extend the invariants listed above and carry the same weight: a change that makes one false
is incomplete until the invariant and its test are restored.

- **Single transport.** Host updates and picks ride kyo-ui's single existing WS (`Json.encode[HtmlOp]`
  / `Json.decode[UIEvent]`). No bespoke channel, no second WS, no SSE. Enforced by
  `UIServerHostBridgeTest` ("one server-signal emission emits exactly one HostUpdate").
- **Wire carries no closure, no `js.Dynamic`.** Every value on the wire is a typed, `Schema`-derived
  Scala value. `HostPayload`, `PointerData`, and `HostUpdate`/`HostPick` carry no function and no
  JS-runtime type. Enforced by `UIServerHostBridgeTest` ("the decoded HostPick carries only typed
  plain fields") and the `derives Schema` constraints in the source.
- **One mount fires once and holds a persistent channel.** `fireHostMounts` on the client fires the
  `mount` closure exactly once per host, and the server-push `subscriptions` call is made exactly
  once per session, never re-registered on a re-render. The session's pick-router channel persists
  for the session lifetime. Enforced by `UIServerHostBridgeTest` ("only subscriptions/onPick are
  invoked on the bridge").
- **A parked pick does not stall the WS loop.** Each `onPick` invocation is forked on its own
  session-`Scope`-bound fiber via the pick-router channel. A long-running pick never blocks the WS
  message loop or a subsequent pick. Enforced by `UIServerHostBridgeTest` ("a second HostPick is
  routed while the first onPick is parked").
- **A host node is never the target of a Replace or Remove.** A sibling reactive re-render emits a
  `HtmlOp.Replace` for the reactive region's path; the host path is never that target. Enforced by
  `UIServerHostBridgeTest` ("a sibling re-render never targets the host path" and "no
  Replace/Remove over N sibling emissions targets the host").
- **`data-kyo-path` addressing is stable.** The path assigned to a host element at page GET is the
  same path `registerHosts` records and `injectHostInit` uses. Path stability across sibling
  re-renders follows from the existing addressing invariant ("Addressing is stable" above).

### Recipe: implementing a HostBridge

A module that needs server-push host reactivity (kyo-threejs is the reference implementation)
implements `UI.Ast.HostBridge` and supplies it via `host.withServerBridge(bridge)` (a `private[kyo]`
call). From outside `kyo-ui`, the bridge is set through the module's own public factory (e.g.,
`Three.embed`) which calls `withServerBridge` on the caller's behalf.

The three implementation obligations:

1. `serverInit(path)`: compute and return the initial `HostPayload` synchronously. This is called
   once per page GET; it must not park. A `Sync.defer` wrapper is sufficient.

2. `subscriptions(path, emit)`: register any server-side signal observers that call `emit(payload)`
   on change, then RETURN. If the observer loop is unbounded (a `Signal.observe` that never
   terminates), fork it under the ambient `Scope` and return immediately:

   ```scala
   private[kyo] def subscriptions(path, emit)(using Frame): Unit < (Async & Scope) =
       Fiber.init { mySignal.observe(value => emit(encode(value))) }.unit
   ```

   The ambient `Scope` is the session `Scope`; the fiber is interrupted on session teardown.

3. `onPick(path, nodeId, pointer)`: handle a client raycast pick. The call runs on its own forked
   fiber (already forked by `registerHosts`); a long-running or suspending body is safe. Do NOT
   call back into any kyo-ui WS or DOM method from this closure.

Tests for a new bridge live in `shared/src/test` (platform-neutral; `HttpWebSocket.connect` is
the in-process WS pair available on JVM and JS; mark WS leaves `.notNative`).

## Test patterns

Cross-platform tests for pure logic (style rules, AST shape, renderer output to HTML) live in
`shared/src/test` and run on JVM, JS, and Native. Browser tests that require a real DOM (the DOM
backend, the host mount seam, reactive update counts) live in `js-wasm/src/test` or, for tests
that need a real Chrome (a host mount fired in a live page, a GL context released), in the
downstream `kyo-threejs/js/src/test`. Follow the root file-naming rule: every test file shares a
name prefix with the source it covers.
