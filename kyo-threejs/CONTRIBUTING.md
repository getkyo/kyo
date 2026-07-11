# Contributing to kyo-threejs

This is the module-specific contributor guide for kyo-threejs. It carries only what is
particular to this module. For every general Kyo convention (effect rows, naming, `using`
ordering, `inline`, Kyo types over stdlib, the safe-by-default tier, test base classes, the
"fix the code not the test" and "reproduce before you fix" rules), the
[root CONTRIBUTING.md](../CONTRIBUTING.md) is the authority. When this file and the root differ
on a generic rule, the root wins; this file never restates a root rule, it points at it.

kyo-threejs is the declarative 3D layer for Kyo: the r3f / kyo-ui shape (a pure-value AST plus
`Signal` reactivity plus a reconciler) retargeted from an HTML tree to a three.js scene graph.
If you have worked in kyo-ui, the model transfers almost directly; the differences are the
GL-resource lifecycle, the FFI-heavy reconciler, the client/server reactivity bridge, and the
js+wasm-only platform.

## Core model

A `Three` is a **pure, immutable scene-graph value**. Calling `Three.scene`, `Three.mesh`,
`Three.Geometry.box`, `Three.Material.standard`, `Three.Light.ambient`, or `Three.Camera.perspective`
allocates plain immutable case classes and runs no effect. A `Three` value is shareable,
re-renderable, and testable on Node without a GPU. This is the kyo-ui pure-factory contract: the
AST factories carry only `(using Frame)` and return a pure value, never an effect row. Keep them
that way. A factory that needs to run an effect is a design error at that layer; the effect work
belongs at mount.

Every bindable prop (transform, material color/opacity/metalness, light intensity, camera
position/lookAt) is a `Bound[A]`:

- `Bound.Const(value)` is a static prop.
- `Bound.Ref(signal)` is the **reactivity boundary**. It binds a `Signal[A]` to one targeted FFI
  setter on one live object. A `Bound.Ref` emission never rebuilds the scene; it calls a single
  property setter (a position emission calls `obj.position.set(x, y, z)` and touches nothing else).

The work happens at mount. The **reconciler** (`kyo.internal.Reconciler`) walks the AST once and
*materializes* it: for each node it constructs the matching three.js object through the
`@JSImport("three")` facade, registers that object for disposal, and records the binding in an
explicit map keyed by AST-node reference identity. That map is **1:1**: each materialized node
maps to exactly one live `Object3D`. Targeted mutations (a `Bound.Ref` emission, a `onFrame`
write, a raycast hit) resolve through this map; they never re-render a subtree.

Two kinds of reactivity, kept distinct:

- **Prop-level** (`Bound.Ref`): one signal drives one setter on one live object. Wired by
  `ThreeMount.subscribeRegions`, which forks one observe fiber per `Bound.Ref` prop.
- **Structural** (`Three.reactive` / `render` / `when`, and `Three.foreach` / `foreachKeyed`):
  a `Signal[Three]` or `Signal[Chunk[A]]` populates and re-diffs a holder region. `foreachKeyed`
  reconciles **by key** so a reorder or insertion reuses the matching live nodes (the GPU buffers
  survive) instead of disposing and recreating them. Each structural element materializes under
  its **own per-element dispose scope**, so removing a key or swapping a `Reactive` subtree
  disposes exactly that element's GL resources, exactly once.

The mount, render, and loader entry points are top-level extensions on `Three.type` in `package kyo` (`runMount`, `testDriver`, `loadGltf`, `texture`, `toImage`, `embed`). A plain `import kyo.*` brings them into scope alongside kyo-ui's `UI.runMount`; there is no collision because the receiver types differ (`Three.type` vs `UI.type`).

`Three.embed(scene, camera)` is the kyo-ui bridge: it returns a `Three.Embedded` (a
`UI.Ast.BackendNode`, so it is a valid `UI` child), placing the 3D canvas as a first-class child
inside a kyo-ui tree alongside ordinary kyo-ui elements. `.id(v)` on the result tags the SSR'd
`<canvas>`. `Three.embed` builds a private `Three.Ast.Embed` node bundling the scene, camera, and
frames, carrying `backend = "three"`; kyo-ui's mount walk resolves the placeholder `<canvas>` and
dispatches `Backend.lookup("three").mount` onto it once the element is in the DOM, which reconciles
the scene, acquires the `WebGLRenderer`, records the camera, starts the frame loop, and wires pointer
delegation, the same sequence `Three.runMount` runs. The 3D pipeline runs under the ambient `Scope`
of the surrounding `UI.runMount` call; closing that scope disposes the renderer. The node carries no
mount-closure member: dispatch is by registry key, so `Three.Ast.Node` is a first-class backend node
the kyo-ui reactive walk descends natively.

When adding behavior to the embed path, keep it thin: `ThreeBackend.mount` delegates to the same
`ThreeMount.hostMountPipeline` and resource acquisition `runMount` uses. Do not add per-embed state
or ad-hoc logic to the backend; if a new capability is needed, add it to the shared `ThreeMount`
internal layer first so both `runMount` and `embed` benefit.

