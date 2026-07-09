package kyo

/** Durability and rotation knobs for a file-backed [[Journal.Backend]].
  *
  * A `Config` is passed to [[Journal.Backend.file]]. The defaults ([[Config.default]]) are the
  * production settings: every acknowledged append is flushed to stable storage, and segments rotate
  * at 64 MiB. Override `fsync` only in tests; see the field note.
  *
  * Recovery runs lazily on the first touch of a stream, and a `read` or `streamInfo` can be that
  * first touch. When it is, recovery truncates a torn active-segment tail left by a prior crash and
  * logs a WARN, so a nominally read-only operation can perform a one-time on-disk write. The
  * truncation only ever removes an unacknowledged trailing batch; committed events are never lost.
  *
  * @param fsync
  *   durability policy for acknowledged appends. Defaults to [[Fsync.Always]]. [[Fsync.Disabled]]
  *   trades durability for throughput and is TEST-ONLY: the crash-survival guarantee does not hold
  *   when [[Fsync.Disabled]] is set.
  * @param segmentSize
  *   soft rotation threshold. Defaults to 64 MiB. The threshold is checked before an append, not
  *   after: a new segment starts on the next append once the active segment has reached this size.
  *   The active segment can therefore grow past the threshold, and a record larger than the
  *   threshold is written whole into the current active segment rather than a dedicated one.
  * @see
  *   [[Journal.Backend.file]] for the constructor that consumes this config
  */
object FileJournal:

    /** Durability policy for acknowledged appends.
      *
      * Each case encodes one flush discipline applied after a batch write. The enum form is the
      * extension point for future fine-grained modes such as batched flushing or interval-based
      * flushing: adding a new case is a non-breaking change, and exhaustive match sites gain a
      * compile error when new cases arrive rather than silently defaulting.
      *
      * The production default is [[Always]]. [[Disabled]] is reserved for test scenarios that need
      * throughput without crash-survival guarantees. Any code path that is expected to survive a
      * power loss must use [[Always]].
      *
      * @see
      *   [[Config]] for the config type that carries this policy
      */
    enum Fsync derives CanEqual:
        /** Flush every acknowledged append to stable storage before returning. Production default. */
        case Always

        /** No flush; trades durability for throughput. TEST-ONLY: the crash-survival guarantee does
          * not hold.
          */
        case Disabled
    end Fsync

    final case class Config(
        fsync: Fsync = Fsync.Always,
        segmentSize: FileSize = 64L.mib
    ) derives CanEqual

    object Config:
        val default: Config = Config()
    end Config

end FileJournal
