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

`Three.embed(scene, camera)` is the kyo-ui bridge adapter. It returns a `UI.Ast.Host`, placing the
3D canvas as a first-class child inside a kyo-ui tree, alongside ordinary kyo-ui elements. The
returned `Host` carries a `DomHostMount` (the kyo-ui JS-platform mount type); kyo-ui's
`DomBackend.fireHostMounts` runs it once after the element is in the DOM, which acquires the
`WebGLRenderer`, starts the frame loop, and wires pointer delegation, exactly as `Three.runMount`
would. The 3D pipeline runs under the ambient `Scope` of the surrounding `UI.runMount` call; closing
that scope disposes the renderer. `Three.embed` is defined in `ThreeMount.scala` (not in `Three.scala`)
because it depends on `DomHostMount`, a JS-platform type not available in the shared source. It is
the only entry point that converts a `Three` scene value into a `UI.Ast.Host` (its `scene` parameter
is typed `Three`, the same umbrella type `runMount` takes).

When adding behaviour to the embed path, keep the mount closure inside `Three.embed` thin: it
delegates to the same `ThreeMount.runLoop` and resource acquisition that `runMount` uses. Do not add
per-embed state or ad-hoc logic to the closure; if a new capability is needed, add it to the shared
`ThreeMount` internal layer first so both `runMount` and `embed` benefit.

A reviewer should be able to state the headline invariant in one line: **a `Three` is a pure
immutable value; the reconciler holds a 1:1 node-identity to live-object map and applies targeted
mutations, never a scene rebuild.**

## Client/server 3D reactivity (the host-bridge seam)

kyo-threejs runs **3D always on the client** (WebGL is browser-only; no server-side GL). The
campaign added a server-push reactive layer that lets server-owned `Signal` changes reach the
browser scene and client raycasts run server-side `onClick` closures, over kyo-ui's existing
WebSocket transport (not a bespoke channel).

### Architecture

Three seams collaborate across the process boundary:

**Server side.** `ThreeMount.serverBridge` (defined at `ThreeMount.scala:316`) builds a
`UI.Ast.HostBridge` implementation that the kyo-ui server attaches to a `UI.host` node via
`.withServerBridge(...)`. Three responsibilities:
- `serverInit`: flattens the initial scene to an inline JSON boot payload the SSR page emits as
  a `<script type="application/json" data-kyo-host-init>` data island.
- `subscriptions`: forks one observe fiber per server-owned `Bound.Ref` prop (via
  `ThreeBridge.observeProps`) and one per structural region (via `ThreeBridge.observeStructure`),
  each encoding changes as a `HostPayload` and pushing over the WebSocket.
- `onPick`: runs the server-side `onClick` closure for a client raycast-hit by node id
  (`ThreeBridge.runPick`).

**Client island.** `ThreeIsland.kyoThreeIsland` (`demos/src/main/scala/kyo/ThreeIsland.scala:17`)
is a `@JSExportTopLevel` entry point the SSR page loads as an ESModule. On load it scans the
document for every `[data-kyo-host]` element, reads that host's inline init payload, and calls
`ThreeMount.islandMount` for each host on a detached fiber whose ambient `Scope` stays open for the
page lifetime. The mount runs **once per host and never re-mounts**; subsequent server pushes flow
through the per-host channel opened at mount time (the persistent-channel invariant).

**Per-host channel.** `HostChannel` (`shared/src/main/scala/kyo/HostChannel.scala`) holds one
mirror `SignalRef[Any]` per bound prop slot of the reconstituted scene plus a structural inbox
(`SignalRef[Chunk[StructuralOp]]`). The inline `clientJs` routes each inbound `HostUpdate` to the
correct channel by `data-kyo-path`. A `HostPayload.Prop` write drives exactly one targeted
`patchProp` through the reconciler's `forkBoundRef` fiber. A `HostPayload.Structural` appends to
the structural inbox, which the `subscribeStructuralInbox` drain loop processes in FIFO order.

### The typed wire payload

The wire is **fully typed, closure-free, and `js.Dynamic`-free**. All types derive `Schema` and
live in `kyo-ui/shared/src/main/scala/kyo/internal/HostPayload.scala`:

- `HostPayload`: three leaves. `Prop(nodeId, slot, value)` for one targeted prop push;
  `Structural(op)` for one keyed splice instruction; `Boot(insert, camera)` for the page-load boot
  envelope, carrying the scene's root insert alongside the embed's `CameraDescriptor` so the client
  reconstitutes the server's actual viewpoint, not a default one.