A reviewer should be able to state the headline invariant in one line: **a `Three` is a pure
immutable value; the reconciler holds a 1:1 node-identity to live-object map and applies targeted
mutations, never a scene rebuild.**

## Client-owned scene, server-driven reactivity

kyo-threejs runs **3D always on the client** (WebGL is browser-only; there is no server-side GL). The
client owns, builds, and animates the real scene. `Three.Ast.Node` is a first-class
`UI.Ast.BackendNode`, so a scene embedded with `Three.embed` participates in the kyo-ui Backend SPI:
on a `UI.runMount` page the client drives its own reactivity locally, and on a `UI.runHandlers`
server-push page the server owns the signal subscriptions and drives the client over kyo-ui's single
existing WebSocket through two path-addressed wire ops. `ThreeBackend` (`internal/ThreeBackend.scala`)
is the client `Backend` registered under the `"three"` key.

### The client owns the scene

`Three.runMount` mounts the real closure-carrying scene in the browser: it materializes the AST,
forks the `Bound.Ref` prop fibers (`subscribeRegions`) and the structural reactive watchers
(`subscribeReactiveRegions`), wires pointer delegation (`setupPointerDelegation`), binds any orbit
`Three.controls` (`setupControls`), and runs the frame loop (`runLoop`). Every behavior closure runs
client-side:
- `onFrame` runs at `requestAnimationFrame` (the `rafLoop`): each tick runs every `onFrame` closure
  inline, then submits one render.
- `onClick` / `onPointerOver` / `onPointerOut` run from a **local** raycast: the capture-phase
  `pointerdown` / `pointermove` listeners (`setupPointerDelegation`) map the event to NDC and call
  `Raycasting.hit`, then dispatch the hit node's own closure. On a client-local page no raycast
  crosses the wire.
- `Three.foreach` / `foreachKeyed` reconcile **client-side, by key**: a `Signal[Chunk[A]]` change
  diffs the holder region locally (`subscribeReactiveRegions`), reusing the live object for an
  unchanged key so the GPU buffers survive.

`Three.embed` places the same client pipeline inside a kyo-ui tree: `ThreeBackend.mount` runs the
identical materialize / subscribe / pointer / controls / loop sequence (`ThreeMount.hostMountPipeline`)
under the page mount `Scope`.

### ThreeBackend: mount, patch, replaceSubtree

`ThreeBackend extends Backend` (key `"three"`) and self-registers into the client registry on first
touch: every `Three.embed` constructs a `Three.Ast.Embed`, whose constructor touches the object so
registration precedes any later dispatch. Its three SPI members:
- `mount(node, host, path)`: for a `Three.Ast.Embed` it runs `hostMountPipeline`, builds the
  bidirectional `data-kyo-path` <-> `Reconciler.Live` index (`buildPathIndex`), records the render
  camera at `path :+ "1"` (matching `Embed.backendChildren(1)`), starts the drain fiber, and registers
  the mount plus a JS-callable `{patch, replaceSubtree}` handle, all under the page `Scope` (teardown
  unregisters both). A bare `Three` node that reached the seam (placed directly in a UI tree instead
  of via `Three.embed`) is contained: `Log.error` plus an inert `Live`, so the rest of the page still
  mounts rather than a panic aborting the whole page mount.
- `patch(path, key, encoded)`: resolves the live object at `path` and applies exactly one FFI setter
  via `applyByKey`, decoding `encoded` by `key`; no re-materialize. A decode failure skips (keeps the
  last-good value rather than snapping to a default); an unknown key is a forward-compatible no-op.
- `replaceSubtree(path, encoded)`: buffers the snapshot into a per-path latest-snapshot slot and wakes
  the drain fiber.

