package kyo.internal.tasty.query

import kyo.*
import kyo.Tasty.Classpath

/** Internal binding wrapping a pure-data Classpath and the optional decode context.
  *
  * INV-009 site-3 (bodyTree) reads decodeCtx here. The decodeCtx carries the mmap arena,
  * body source handle, and body memo needed to decode TASTy body bytes on demand.
  *
  * Phase 04: DecodeContext is a stub; wired in Phase 05 when coldLoadBinding is introduced.
  *
  * private[kyo]: accessible within package kyo and kyo.* sub-packages only.
  */
final private[kyo] case class Binding(cp: Classpath, decodeCtx: Maybe[DecodeContext])

private[kyo] object Binding:
    val empty: Binding = Binding(Classpath.empty, Maybe.Absent)
end Binding

/** Carries the decode-time context needed to decode TASTy body bytes on demand.
  *
  * Populated by coldLoadBinding (Phase 05) and stored in Binding.decodeCtx. Absent
  * when a Binding is created from a pre-existing Classpath (withClasspath(cp) form)
  * or from the empty fallback.
  *
  * Phase 04: minimal stub; fields are wired in Phase 05.
  */
sealed private[kyo] trait DecodeContext