- `HostValue`: the value union, tagged by SLOT (not runtime type, which mis-tags a whole-number
  scalar that boxes to an `Int` on Scala.js). `V3(x, y, z)` for position/rotation/scale; `Col(rgb)`
  for color/emissive; `Num(value)` for opacity/metalness/roughness/intensity scalars.
- `StructuralOp`: three leaves. `Insert(key, index, descriptor)` to splice a new subtree;
  `Remove(key)` to dispose one; `Move(key, toIndex)` to reorder without dispose.
- `SceneDescriptor`: the serializable declarative form of a spliced subtree: kind tag, resolved
  prop values, and children recursively. A `mesh` kind also carries its typed `GeometryDescriptor`
  (the geometry shape + numeric params, one leaf per `Three.Geometry.*`) and `MaterialKind` tag (the
  material class, one leaf per `Three.Material.*`), so the client rebuilds the exact sphere/torus and
  basic/standard/line/points material, not a hardcoded box + standard. Carries no closure, no signal
  ref, and no `Custom`, `Reactive`, or `Foreach` node; a `Custom` geometry or material drops at
  flatten time.
- `GeometryDescriptor` / `MaterialKind` / `CameraDescriptor`: the typed, FFI-free shapes a mesh's
  geometry, a mesh's material class, and the embed's camera flatten to, one leaf per `Three.Geometry.*`,
  `Three.Material.*`, and `Three.Camera.*` factory the AST supports. A `Custom` geometry/material has
  no leaf (it stays server-side).
- `PointerData`: the FFI-free wire form of a raycast-hit pointer, carried client-to-server for
  pick routing. Plain `Double` fields; no three.js object crosses the wire.

The **serializable subset** is geometry, material, transform, and lights whose props resolve to
plain values. Closures (`onClick`/`onFrame`), server-owned signals, and `Custom`/`Reactive`/
`Foreach` nodes **stay server-side** and never cross the wire. Reaching a non-serializable node
while flattening raises a typed `ThreeBridge.UnserializableNode` failure rather than silently
dropping subtrees. The boot path is the documented-drop side of this boundary (it falls back to an
empty scene); the live structural-diff path propagates the typed failure to the caller.

Node identity on the wire uses a depth-first index path string (`"r"` for the root, `"r.0"` for
its first child, `"r.0.1"` recursively). This scheme is **deterministic across flatten and
reconstitute**, so a `HostPayload.Prop(nodeId, slot, value)` the server emits addresses the same
node the client reconstituted.

The slot names the server and client share are string constants on `ThreeBridge`
(`ThreeBridge.slotPosition`, `slotRotation`, `slotScale`, `slotColor`, `slotOpacity`,
`slotMetalness`, `slotRoughness`, `slotEmissive`, `slotIntensity`). Adding a new bindable prop
kind requires adding a slot constant here and wiring it in both `ThreeBridge.collectBounds`
(server side) and `ThreeBridge.materializeNode` (client reconstitution).

### Structural reactivity over the channel

A server reactive region (`foreach`/`foreachKeyed`) splices, removes, or reorders a 3D subtree on
the client over the same host channel, with no re-mount. The server-side keyed diff
(`ThreeBridge.diffKeyedServer`) produces the minimal op set: a surviving key at an unchanged index
emits no op; a surviving key at a changed index emits `Move`; a removed key emits `Remove`; a new
key emits `Insert` carrying its flattened `SceneDescriptor`. Only an `Insert` re-flattens a
subtree; `Move` and `Remove` carry only the key.

The client drain loop (`ThreeMount.subscribeStructuralInbox`, `ThreeMount.scala:883`) applies each
op via `ThreeMount.applyStructuralOp` (`ThreeMount.scala:939`):
- `Insert`: reconstitutes the descriptor through `ThreeBridge.reconstitute`, materializes it under
  a fresh per-element scope (so its GL resources dispose when that scope closes), and splices it
  at the requested index.
- `Remove`: closes the key's per-element scope **exactly once**, disposing its GL resources once
  and never more. A stale second `Remove` for the same key finds no entry and is a no-op (the
  double-dispose guard).
- `Move`: reuses the existing live `Object3D` reference at a new index with no dispose; GPU
  buffers survive a reorder unchanged.

The drain loop uses `Signal.next` rather than `Signal.observe` for the structural inbox
deliberately: `observe` runs each value inside a per-value `Scope` that would dispose an inserted
element's per-element scope on the next op. The `next`-driven loop runs splices under the island's
long-lived ambient `Scope`, so an inserted subtree lives until its own `Remove`.