Each `replaceSubtree` op is a COMPLETE subtree snapshot, so the pending buffer is a per-path slot (a
`LinkedHashMap` keyed by path), not a queue: overwriting the slot coalesces to the newest (the newest
is never dropped, unlike a bounded queue's drop-on-full), and the drain applies each path's latest
exactly once, in first-enqueued order. A capacity-1 `wakeup` channel schedules the drain. `applyReplace`
decodes a `Foreach`'s keyed snapshot (`decodeKeyed` -> `Reconciler.foreachReplace`) or a `Reactive`'s
subtree (`decodeSubtree` -> `Reconciler.reactiveReplace`) and runs the client's own keyed reconcile,
reusing unchanged keys' live objects.

### boundProps to SetProp, structural region to ReplaceSubtree

The two backend wire ops carry the two kinds of reactivity:
- **Prop-level.** `Three.Ast.Node.boundProps` (`Ast.boundPropsOf`) walks each node's `Bound.Ref`
  props over a closed 12-key vocabulary (`position` / `rotation` / `scale`, `material.color` /
  `material.opacity` / `material.metalness` / `material.roughness` / `material.emissive`, `color` /
  `groundColor` / `intensity`, `lookAt`), yielding `(key, signal, encode)` triples. The kyo-ui walk
  turns each into a `SetProp(path :+ key, key, encoded)`, and the client's `ThreeBackend.patch`
  applies it by `applyByKey`. `encode` Json-serializes each raw value under its `Schema`, so the wire
  carries only encoded data.
- **Structural.** `Three.Ast.Reactive` and `Three.Ast.Foreach` override `structuralRegion` with a
  `StructuralBinding` (the Schema-encoded data signal plus its encoder). The kyo-ui walk observes it at
  the node's own path and emits a `ReplaceSubtree(path, encoded)`, which the client's
  `ThreeBackend.replaceSubtree` decodes and reconciles by key. `render` / `foreach` / `foreachKeyed`
  capture `Schema[A]` so their region is wire-drivable; `Three.reactive(Signal[Three])` is the
  Schema-free client-local escape (no server drive).

### Server-side onClick over BackendEvent

On a server-push page the client still raycasts locally, but resolves the hit SERVER-side.
`setupPointerDelegation` detects the mode by the inline client's post seam: when
`window.__kyoPostBackendEvent` is present (a server-driven page), a hit posts
`BackendEvent(path, PointerWire.encode(pointer))` over the page's single WebSocket, where `path` is the
hit object's `data-kyo-path` resolved through the mount's path-to-live inverse. The server routes the
`UIEvent.BackendEvent` by `path` to the owning node's `dispatchBackendEvent`, which decodes the
`Pointer` and walks the node's AST down the relative path (`resolveOnClick`) to run the addressed
`onClick`: a pure server-side read of the same authoritative signals the structural region drives, no
live scene and no FFI on the server. On a client-local `runMount` page (no post seam) the hit runs its
`onClick` closure locally.

### The camera

The render camera is created by `ThreeFacadeOps.makeCamera` and recorded into the mount's live map by
`ThreeMount.recordCamera` (keyed by the camera's AST-node identity). That recording is what makes a
signal-bound camera reactive: `subscribeRegions` (live) and `fillBoundRefsOnce` (headless `toImage`)
discover its `.lookAt(Signal)` / `.position(Signal)` `Bound.Ref` and re-aim the live camera on each
emission. For the server-driven path, `Embed.backendChildren` is `Chunk(scene, camera)`, so
`ReactiveUI.normalize` discovers the camera's boundProps and `ThreeBackend.buildPathIndex` indexes the
live camera at `path :+ "1"`, the path a camera `SetProp` addresses.

## Invariants (binding)

These are the load-bearing properties of the module. Each is paired with the test that guards it.
A change that makes one of these false is incomplete until the invariant and its test are restored.
Do not relax an invariant to make a change land; if a change genuinely requires changing an
invariant, that is a design decision to surface, not a quiet edit.

- **The 1:1 live map.** The reconciler maps each materialized AST-node identity to exactly
  one live `Object3D`; no node maps to zero or two. The map is keyed by reference identity (an
  `IdentityKey` wrapper using `eq` + `System.identityHashCode`, since `java.util.IdentityHashMap`
  is absent on Scala.js), so two structurally-equal sibling nodes are two distinct live entries,
  never collapsed. Guarded by `ReconcilerTest`.

- **Every GL resource disposed exactly once.** Every GPU resource (geometry, material,
  texture, renderer, render target) is created through `Scope.acquireRelease(create)(_.dispose())`,
  so scope close disposes it once and nothing leaks. The split is sharp: `acquireGl` registers
  GPU-buffer resources for disposal; `acquirePlain` constructs holder `Object3D`s (a `Group`, a
  scene node) that own no GPU buffer and need no `dispose`. Removed keyed/reactive children dispose
  through their **per-element scope**, which closes on removal (and a second close is a no-op:
  never a double-dispose). A surviving element's per-element scope is registered with the mount
  scope and closes on mount teardown. Never construct a GL resource outside a Scope-registered
  acquire. Guarded by `ThreeMountTest` ("foreachKeyed shrink disposes removed child geometry and
  material exactly once", "Reactive swap disposes the prior subtree's GL resources exactly once") and
  `ReconcilerTest` ("each GL resource dispose fires once and only once per scope"); the
  renderer-disposal half is browser-required (`ThreeMountBrowserTest`, `ThreeEmbedBrowserTest`; see
  the real-GL gate below). The client keyed reconciler holds this line for structural reactivity: a
  dropped key disposes its per-element scope exactly once and an unchanged key keeps its live object
  undisturbed. Guarded by `ThreeMountTest` ("foreachKeyed shrink: unchanged keys keep the same Live
  instance and are not disposed").

- **Frame-loop ordering: closures before submit.** Per tick the loop runs every per-object
  `onFrame` closure **inline and awaited**, then calls `renderer.render` exactly once. The submit
  never precedes its closures, so the rendered frame reflects this tick's mutations; a tick never
  double-submits and never skips. This ordering is easy to break by making the submit concurrent
  with the closures: keep `runFrame` as
  `foreachDiscard(onFrameClosures)(...).andThen(submit)`. The inner submit stays a tight FFI call
  (no fresh `Sync.defer` / `Async` per tick); coordination effects are forked once at loop
  start, not re-allocated per frame. Guarded by `ThreeMountTest` via `ThreeFrames.Manual` (no sleep).

- **`toImage` applies current `Bound.Ref` values before rendering.** A headless single-frame
  capture has no live loop, so before it renders it fills every structural reactive region and
  every prop-level `Bound.Ref` from its signal's *current* value
  (`Reconciler.fillReactiveRegionsOnce` + `ThreeMount.fillBoundRefsOnce`). A captured frame shows
  each reactive prop at its current value, not its materialize seed. Any new reactive prop kind must
  be reachable from these two one-shot fills, or it will render stale in `toImage`.

- **The real-GL gate.** Live WebGL runs only in a real browser. A `WebGLRenderer` submit
  cannot run on Node; it is exercised by the in-browser WebGL tests (`WebGLSceneHarness` subclasses)
  against real software-WebGL Chrome. Any new surface that submits to a live GL context belongs behind
  this gate (a js-only test under `kyo-threejs/js/src/test`, against the kyo-browser path), never as
  a Node test that fakes the submit.

- **`toImage` carries no `Browser` effect.** `Three.toImage` honors exactly the row
  `Image < (Async & Scope & Abort[ThreeException])` and produces its `Image` via the public
  `Image.fromBinary`. The row must not carry `Browser`. This is a compile-level property: widening
  the row to summon `Browser` is a regression even if it type-checks against a `Browser` in scope.
  Guarded by `ThreeToImageTest`.

- **Camera: `lookAt` after `position`.** A camera's `position.set` must run before its `lookAt`, so
  three.js computes orientation from the correct world position toward the target. This holds for
  perspective and orthographic cameras and for the reactive case: a `Bound.Ref` `lookAt` re-aims
  the camera after every position update. Both calls live in one thunk in `ThreeFacadeOps`; keep
  the ordering when touching camera materialization.

- **A prop patch never re-materializes.** A `SetProp` arriving over the WS resolves the one live
  object at its path and applies exactly one FFI setter via `applyByKey` (`patchProp` on the
  client-local path); the live-map count is unchanged, never a scene rebuild. Guarded by
  `ThreeReactiveTest` ("no full rebuild: applying a signal patch does not replace the scene root live
  object", "signal patch on one mesh leaves sibling live object identity unchanged") and
  `ThreeMountTest` (reactive camera and light position patches); proven end to end over a real
  WebSocket by `ThreeBackendBridgeBrowserTest`.

- **`replaceSubtree` keeps the latest snapshot per path.** Each op is a complete subtree snapshot, so
  under backpressure the per-path slot coalesces to the newest (the newest is never dropped) and the
  drain applies each path's latest exactly once, in first-enqueued order. Guarded by `ThreeBackendTest`
  ("replaceSubtree coalesces a flood to the LATEST snapshot per path (never dropping the newest) and
  applies the final pushed state", "replaceSubtree keeps distinct paths' latest snapshots independently,
  in first-enqueued order").

- **The wire carries no closures and no `js.Dynamic`.** `HtmlOp.SetProp` / `ReplaceSubtree` and the
  inbound `UIEvent.BackendEvent` each carry a string key/path plus a `Json.encode`d string of the
  Schema-serialized value. No function and no three.js object crosses: the client owns the scene, so
  `onClick` / `onFrame` closures and `Custom` / `Reactive` / `Foreach` nodes never serialize. Guarded
  by `HtmlOpTest` (the `SetProp` and `ReplaceSubtree` Schema round-trips) and the backend-dispatch
  checks in `ThreeBackendTest` / `ThreeBackendEventTest`.

- **The 12-key bound-prop vocabulary stays consistent across its three copies.** The same keys are
  enumerated by `boundPropsOf` (server discovery), `applyByKey` (client apply), and `extractBoundRefs`
  (client subscribe); a key that encodes on one side and is unhandled on another would be a silent
  no-op. Guarded by `ThreeBackendTest` ("the 12-key bound-prop vocabulary is consistent across
  boundPropsOf (server), applyByKey (client apply), and extractBoundRefs (client subscribe)").

- **Server-side onClick resolves against the authoritative signals.** A client raycast on a
  server-driven scene posts a `BackendEvent` whose `path` the server resolves through
  `dispatchBackendEvent` / `resolveOnClick`; a foreach child is re-read fresh at dispatch, a keyed
  child is addressed by key, and a malformed payload is a silent no-op. Guarded by
  `ThreeBackendEventTest`; proven end to end over the WS by `ThreeStructuralBridgeBrowserTest`.

- **Camera reactivity.** `ThreeMount.recordCamera` records the render camera in the mount's live map,
  so a signal-bound `lookAt` / `position` re-aims the live camera on each emission, and the server walk
  indexes it at the camera `SetProp` path. Guarded by `ThreeReactiveTest` ("recordCamera makes the
  render camera's lookAt(Signal) discoverable in boundRefs, and applying its patch re-aims the live
  camera") and `ThreeBackendTest` ("the render camera is Embed.backendChildren(1), so the server walk
  discovers its lookAt boundProp at the camera's own path (the camera SetProp wire path)").

- **A bare Three node in a UI tree is contained, not fatal.** A `Three.Ast.Node` placed directly in a
  UI tree (not via `Three.embed`) reaches the mount seam as a non-Embed node; the seam logs and returns
  an inert `Live` rather than panicking, so the rest of the page still mounts. Guarded by
  `ThreeBackendTest` ("ThreeBackend.mount contains a bare (non-Embed) Three node: it logs and returns
  an inert Live instead of panicking, so the page mount is not aborted").

- **Structural reorder preserves live-object identity.** A reorder of a `foreachKeyed` field reuses
  the live `Object3D` and its GPU buffers for an unchanged key; the keyed diff runs in the client's own
  reconciler off the decoded `ReplaceSubtree` snapshot (server-driven) or the local `Signal[Chunk[A]]`
  (client-local). Guarded by `ReconcilerTest` ("keyed diff reuses live nodes on reorder by reference
  identity") and `ThreeMountTest` ("foreachKeyed shrink: unchanged keys keep the same Live instance
  and are not disposed"); proven end to end by `ThreeStructuralBridgeBrowserTest`.

The remaining invariants each have a named guard test under `shared/src/test`: targeted mutation
(a `Bound.Ref` patches exactly one live object, never a scene rebuild), the one facade that links
on both backends, raycast resolving against the materialized graph, `Color`/`Normal` clamping with
a `Maybe` return, typed `loadGltf` failure, keyed reuse, the `position(v)` to
`position(Bound.Const(v))` forwarding pair, and the no-per-tick-allocation hot path. Keep each
guard test green when you touch the behavior it names.

## The unsafe boundary

kyo-threejs is FFI-heavy: it constructs and mutates three.js objects, queries the DOM, and submits
to WebGL, all through `js.Dynamic`. The discipline (the root's "Safe by default" rule, applied
here) is:

- **Stay in the safe tier by default.** The entire pure AST, every value type, the props, the
  signal plumbing: no `AllowUnsafe`, no `Sync.Unsafe`.
- **Reach for `Sync.Unsafe` / `AllowUnsafe` only at genuine bridging boundaries**, and there are
  exactly four kinds here:
  1. **FFI construction and mutation** of three.js / DOM / WebGL objects (the facade `new`, a
     property setter, `renderer.render`, `canvas.querySelector`, `getBoundingClientRect`).
  2. **JS-callback to effect bridges** (a `pointerdown` / `pointermove` listener or a
     `requestAnimationFrame` callback that must run or complete a Kyo effect synchronously, via
     `Sync.Unsafe.evalOrThrow` or `Promise.unsafe.complete*`).
  3. **The `@JSExportTopLevel` demo entry points** that the browser page calls directly.
  4. **The backend JS-handle wrappers and the backend-event post.** `ThreeBackend.registerJsHandle`
     installs a `{patch, replaceSubtree}` handle on `window.__kyoBackends`; each wrapper starts the
     underlying effect as a detached fiber, because a synchronous JS callback cannot await an `Async`
     effect. The pointer-delegation seam posts a `UIEvent.BackendEvent` over the page WebSocket through
     `window.__kyoPostBackendEvent`. These are the JS-callback-to-effect bridges for the server-driven
     path.
- **Mark each such site with a `// Unsafe:` comment stating why** (what is being bridged and why it
  is safe here). The unsafe tier mirrors the safe tier: every safe operation has its `Unsafe`
  equivalent, bridged through `Sync.Unsafe.defer`.
- **Do not put `// Unsafe:` on pure code, no-ops, or plain in-process side effects.** The marker
  means "this crosses the FFI / unsafe boundary"; a `var` accumulator on the single-owner mount
  fiber, a pure AST copy, or a `Sync.defer(())` seam is not an unsafe boundary and must not carry
  the marker. Over-marking is as much a defect as under-marking: it dilutes the signal the marker
  exists to carry.

`js.Dynamic` is confined to the internal reconciler and facade. It reaches the public surface only
through the sanctioned typed escape hatches (`Three.custom`, `Geometry.custom`, `Material.custom`,
each a typed `In => js.Dynamic` builder); their bridge in the reconciler is the one place a public
`js.Dynamic` is legitimate, and it carries an `// Unsafe:` rationale.

## Platform: js + wasm only

kyo-threejs is the repo's only `crossProject(JSPlatform, WasmPlatform)` pair: **no JVM, no Native.**
This is a genuine platform divergence, not a shortcut. three.js, WebGL, and the DOM do not exist on
JVM or Native; there is no meaningful JVM target for a 3D scene graph over three.js. Do not add a
JVM source set to "round out" the cross-build. The `shared/` directory may freely import
`scala.scalajs.js` and `org.scalajs.dom`: the whole module is Scala.js.

Source and test layout:

- `shared/src/main` holds the whole main surface, both the pure FFI-free layer (the AST, `Bound`,
  `Color`/`Radians`/`Normal`/`Vec3`, `Pointer`, `ThreeFrames`, `Asset`, `ThreeException`, and the
  `boundPropsOf` / `structuralRegion` server-walk halves) and the FFI (the facade, the reconciler,
  `ThreeMount`, `ThreeBackend`, the loaders, `ThreeToImage`). `CrossType.Full` over the JS+Wasm pair
  compiles `shared` for both backends; there is no separate platform `main` source set. The pure layer
  is Node-testable without a GPU. A new FFI source must compile under both backends from one file: use
  `@JSImport`, never `js.Dynamic.global.require` (`require` is not a global under the ESModule the Wasm
  backend mandates).
- `shared/src/test` holds the cross-platform tests; they run on both the JS (CommonJS) and Wasm
  (ESModule) Node backends. Keep tests here.
- The only legitimate js-only split (`kyo-threejs/js/src/test`) is the surface that needs a real
  browser: the WebGL submit and the in-browser WebGL tests. That is the divergence the
  root's "all platforms, shared tests" rule explicitly allows for genuinely platform-specific
  behavior. Do not move a Node-testable test into the js-only tree to dodge anything.

three.js resolves via an `installThree` task that installs `three@0.184.0` into the Scala.js test
linker output dir's parent (so both `require("three")` and `import "three"` resolve); `Test/test`
depends on it. Keep the pin; a version bump is a deliberate, reviewed change.

## Test patterns

**Real tests only.** Assert concrete values on **real three.js objects** running on Node. No mocks,
no spies, no stubs. The reconciler builds an actual three.js scene graph headlessly; a test reads
the real live object's `.position`, `.material.color`, `.children`, or a `Raycaster` intersection
and asserts the concrete value. `assert(distinct >= 2)`, `assert(calls == n)`,
`assert(observedDelta == 33.millis)`: never `assert(true)` or a type-only check. Tests extend the
module's `Test` base class (`ThreeTest`), not ScalaTest directly.

The seams you will use:

- **`Three.testDriver`** is the deterministic frame-loop seam. It materializes the scene headless
  (no `WebGLRenderer`, no live loop) and yields the same `Three.Driver` that the `ThreeFrames.Manual`
  path hands a test. `driver.step(delta)` advances exactly one frame, runs the `onFrame` closures
  inline, and calls the render seam, all with **no sleep**. Drive frame-loop and `onFrame` tests
  through this (and assert ordering / counts deterministically), not through a real loop or a timer.
- **Dispose tests observe the real three.js `'dispose'` event** on the live object, so a leak or a
  double-dispose is observed against real behavior, not a counter you trust.
- **The in-browser WebGL tests** (`ThreeToImageBrowserTest`, `ThreeMountBrowserTest`, `WebGLAcceptanceTest`,
  all extending `WebGLSceneHarness`) load real GL pages into a software-WebGL Chrome and assert
  non-blank pixel buffers or context-release state. On a platform with no downloadable Chrome the
  suite cancels (skips) rather than failing. This is the only place live GL is exercised; new
  GL-visible behavior should be reachable from a test or demo scene so the browser suite covers it.
  For a real software-WebGL GPU render (non-blank pixels) launch Chrome with
  `headedSwiftshaderLaunch` / `headless=false` and `--enable-unsafe-swiftshader`.
- **Backend-seam tests** split by what they prove. On Node with no WebGL, `ThreeBackendTest`
  (`shared/src/test/scala/kyo/ThreeBackendTest.scala`) pins the path index (`buildPathIndex`), the
  camera `SetProp` wire path, the `replaceSubtree` coalesce-to-latest behavior, the 12-key vocabulary
  parity across `boundPropsOf` / `applyByKey` / `extractBoundRefs`, and the bare-node containment;
  `ThreeBackendEventTest` pins server-side `onClick` resolution (foreach positional and keyed,
  render/when, the Embed `"0"`/`"1"` hop, and a malformed-payload drop). The wire-op `Schema`
  round-trips (`SetProp`, `ReplaceSubtree`) live in kyo-ui's `HtmlOpTest`. The full server-to-client
  grain over a real WebSocket is proven in the browser: `ThreeBackendBridgeBrowserTest` (a server
  `SetProp` reaches the embedded cube's material over the one socket with no re-mount, and a
  pre-registration push is buffered and flushed on registration) and `ThreeStructuralBridgeBrowserTest`
  (a server-driven keyed splice and reorder each arrive as one `ReplaceSubtree` with dispose-once and
  reuse, and a real raycast-click on server-driven content resolves server-side). Every assertion
  observes a concrete value: a sampled canvas pixel, a lit-column count, a live-map entry. Mock nothing.
- **README examples are doctest-compiled**, not via the JVM doctest task (a js+wasm-only module has
  no JVM doctest host). `project/ReadmeBlocks.scala` extracts each fenced `scala` block into a
  generated source compiled under the Scala.js compiler. Two consequences for README authors: each
  fence is wrapped in its own `object`, so a fence must be **self-contained** (a `val` in one block
  is not visible in the next); and the generated file is named `*Test.scala` deliberately, because
  the `Frame` macro only derives inside package `kyo` in files whose name ends `Test.scala` /
  `Spec.scala` / `Bench.scala`. A README example that builds a scene through `(using Frame)`
  factories compiles only because of that suffix.

Follow the root's test-file naming rule: a test shares a name prefix with the source it covers
(`Color.scala` to `ColorTest.scala`), and no orphan or scratch test survives a finished change.

## Running demos

Each live-scene demo (`BouncingBalls`, `SolarSystem`, `ReactiveCubeField`, `Snake3D`, `GltfViewer`,
`EmbeddedScene`) is a self-serving `KyoApp` (an `object` in package `democlient`) that starts an
`HttpServer` on Node. Scala.js has no `Test/runMain`, so a demo runs by selecting its launcher as the
`kyo-threejs-demo-runner` main and running it:

```
sbt 'kyo-threejs-demos/fastLinkJS' \
    'set LocalProject("kyo-threejs-demo-runner") / Compile / mainClass := Some("democlient.BouncingBalls")' \
    'kyo-threejs-demo-runner/run'
```

`kyo-threejs-demos/fastLinkJS` links the bundle the page imports, the `set` picks the launcher
(`democlient.BouncingBalls` is the default main, so it can be dropped for that demo), and `run` serves
it on Node. The demo prints `http://localhost:<port>/` to open. There is **no shared dispatcher**: one
`run` launches exactly one demo.

The **demos source set** (`kyo-threejs/shared/src/test/scala/demo/`) is compiled into two distinct
build artifacts:
- `kyo-threejs-demos`: the browser-side bundle (exposes the `DemoMounts` `@JSExportTopLevel` mount
  entries, serves via ESModule; no `node:*` paths, so the linker's dead-code elimination drops the
  HttpServer code).
- `kyo-threejs-demo-runner`: the Node-side runner (links the full `KyoApp` main, including
  `HttpServer`; does not link the browser entries).

The `kyo-threejs-demos` bundle must be linked before serving a feed demo:
`sbt kyo-threejs-demos/fastLinkJS`. The demo launchers (`DemoClientServe`) and the browser tests
(`WebGLSceneHarness.readDemoBundle`) read the bundle from disk at request time and surface a clear
error if the link step has not run.

The **gallery** (`ThumbnailGallery`, `shared/src/test/scala/demo/ThumbnailGallery.scala`) renders
each demo scene to a PNG via `Three.toImage` for visual review and commits the output under
`docs/images/`. It needs a browser WebGL context (a real software-WebGL Chrome) and is not part of
the automated test suite; it is run manually when doc thumbnails need updating.

## Adding a geometry, material, or light

The surface is locked (see below), so adding a primitive is a deliberate four-part change, all in
one pass:

1. **The AST node.** Add the `final case class` variant to the sealed union in `Three.scala`
   (`Three.Ast.Geometry`, `Three.Ast.Material`, or `Three.Ast.Light`), carrying only pure params
   (`Double` / `Int` / `Bound[Color]` / `Bound[Normal]` / `Vec3`). It derives `CanEqual` with its
   union. Keep it a pure value: no effect, no `js.Dynamic` in a public field.
2. **The facade.** Add the three.js constructor to `ThreeFacade` (typed `js.Dynamic`, `js.native`),
   so it resolves through the one `@JSImport("three")` import on both backends.
3. **The factory plus `makeX`.** Add the lowercase factory on the nested companion
   (`Three.Geometry.foo`, `(using Frame)`, pure value), and the `makeFoo` materializer in
   `ThreeFacadeOps`. A GPU-buffer resource goes through `acquireGl` (so it disposes on scope close);
   a holder-only object goes through `acquirePlain`. Every FFI line in `makeFoo` carries
   an `// Unsafe:` rationale. If the prop is bindable over the 12-key vocabulary, wire its `Bound.Ref`
   into all three copies: `ThreeMount.extractBoundRefs` (client subscribe, so reactivity and the
   `toImage` one-shot fill reach it), `Ast.boundPropsOf` (server discovery), and
   `ThreeBackend.applyByKey` (client apply).
4. **A real test.** Add a concrete-value assertion against the real three.js object in the matching
   `*Test.scala` (the live object's actual geometry/material/light fields), plus a dispose
   assertion if it owns a GPU buffer. If it is GL-visible, add or extend a demo so the visual-review
   harness covers it.

A new bound prop needs **no** wire-protocol change to be server-driven. A `SetProp` carries any
`A: Schema` value by key, so wiring the prop into the three vocabulary copies (step 3) is all the
plumbing required: the client-local `.color(signal)` / `.position(signal)` setter binds it locally,
`boundPropsOf` discovers it for the server walk, and `applyByKey` applies the pushed `SetProp`. Only a
new value type that is not yet `Schema`-serializable needs attention, and that is a `Schema`
derivation, not a protocol branch.

A new top-level type or a changed public signature is a surface change: raise it deliberately as a
design decision, not a quiet addition. Nesting new primitives under the existing `Three.Geometry` /
`Three.Material` / `Three.Light` / `Three.Camera` companions keeps the top-level package within its
budget; that is why new variants nest rather than landing at the package root.

## Decision checklist

Before shipping a change to kyo-threejs, run through these questions:

- Does the change touch the AST factories? Verify they remain pure (no effect row, no `js.Dynamic`).
- Does the change add a new bindable prop? Wire it in `ThreeMount.extractBoundRefs` (reactivity),
  `Reconciler.fillReactiveRegionsOnce` (headless fill), and `ThreeMount.fillBoundRefsOnce` (toImage).
  To make it server-drivable, also add it to `Ast.boundPropsOf` (server discovery) and
  `ThreeBackend.applyByKey` (client apply); the `SetProp` wire op carries it with no protocol change.
- Does the change add a new GL resource? Use `acquireGl` (not `acquirePlain`, not a bare `new`);
  verify the dispose test fires exactly once.
- Does the change put a new value on the wire? A bound prop rides `HtmlOp.SetProp` and a structural
  region rides `HtmlOp.ReplaceSubtree`; both carry a `Json.encode`d string, so a new value type is a
  `Schema` derivation on that type (round-tripped by `HtmlOpTest`), not a new wire variant.
- Does the change affect the frame loop? Verify `runFrame` keeps the closures-before-submit
  ordering (`ThreeMountTest`).
- Does the change touch client-side reactive regions? Verify a `Foreach` / `Reactive` region still
  reuses live objects for unchanged keys and disposes removed ones exactly once (`ThreeMountTest`,
  `ReconcilerTest`).
- Is the new source JS+Wasm compatible? A `@JSImport` is fine; a `js.Dynamic.global.require` is not
  (the Wasm backend mandates ESModule). Test under both backends.
- Does the change move a test into `js/src/test`? Only GL-submit or browser-specific behavior
  belongs there.

## Scope and roadmap

Keep the module's coverage honest as a visible trajectory, not a promise of completeness.

**Round 1 (the current curated slice).** kyo-threejs ships a deliberately curated primitive slice,
not all of three.js:

- Geometries: box, sphere, plane, cylinder, cone, torus, plus the `Geometry.custom` escape hatch.
- Materials: the PBR `standard` and the core `basic`, `line`, `points`, plus `Material.custom`.
- Lights: ambient, directional, point, spot, hemisphere.
- Cameras: perspective and orthographic.
- Transforms (position / rotation / scale) with `Bound` props.
- `runMount` + the pluggable frame loop, raycast interaction (`onClick` / `onPointerOver` /
  `onPointerOut`), glTF + texture loading, headless `toImage`, and the reactive scene graph
  (`reactive` / `render` / `when` / `foreach` / `foreachKeyed`).
- A client-owned scene with server-driven reactivity over kyo-ui's WebSocket transport (the Backend
  SPI): the client mounts, animates, and raycasts the real scene locally, while a server-push page
  drives the client through path-addressed `SetProp` (bound props) and `ReplaceSubtree` (a structural
  `Chunk[A]` snapshot the client's own `foreachKeyed` reconciler diffs). A client raycast resolves its
  `onClick` server-side via `BackendEvent`.

Anything outside this slice is reachable **today** through the typed `custom` escape hatches; a
contributor is never blocked on the round-1 boundary.

**Future rounds (the trajectory toward broad three.js coverage).** The aspiration is broad three.js
coverage, approached as explicit rounds, each a deliberate surface expansion:

- Round 2: post-processing (`EffectComposer`, bloom / SSAO / outline passes), shadow-map config,
  environment maps / PMREM, instanced meshes.
- Round 3: skeletal animation + `AnimationMixer` typed clips, morph targets, more loaders
  (OBJ / FBX / DRACO / KTX2), more geometries (extrude / lathe / text).
- Round 4 and beyond: more of three.js, toward the aspirational "all of three.js" coverage.

**The follow-on: a future kyo-webxr.** The WebXR session / input / presentation layer is
the next module, a future `kyo-webxr` depending on kyo-threejs. The frame loop is already
abstracted behind `Frames` (a sealed union), so a WebXR `setAnimationLoop` source flips in as a new
`Frames` variant without disturbing the loop body; WebXR controller / hand input feeds the existing
`Pointer` handler shape. This is a stated direction, not a commitment in this round.

Keep this section honest when you extend the module: a primitive you add moves from a future round
into round 1, and the roadmap shrinks accordingly. The roadmap describes where coverage is going,
never claims it has already arrived.
