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

A `UI` is a **pure immutable value**. Every factory call (`UI.div`, `UI.button`, `UI.input`)
allocates a plain case class with no effect, so the result is a `val`: shareable,
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
- `js-wasm/src/main`: the JS + Wasm DOM backend (`DomBackend`, `DomStyleSheet`), the per-node
  rendering backend SPI (`Backend` in `internal/Backend.scala`), and the client entry point
  (`UIMount`, with `UILocation` / `UIWindow`). Compiles under both JS and Wasm. The client entry
  point is the `UI.runMount` extension on `UI.type`, not a top-level export. `Backend.mount` takes a
  `host: dom.Element`, a JS-only type, so the SPI trait lives here rather than in `shared`.

The shared source must not import `org.scalajs.dom`, `scala.scalajs.*`, or any JS-only type.
Platform-specific behavior belongs in the appropriate platform subtree.

## The backend SPI

`internal/Backend.scala` defines the per-node rendering backend seam. A foreign renderer
(kyo-threejs) is a sibling of the DOM backend rather than a bolt-on: it renders one node type into a
host element and delivers later changes through the same two wire ops the DOM path uses. The rules
below are binding for all code that touches `Backend`, `UI.Ast.BackendNode`, `DomBackend`, and the
client backend registry.

### The `Backend` trait

`Backend` (`private[kyo]`, in `js-wasm/src/main`) has four members:

- `key: String`: the registry key the client dispatches on (`"dom"`, `"three"`).
- `mount(node, host, path): Backend.Live < (Async & Scope)`: materializes `node` into the `host`
  element under the ambient page `Scope`, returning a Scope-managed teardown handle. It runs EXACTLY
  ONCE per backend node per lifetime; a signal-driven re-render never re-runs it.
- `patch(path, key, encoded): Unit < Async`: applies one path-addressed scalar prop patch on the
  live tree the backend owns.
- `replaceSubtree(path, encoded): Unit < Async`: re-materializes the structural reactive region at
  `path` from a Schema-encoded snapshot, reusing unchanged keys' live objects.