### The persistent-channel invariant

The host mount fires **once** per host element and holds the channel open for the page lifetime.
A re-mount is never triggered by a subsequent server push, a sibling reactive re-render, or a
prop change. All updates flow through the open channel. If you add a code path that could
re-invoke `ThreeMount.islandMount` for an already-mounted host, you are breaking this invariant.

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
  acquire. Guarded by `ThreeMountTest` and `ReconcilerTest`; the renderer-disposal half is
  browser-required (see the real-GL gate below). The channel structural-reactivity path extends
  this: a `Remove` closes the per-element scope exactly once; a `Move` leaves it open (GPU buffers
  survive). Guarded by `ThreeMountChannelTest` ("a removed key disposes its GL scope exactly once"
  and "a removed key is not disposed twice").

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

- **Prop pushes never re-materialize.** A `HostPayload.Prop` arriving over the channel writes
  exactly one slot mirror and drives exactly one targeted `patchProp`; the live-map count is
  unchanged. A prop push must not trigger a scene rebuild or a re-materialize of any subtree.
  Guarded by `ThreeMountChannelTest` ("successive prop pushes do not re-materialize the scene
  (live-map count is stable)").

- **The wire carries no closures and no `js.Dynamic`.** A `HostPayload`, `HostValue`,
  `StructuralOp`, or `SceneDescriptor` serialized to JSON and decoded on the client contains only
  typed Scala values. `Custom`/`Reactive`/`Foreach` nodes and any node carrying a closure fail with
  a typed `ThreeBridge.UnserializableNode` at flatten time rather than silently serializing a broken
  payload. Guarded by `ThreeMountChannelTest` ("a Custom/closure subtree is not flattened to a
  descriptor").

- **Structural reorder preserves live-object identity.** A `Move` op reorders a keyed node
  without disposing or recreating its `Object3D` or its GPU buffers. The live reference before and
  after a `Move` is `eq`-identical. Guarded by `ThreeMountChannelTest` ("a surviving node is not
  disposed on reorder").

- **The server keyed diff is minimal.** `ThreeBridge.observeStructure` emits only the ops
  needed: one `Remove` per removed key, one `Move` per key at a changed index, one `Insert` per new
  key. A full re-materialize of `[a,c,d]` from `[a,b,c]` would emit 3 removes and 3 inserts; the
  diff emits exactly 3 ops. Guarded by `ThreeMountChannelTest` ("the server-side keyed diff emits
  the minimal op set").

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
  4. **The WS-receiver callback** (`ThreeMount.registerChannelReceiver`, `ThreeMount.scala:233`)
     that decodes a `HostPayload` and applies it to the channel synchronously via
     `Sync.Unsafe.evalOrThrow`. This is the JS-callback-to-effect bridge for the channel path.
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

- `shared/src/main` holds the pure, FFI-free surface (the AST, `Bound`, `Color`/`Radians`/`Normal`/
  `Vec3`, `Pointer`, `ThreeFrames`, `Asset`, `ThreeException`, `ThreeBridge`, `HostChannel`,
  `HostInit`). This is Node-testable without a GPU.
- `js-wasm/src/main` holds the FFI: the facade, the reconciler, `ThreeMount`, the loaders,
  `ThreeToImage`. `CrossType.Full` does not auto-wire this directory for a two-platform cross, so
  both `jsSettings` and `wasmSettings` add it explicitly. A new FFI source must compile under both
  backends from one file: use `@JSImport`, never `js.Dynamic.global.require` (`require`
  is not a global under the ESModule the Wasm backend mandates).
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
- **Channel-level reactivity tests** (`ThreeMountChannelTest`, `shared/src/test/scala/kyo/ThreeMountChannelTest.scala`)
  drive the full server-to-client grain on Node with no WebGL: a server signal change flattens to
  a boot payload, reconstitutes into per-slot mirror `SignalRef`s, a `HostPayload` write routes
  through `HostChannel` to the mirror, and the reconciler's patch path applies exactly one targeted
  mutation to the live three.js object. Synchronization uses `Channel`/`Fiber`/latch primitives
  (no sleeps). Every assertion observes a concrete value: a live material color hex, a mirror's
  current value, a counter. Mock nothing.
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
`EmbeddedScene`) is a self-serving `KyoApp` that starts an `HttpServer` on Node. Scala.js has no
`Test/runMain`, so each demo runs through a named sbt command alias (defined in `build.sbt:1847`):

```
sbt demoBouncingBalls
sbt demoSolarSystem
sbt demoReactiveCubeField
sbt demoSnake3D
sbt demoGltfViewer
sbt demoEmbeddedScene
```

Each alias sets `kyo-threejs-demo-runner / Compile / mainClass` to the chosen demo's object and runs
`kyo-threejs-demo-runner/run`. The demo prints `http://localhost:<port>/` to open. There is **no
shared dispatcher**: each alias launches exactly one demo, independently.

The **demos source set** (`kyo-threejs/shared/src/test/scala/demo/`) is compiled into two distinct
build artifacts:
- `kyo-threejs-demos`: the browser-side bundle (links `kyoThreeIsland`, serves via ESModule; no
  `node:*` paths, so the linker's dead-code elimination drops the HttpServer code).
- `kyo-threejs-demo-runner`: the Node-side runner (links the full `KyoApp` main, including
  `HttpServer`; does not link `kyoThreeIsland`).

The `kyo-threejs-demos` bundle must be linked before serving a server-push demo:
`sbt kyo-threejs-demos/fastLinkJS`. The `DemoServe.islandHandler` reads the bundle from disk at
request time and returns a 500 with an explanatory message if the link step has not run.

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
   an `// Unsafe:` rationale. If the prop is bindable, wire its `Bound.Ref` patch in
   `ThreeMount.extractBoundRefs` so reactivity and the `toImage` one-shot fill both reach it.
4. **A real test.** Add a concrete-value assertion against the real three.js object in the matching
   `*Test.scala` (the live object's actual geometry/material/light fields), plus a dispose
   assertion if it owns a GPU buffer. If it is GL-visible, add or extend a demo so the visual-review
   harness covers it.

If the new prop is a `Bound` prop that should cross the server-push wire, you also need:
- A slot constant on `ThreeBridge` (`ThreeBridge.slotXxx`).
- A `collectBounds` branch in `ThreeBridge` that emits the `(slot, Bound)` pair for the new node
  kind.
- A `materializeNode` branch in `ThreeBridge` that reconstitutes the prop as a `Bound.Ref` on the
  client side.
- A test in `ThreeMountChannelTest` asserting the prop reaches the client mirror and drives the
  live object.

A new top-level type or a changed public signature is a surface change: raise it deliberately as a
design decision, not a quiet addition. Nesting new primitives under the existing `Three.Geometry` /
`Three.Material` / `Three.Light` / `Three.Camera` companions keeps the top-level package within its
budget; that is why new variants nest rather than landing at the package root.

## Decision checklist

Before shipping a change to kyo-threejs, run through these questions:

- Does the change touch the AST factories? Verify they remain pure (no effect row, no `js.Dynamic`).
- Does the change add a new bindable prop? Wire it in `ThreeMount.extractBoundRefs` (reactivity),
  `Reconciler.fillReactiveRegionsOnce` (headless fill), and `ThreeMount.fillBoundRefsOnce`
  (toImage). If it should cross the server-push wire, add the slot constant and the
  `ThreeBridge.collectBounds` / `ThreeBridge.materializeNode` branches.
- Does the change add a new GL resource? Use `acquireGl` (not `acquirePlain`, not a bare `new`);
  verify the dispose test fires exactly once.
- Does the change touch the `HostChannel` or `ThreeBridge` wire protocol? Any new slot or payload
  variant must be reflected in both the server flatten path and the client reconstitution path; the
  tests in `ThreeMountChannelTest` must cover the round-trip.
- Does the change add a new `Bound` prop that should not cross the wire (a closure-bearing or
  signal-bearing prop)? Add a branch to `ThreeBridge.unserializableKind` so flatten raises the
  typed failure rather than silently shipping a broken payload.
- Does the change affect the frame loop? Verify `runFrame` keeps the closures-before-submit
  ordering (`ThreeMountTest`).
- Does the change affect the structural drain loop? Verify the inbox uses `Signal.next` (not
  `Signal.observe`) so inserted element scopes are not closed on the next op.
- Does the change add a new entry point that could re-mount an already-mounted host? That breaks
  the persistent-channel invariant.
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
- Client/server 3D reactivity over kyo-ui's WebSocket transport: server-owned `Signal` changes
  reach the client scene; client raycasts run server-side `onClick` closures. Structural reactivity
  (`foreach`/`foreachKeyed`) splices/removes/reorders 3D subtrees over the same channel with
  minimal diffs and no re-mount.

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
