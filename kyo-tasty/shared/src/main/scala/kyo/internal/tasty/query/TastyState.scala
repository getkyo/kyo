package kyo.internal.tasty.query

import kyo.*

/** Process-level state for Kyo TASTy queries.
  *
  * Holds the thread-local binding used by all Tasty.* query methods to resolve the active Classpath,
  * and the module-level lazy fallback for processes that do not call withClasspath explicitly.
  *
  * bindingLocal: set by withClasspath, withPickles. Query methods call bindingLocal.use to obtain
  * the active Binding; if absent they fall back to global.
  *
  * global: initialized at most once per process (lazy val). On JVM, cold-loads java.class.path via
  * PlatformFallback.initFallback. On JS and Native, returns Binding.empty.
  *
  * private[kyo]: accessible within package kyo and kyo.* sub-packages only.
  */
private[kyo] object TastyState:
    val bindingLocal: Local[Maybe[Binding]] = Local.init(Maybe.Absent)
    lazy val global: Binding                = PlatformFallback.initFallback
end TastyState
