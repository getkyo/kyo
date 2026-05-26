package kyo.compat.internal

/** Immutable local-context map threaded through every `CIO` carrier and used by `CLocal` for identity-keyed lookup. Backed by
  * `scala.collection.immutable.Map[Any, Any]` so the keys can be `CLocal[A]` instances looked up by object identity — the `Any`-typed map
  * preserves opacity at the call site that an `AnyRef` widening would block. `CLocal.let` produces a child ctx via `updated`; `CLocal.get`
  * reads it via `get` with a default fallback. The empty ctx is the root passed by `CIO.unsafeRun`.
  */
opaque type LocalCtx = Map[Any, Any]

object LocalCtx:
    /** The root context with no local bindings; passed by `CIO.unsafeRun`. */
    val empty: LocalCtx = Map.empty

    extension (inline ctx: LocalCtx)

        /** Identity-keyed lookup; returns `default` when `key` is not bound. */
        inline def get[A](inline key: Any, inline default: A): A =
            (ctx: Map[Any, Any]).getOrElse(key, default).asInstanceOf[A]

        /** Returns a child context with `key` bound to `value`. */
        inline def updated(inline key: Any, inline value: Any): LocalCtx =
            (ctx: Map[Any, Any]).updated(key, value)

    end extension
end LocalCtx
