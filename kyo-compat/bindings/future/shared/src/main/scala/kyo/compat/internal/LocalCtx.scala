package kyo.compat.internal

/** Immutable local-context map threaded through every `CIO` carrier. `CLocal.let` produces a child ctx; `CLocal.get` reads it. The empty
  * ctx is the root passed by `CIO.unsafeRun`. Backed by `scala.collection.immutable.Map[Any, Any]` so opaque-typed keys (e.g. `CLocal[A]`)
  * work without an `AnyRef` widening that opacity at the call site would block.
  */
opaque type LocalCtx = Map[Any, Any]

object LocalCtx:
    val empty: LocalCtx = Map.empty

    extension (inline ctx: LocalCtx)

        inline def get[A](inline key: Any, inline default: A): A =
            (ctx: Map[Any, Any]).getOrElse(key, default).asInstanceOf[A]

        inline def updated(inline key: Any, inline value: Any): LocalCtx =
            (ctx: Map[Any, Any]).updated(key, value)

    end extension
end LocalCtx
