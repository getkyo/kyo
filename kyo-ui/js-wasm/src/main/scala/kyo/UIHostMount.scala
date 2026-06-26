package kyo

import org.scalajs.dom

/** The one concrete `HostMount`: closes over the element-typed mount effect. JS+Wasm only,
  * because `org.scalajs.dom.Element` resolves only where scalajs-dom is on the classpath.
  */
final private[kyo] case class DomHostMount(run: dom.Element => (Unit < (Async & Scope))) extends UI.Ast.HostMount

extension (ui: UI.type)
    /** A host element: kyo-ui renders it once as a real DOM `<tag>` (default `canvas`) on
      * every runner and never paints inside it. With no client mount it is a bare
      * cross-platform element; attach client content with the arity-2 `UI.host(tag)(mount)`
      * factory or `Three.embed`.
      *
      * Both arity-1 and arity-2 overloads live in the same extension block here (JS+Wasm)
      * because Scala 3 requires overloaded extension methods on the same type to be in the
      * same `extension` block. On JVM and Native, where `dom.Element` is absent, only the
      * arity-1 form exists (defined in the platform-specific `UIHost.scala`).
      */
    def host(tag: String = "canvas")(using Frame): UI.Ast.Host = UI.Ast.Host(hostTag = tag)

    /** A host with a client mount: kyo-ui renders the `<tag>` on every runner, and on the
      * CLIENT runs `mount(element)` once when the element enters the live DOM at page mount,
      * with its releases running at page teardown (bound to the ambient page Scope). Returns
      * the same cross-platform `UI.Ast.Host` the bare factory returns, with the mount
      * attached. The closure parameter infers `dom.Element` from this arity.
      */
    def host(tag: String)(mount: dom.Element => (Unit < (Async & Scope)))(using Frame): UI.Ast.Host =
        UI.Ast.Host(hostTag = tag).withMount(DomHostMount(mount))
end extension