`Backend.Live` is the opaque teardown handle: closing the ambient page `Scope` releases every
backend renderer (a WebGL context, a frame loop, a path-to-live index). `Backend.register` /
`Backend.lookup` hold the client-side registry keyed by `key`. The DOM backend pre-registers at
first mount; an island registers its backend at init. The registry is a mutable single-owner map
(the page's main fiber registers before any mount dispatch reads it), never shared across fibers.

The trait lives in `js-wasm/src/main` because `mount` takes a `host: dom.Element`, a JS-only type,
and both implementations are JS/Wasm. The JS-free pieces (`UI.Ast.BackendNode`, the `HtmlOp` wire
ops) stay in `shared`, so kyo-ui keeps compiling on JVM and Native.

### `UI.Ast.BackendNode`

`UI.Ast.BackendNode` (in `UI.scala`) is the sanctioned non-sealed AST base a node rendered by a
foreign backend mixes in. It is public as a TYPE (so kyo-threejs's `Three.Ast.Node` extends it) but
carries `HtmlContent`, NOT `Inline`: mixing `Inline` would re-impose `Element.children: Chunk[UI]`,
which a scene node's `children: Chunk[Three]` cannot satisfy. Every member is `private[kyo]`: the SPI
the kyo-ui walk and the registered backends consume, never a user call. A backend node carries NO
public member, in particular no chainable `id` setter; a producer that wants one declares it on its
own return type, as `Three.embed` does with `Three.Embedded.id`.

The SPI members:

- `backend: String`: the registry key selecting the mounting backend.
- `placeholder: Placeholder(tag, attrs)`: the real SSR/DOM element the node emits (default
  `<canvas>`); only the root SSRs a tag, inner backend nodes never do. `attrs` carries id/class/style.
- `backendChildren: Chunk[UI]`: the addressable child nodes the reactive walk descends
  (index-addressed, `path :+ i`).
- `boundProps: Chunk[BoundProp]`: the node's scalar reactive props. Each `BoundProp(key, signal,
  encode)` carries a stable structural `key` (the navigate path, e.g. `"material.color"`), the
  type-erased bound `signal`, and an `encode` so the server serializes an emission with no knowledge
  of the value type.
- `structuralRegion: Maybe[StructuralBinding]` (default `Absent`): the node's own structural
  reactive region (a render/foreach whose signal produces the subtree as serializable DATA).
  `StructuralBinding(signal, encode)` is the server-side half; the client decode and re-render live
  on the backend node.
- `dispatchBackendEvent(relPath, encoded)` (default no-op): given a path relative to this node plus
  the backend's encoded event payload, resolves the addressed node's handler and runs it.

The `Signal[Any]` erasure in `BoundProp` and `StructuralBinding` is contained inside those case
classes, never a public `asInstanceOf`.

### The wire ops

`HtmlOp` (`internal/HtmlOp.scala`) is the server-to-client op codec. Beyond the DOM ops (`Replace`,
`Remove`, `InjectCss`), two are backend-generic and path-addressed:

- `SetProp(path, key, encoded)`: set the prop `key` of the live node at `path` to the Schema-encoded
  value `encoded`.
- `ReplaceSubtree(path, encoded)`: replace the subtree at `path` with the Schema-encoded snapshot (a
  `Chunk[A]` for a foreach feed).

Both carry `path: Seq[String]` and a typed `String` payload, never a `js.Dynamic` or a closure. The
inbound `UIEvent.BackendEvent(path, encoded)` is the client-to-server counterpart: the server routes
it by `path` to the addressed `BackendNode.dispatchBackendEvent`, which decodes the backend's own
opaque payload. The `HtmlOp` round-trips are guarded by `HtmlOpTest`.

### How normalize descends a backend node

`ReactiveUI.normalize`'s `BackendNode` arm is matched BEFORE the `Element` arm (a backend node is
not an `Element`, so it must be routed here rather than falling through to the Element arm's HTML
descent). It assigns the node's path and descends three things, emitting NO HTML:

- `backendChildren`, each normalized at `path :+ i`.
- each `boundProps` entry as a per-key reactive region at `path :+ key`, marked so the exchange emits
  a `SetProp`.
- the `structuralRegion` (0 or 1), observed at the node's OWN path, marked so the exchange emits a
  `ReplaceSubtree`.

The node's own carrier signal is `Signal.initConst`, so `subscribeScoped` observes only the prop and
structural signals (the ones that change), never re-walking this node. `ReactiveUI.Region` is the
discriminator the exchange reads: `DomRegion` (render HTML, a `Replace`), `PropRegion(path, key,
encode)` (a raw value, a `SetProp`), and `StructuralRegion(path, encode)` (a data snapshot, a
`ReplaceSubtree`).

### The mount walk

`DomBackend.mount` (the SPI mount) renders the whole un-keyed tree into `host`, then walks the AST
for backend nodes. For each `BackendNode` it resolves the SSR'd placeholder element by
`data-kyo-path` and dispatches `Backend.lookup(bn.backend).mount(bn, element, path)` onto it, exactly
once. A backend node inside a reactive (`Reactive`/`Foreach`) zone is NOT fired: it sits under a
signal whose subtree a re-render may replace, so only a node in a const subtree mounts. The walk
still descends the reactive region's current content and logs a warning when it finds a backend node
there, so the skipped mount is visible to the author, never silent.

`DomBackend.hydrateBackendNodes` is the server-driven variant: it walks the client-rebuilt tree
(built by the SAME builder the server called, so `data-kyo-path`/`data-kyo-backend` match the SSR
markup by construction) and dispatches each backend node's mount onto its existing placeholder
WITHOUT touching `container.innerHTML`. A server-driven page's own reactivity rides the inline WS
listener, so this walk only registers the live mount for the pushed ops to land on.

### The single-writer ownership split

`DomBackend.LocalExchange` is the client-local reactive exchange for a `UI.runMount` page. A
`DomRegion` renders UI to HTML and applies it to the DOM. A backend node's own reactivity, its bound
props AND its structure, is owned by that backend's own reconciler, which observes the same signals
locally; so the `PropRegion` and `StructuralRegion` arms are deliberate no-ops. Routing them to the
backend here would make `LocalExchange` a SECOND writer of the live state the reconciler already
patches, breaking dispose-once and keyed reuse. The client owns prop and structural reactivity in a
`runMount` page; the server exchange (`UIServer.wsExchange`) is the one that routes prop and
structural regions over the wire.

### The `data-kyo-path` addressing scheme

`HtmlRenderer.renderTo` assigns `data-kyo-path="..."` to every element as it walks the AST. The root
element (rendered with `Seq.empty`) gets `data-kyo-path=""` (the empty string). Its children get
`"0"`, `"1"`, ...; grandchildren get `"0.0"`, `"0.1"`, etc. The path is `path.mkString(".")` over the
index sequence at each level. A backend node's placeholder carries both `data-kyo-path` and
`data-kyo-backend="<key>"`, and the HTML descent STOPS at the placeholder (no child HTML for the
backend's own subtree; a scene root SSRs `<canvas data-kyo-path="1" data-kyo-backend="three">`). Path
stability across sibling re-renders is what lets the mount walk resolve a placeholder after the
initial render.

## Invariants

These are the load-bearing properties of the module. A change that makes one of these false is
incomplete until the invariant and its test are restored.

- **Fine-grained updates, no full re-render.** A signal emission patches only the subscribed subtree;
  a backend node adjacent to a reactive subtree is never replaced or re-created. Guarded by
  `ThreeEmbedBrowserTest` ("the embed canvas element identity is preserved across sibling reactive
  re-renders").
- **One mount per backend node, per lifetime.** `Backend.mount` runs exactly once per backend node,
  at mount; a signal-driven re-render never re-runs it. Guarded by `ThreeEmbedBrowserTest` ("the
  embed mount callback fires exactly once").
- **Backend teardown tied to page scope.** A backend's live resources bind to the ambient page
  `Scope` through its `Backend.Live` handle; closing that scope releases every backend renderer.
  Guarded by `ThreeEmbedBrowserTest` ("the embedded WebGL context is released when the containing
  Scope closes").
- **Single writer of client-local live state.** In a `UI.runMount` page the backend's own reconciler
  is the single writer of its bound props and structure; `LocalExchange`'s `PropRegion` /
  `StructuralRegion` arms no-op so that live state is never written twice. The server exchange, not
  the client one, routes prop and structural regions over the wire.
- **Addressing is stable.** `data-kyo-path` values are stable across signal-driven re-renders of
  sibling subtrees; a backend node's path does not change because a neighboring reactive node
  re-rendered. This is what lets the mount walk resolve the placeholder element after the initial
  render.

## Server-push backend transport

The same `UI` value runs as a client single-page app or as a server-push page, and a backend node (a
3D canvas, a chart, a map) works under both. On a client `UI.runMount` page the backend owns its
reactivity locally; on a server-push page the server owns the signal subscriptions and pushes each
change as a wire op over kyo-ui's single existing WebSocket. This section documents the server-push
half: the runner selector, the `UI.runHandlers` 2-arg overload and `PageHead.moduleScript`, the
exchange that turns a region emission into a `SetProp`/`ReplaceSubtree`, and the client registry seam
that routes an inbound op to the owning backend.

### The runner selector: client vs server-push

The `UI` tree and its `Signal` wiring are identical for both runners; the runner is the only choice
that determines client vs server-push:

- `UI.runMount` (JS/Wasm): mounts to the live DOM as a single-page app. The client owns signal
  subscriptions; a backend node mounts once via `DomBackend.mount`, and its bound props and structure
  are driven locally by the backend's reconciler.
- `UI.runHandlers` (shared): serves a page GET and a WebSocket route. The server owns signal
  subscriptions; DOM regions ride the WS as `HtmlOp.Replace`/`Remove`, and a backend node's bound
  props and structure ride it as `HtmlOp.SetProp`/`ReplaceSubtree`.

### The public overload and the PageHead.moduleScript field

`UI.runHandlers` has two overloads (both in `UI.scala`):

```scala
// 1-arg form: delegates to the 2-arg form with PageHead("kyo-ui")
def runHandlers(basePath: String)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync

// 2-arg form: the one implementation; accepts a configurable PageHead
def runHandlers(basePath: String, head: UI.PageHead)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync
```

The 1-arg form delegates unconditionally to the 2-arg form with the default `PageHead("kyo-ui")`:
one implementation, no duplicated logic, and the 1-arg form's rendered HTML is byte-identical to the
2-arg form's with the default head.

The `head` parameter is a `UI.PageHead`. Its `moduleScript` field (`Maybe[String]`, default `Absent`)
emits a `<script type="module" src="...">` in the served page when `Present`. A server-push app that
embeds a backend node (a `Three.embed` canvas, say) sets this field to the client island's bundle
path; the island hydrates the backend node onto its SSR'd placeholder so the pushed ops have a live
target:

```scala
val head = UI.PageHead("My App", moduleScript = Present("/_kyo/island.js"))
UI.runHandlers("/", head)(myUI)
```

The inline server-push client script (the WS reconnect loop and DOM patcher) is always present; the
`moduleScript` link is appended after it.

`PageHead.importMap` (`Seq[(String, String)]`, default empty) is the no-bundler companion to
`moduleScript`: when the island is a plain `fastLinkJS`/`fullLinkJS` ESModule that imports bare npm
specifiers (such as `three`), each `(specifier, url)` entry maps one to a served module URL. The
renderer emits a single `<script type="importmap">{"imports": {...}}</script>` in the head before the
`moduleScript`, so the browser resolves the bare imports without a pre-bundling step. Empty emits no
import map. Guarded by `UIRunHandlersTest` ("renderPage appends a module script only when moduleScript
is Present", "the locked 2-arg runHandlers signature type-checks at its declared type").

### The server exchange: region emission to wire op

`UIServer.serveSession` (`internal/UIServer.scala`) runs one full-duplex session under a `Scope.run`:
it evaluates `ui` once, normalizes it with `ReactiveUI.normalize`, subscribes the reactive regions
with `ReactiveUI.subscribe` against `wsExchange`, and races the inbound-message loop against
`ws.onPeerClose`. When the session ends, `Scope.run` interrupts every subscribed fiber; no fiber
outlives the session.

`wsExchange.onChange` maps each region emission to one wire op and puts it on the WS:

- a `DomRegion` renders the UI subtree and pushes a `Replace`.
- a `PropRegion` encodes the raw value and pushes a `SetProp(path, key, encoded)`.
- a `StructuralRegion` encodes the raw structural data (a `Chunk[A]` for foreach, an `A` for render)
  and pushes a `ReplaceSubtree(path, encoded)`.

The inbound loop decodes each frame as a `UIEvent`; a `UIEvent.BackendEvent(path, encoded)` is routed
by `path` to the addressed `BackendNode.dispatchBackendEvent`, so a client-side raycast resolves its
`onClick` server-side. A malformed inbound frame is dropped (a buggy client must not tear the session
down); a decoder `Panic` propagates.

### The client registry seam and the startup buffer

The inline client script (`HtmlRenderer.clientJs`) routes each inbound backend op to the owning
backend. A `SetProp`/`ReplaceSubtree` addresses a `path`; `backendRootPath` shrinks that path one
segment at a time (longest prefix first) to the nearest element carrying `data-kyo-backend`, the
backend ROOT that owns it. Shrinking the path (not walking the DOM) reaches the root even for a prop
nested several AST levels below the placeholder, because the backend's HTML descent stops at its own
placeholder and no element carries a deeper `data-kyo-path`. For a `SetProp` the client drops the
trailing key segment (`p.slice(0, p.length - 1)`) so the node's own path reaches the backend's
`patch`.

`window.__kyoBackends[root]` holds each backend's `{patch, replaceSubtree}` handle, installed by the
island through `__kyoBackendsRegister(root, handle)`, which runs only after the island module loads,
hydrates, and mounts. The server pushes each bound prop's and each structural region's CURRENT value
at subscribe time (observe fires immediately), so an op can arrive in the window between WS-open and
island registration. Such an op is buffered per root in `window.__kyoBackendsPending` (bounded; on
overflow the OLDEST is evicted, so the newest snapshot is never dropped) and flushed in arrival order
the moment the root registers. `__kyoBackendsRegister` installs the handle and drains that root's
buffer. A backend that tears down unregisters its handle and drops any never-flushed buffer.

The client-to-server counterpart is `window.__kyoPostBackendEvent(path, encoded)`, which posts a
`UIEvent.BackendEvent` over the same WS; the backend island calls it with its own opaque payload (a
raycast pointer for three), decoded server-side by the owning backend node.

### Invariants for the server-push transport

These extend the invariants above and carry the same weight.

- **Single transport, end to end.** Backend prop and structural pushes and the inbound backend events
  ride kyo-ui's single existing WS (`Json.encode[HtmlOp]` / `Json.decode[UIEvent]`); no bespoke
  channel, no second WS. Proven end to end by
  `kyo-threejs/js/src/test/scala/kyo/ThreeBackendBridgeBrowserTest.scala` ("the server-pushed DOM
  label and the embedded cube's material.color both reach the client over the one /_kyo/ws socket,
  with no re-mount").
- **The wire carries no closure and no `js.Dynamic`.** Every `HtmlOp.SetProp`/`ReplaceSubtree` and
  every inbound `UIEvent.BackendEvent` carries its payload as an opaque `Json.encode`d string; a
  closure or a JS-runtime value never crosses. Guarded by `HtmlOpTest` (the `SetProp` and
  `ReplaceSubtree` Schema round-trips).
- **A pre-registration push is buffered, not dropped.** A `SetProp`/`ReplaceSubtree` for a backend
  root that arrives before the island registers is buffered (bounded, oldest-evicted) and flushed in
  order on registration, so the client converges. Proven by `ThreeBackendBridgeBrowserTest` ("a
  server SetProp that arrives BEFORE the island registers is buffered and flushed on registration, so
  the client converges").
- **Server-side structural reactivity and event resolution.** A server-driven keyed splice or reorder
  reaches the client as one `ReplaceSubtree` apiece (dispose-once, keyed reuse), and a client-side
  raycast on server-driven content resolves its `onClick` server-side over the same socket. Proven by
  `kyo-threejs/js/src/test/scala/kyo/ThreeStructuralBridgeBrowserTest.scala`.
- **Session-scoped teardown.** The reactive subscription and every fiber it forks bind to the session
  `Scope` of `serveSession`'s `Scope.run`; a disconnect interrupts every one, so no fiber outlives the
  session.

## Test patterns

Cross-platform tests for pure logic (style rules, AST shape, renderer output to HTML, wire-op
round-trips) live in `shared/src/test` and run on JVM, JS, and Native. Browser tests that require a
real DOM (the DOM backend, the backend mount seam, reactive update counts) live in `js-wasm/src/test`
or, for tests that need a real Chrome (a backend node mounted in a live page, a GL context released),
in the downstream `kyo-threejs/js/src/test`. Follow the root file-naming rule: every test file shares
a name prefix with the source it covers.
