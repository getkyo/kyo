package kyo

extension (ui: UI.type)
    /** A host element: kyo-ui renders it once as a real DOM `<tag>` (default `canvas`) on
      * every runner and never paints inside it. With no client mount it is a bare
      * cross-platform element; attach client content with the JS+Wasm `UI.host(tag)(mount)`
      * factory or `Three.embed`.
      *
      * JVM-only copy: the arity-1 and arity-2 overloads live in the same extension block on
      * JS+Wasm (`UIHostMount.scala`). On JVM there is no arity-2 overload (dom.Element is
      * absent), so this standalone extension block is fine under Scala 3's same-group rule.
      */
    def host(tag: String = "canvas")(using Frame): UI.Ast.Host = UI.Ast.Host(hostTag = tag)
end extension
