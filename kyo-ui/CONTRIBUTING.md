# Contributing to kyo-ui

This is the module-specific contributor guide for kyo-ui. It carries only what is particular to
this module. For every general Kyo convention (effect rows, naming, `using` ordering, `inline`,
Kyo types over stdlib, the safe-by-default tier, test base classes, the "fix the code not the
test" and "reproduce before you fix" rules), the [root CONTRIBUTING.md](../CONTRIBUTING.md) is
the authority. When this file and the root differ on a generic rule, the root wins; this file
never restates a root rule, it points at it.

kyo-ui is the declarative UI layer for Kyo: a pure-value AST, `Signal` reactivity, and three
runners (Scala.js DOM, HTTP server-push, HTML stream). Its core model mirrors kyo-three: factories
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

An external renderer (`kyo-three`, a chart library, a map widget) builds a `UI.Ast.Host` by
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
(e.g., `kyo-three`) accesses the host bridge through the public `UI.host(tag)(mount)` factory,
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

## Test patterns

Cross-platform tests for pure logic (style rules, AST shape, renderer output to HTML) live in
`shared/src/test` and run on JVM, JS, and Native. Browser tests that require a real DOM (the DOM
backend, the host mount seam, reactive update counts) live in `js-wasm/src/test` or, for tests
that need a real Chrome (a host mount fired in a live page, a GL context released), in the
downstream `kyo-three/js/src/test`. Follow the root file-naming rule: every test file shares a
name prefix with the source it covers.
