package kyo.internal

import kyo.*

/** Compile-time-checked literal interpolators for the kyo-eventlog validated opaque
  * identifier types. Each `inline def` extension on `StringContext` expands, via a
  * paired macro implementation in [[EventInterpolatorMacros]] (a SEPARATE file: a macro
  * cannot be called from its own defining file), to a compile error on an invalid literal or
  * on any interpolated argument, or to the validated value directly on a valid literal. There
  * is no runtime `Abort`: an invalid literal never reaches program execution.
  *
  * `key"..."`, `streamId"..."`, `streamName"..."`, `eventType"..."`, `eventId"..."`, and
  * `journalId"..."` each reuse the SAME predicate their runtime constructor
  * (`Event.Metadata.Key.apply`, `Event.StreamId.apply`, `Event.StreamName.apply`,
  * `Event.Type.apply`, `Event.Id.apply`, `JournalId.validate`) already applies, so the compile-
  * time and runtime checks can never drift apart.
  *
  * A dynamic argument (e.g. `key"trace.${x}"`) is a compile error naming the runtime
  * constructor as the fallback for that case, rather than silently truncating the literal or
  * falling back to a different, effectful return type.
  */
extension (inline sc: StringContext)
    /** Compile-time-validated [[kyo.Event.Metadata.Key]] literal. */
    inline def key(inline args: Any*): Event.Metadata.Key = ${ EventInterpolatorMacros.keyImpl('sc, 'args) }

    /** Compile-time-validated [[kyo.Event.StreamId]] literal. */
    inline def streamId(inline args: Any*): Event.StreamId = ${ EventInterpolatorMacros.streamIdImpl('sc, 'args) }

    /** Compile-time-validated [[kyo.Event.StreamName]] literal. */
    inline def streamName(inline args: Any*): Event.StreamName = ${ EventInterpolatorMacros.streamNameImpl('sc, 'args) }

    /** Compile-time-validated [[kyo.Event.Type]] literal. */
    inline def eventType(inline args: Any*): Event.Type = ${ EventInterpolatorMacros.eventTypeImpl('sc, 'args) }

    /** Compile-time-validated [[kyo.Event.Id]] literal. */
    inline def eventId(inline args: Any*): Event.Id = ${ EventInterpolatorMacros.eventIdImpl('sc, 'args) }

    /** Compile-time-validated [[kyo.JournalId]] literal. */
    inline def journalId(inline args: Any*): JournalId = ${ EventInterpolatorMacros.journalIdImpl('sc, 'args) }
end extension
