package kyo.internal

import kyo.*
import org.scalajs.dom

/** The per-node rendering backend SPI, extracted from the hardcoded `DomBackend` so a foreign
  * renderer (kyo-threejs) is a sibling of the DOM backend rather than a host-mount bolt-on.
  *
  * A backend mounts a node into a host element under the page `Scope`, returning a Scope-managed
  * teardown handle, then delivers every later change through `patch` (one scalar prop) or
  * `replaceSubtree` (a structural reactive region). The DOM backend is pre-registered; an island
  * (kyo-threejs) registers its backend at init, keyed by [[BackendNode.backend]].
  *
  * All members are `private[kyo]`: this is the internal backend seam, never user surface. Declared in
  * `js-wasm/src/main` because `mount`'s `host: dom.Element` parameter is JS-only and both
  * implementations are JS/Wasm; the JS-free pieces (`BackendNode`, `BackendMount`, the wire ops) stay
  * in `shared` so kyo-ui keeps compiling on JVM/Native (no three.js, no JS-only DOM type in shared).
  */
private[kyo] trait Backend:
    def key: String

    /** Mounts `node` into `host` under the ambient page `Scope`, returning a teardown handle. Runs
      * EXACTLY ONCE per backend node per lifetime; a signal-driven re-render never re-runs it. DOM:
      * set innerHTML + fire descendant backend mounts. three: reconcile + attach renderer + runLoop.
      */
    def mount(node: UI, host: dom.Element, path: Seq[String])(using Frame): Backend.Live < (Async & Scope)

    /** Applies one path-addressed scalar prop patch on the live tree this backend owns. DOM:
      * re-render the element subtree (Replace semantics, `key` ignored, `encoded` is rendered HTML).
      * three: navigate `path`->live, decode by `key`, set the one bound prop (no rebuild).
      */
    def patch(path: Seq[String], key: String, encoded: String)(using Frame): Unit < Async

    /** Re-materializes the structural reactive region at `path` (a `Reactive`/`Foreach` swap) from the
      * Schema-encoded snapshot, reusing unchanged keys' live objects.
      */
    def replaceSubtree(path: Seq[String], encoded: String)(using Frame): Unit < Async
end Backend

private[kyo] object Backend:
    /** The opaque per-backend teardown handle. Scope-managed: closing the ambient page `Scope` releases
      * every backend renderer (the WebGL context, the frame loop, the controls, the path->Live index).
      */
    private[kyo] trait Live

    // The client-side backend registry, keyed by Backend.key. The DOM backend is pre-registered at
    // first read; an island registers its backend at init. Mutable single-owner (the page's main fiber
    // registers before any mount dispatch reads it); never shared across fibers.
    private val registered: scala.collection.mutable.Map[String, Backend] =
        new scala.collection.mutable.HashMap[String, Backend]()

    private[kyo] def register(backend: Backend): Unit = registered.update(backend.key, backend)

    private[kyo] def lookup(key: String): Maybe[Backend] = Maybe.fromOption(registered.get(key))
end Backend
