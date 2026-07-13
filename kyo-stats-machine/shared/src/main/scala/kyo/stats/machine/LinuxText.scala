package kyo.stats.machine

import kyo.*

/** A read-only text view over a borrowed byte span, for the INIT-TIME and mount-change reads only: the
  * cgroup layout resolution and the mount-table parse, each of which runs once per sampler (or once per
  * real mount change) and is explicitly outside the zero-allocation tick claim.
  *
  * The per-tick decode path never comes here. It scans the sampler's retained buffer in place through
  * `LinuxScan` and allocates nothing.
  */
private[machine] object LinuxText:

    /** Decodes the borrowed bytes to a `String`. The borrowed span never escapes the caller. */
    def fromSpan(bytes: Span[Byte], len: Int): String =
        new String(Span.toArrayUnsafe(bytes), 0, len, java.nio.charset.StandardCharsets.US_ASCII)

    def lines(bytes: Span[Byte], len: Int): Iterator[String] =
        fromSpan(bytes, len).linesIterator

    /** The whitespace tokens of `line`. */
    def tokens(line: String): IndexedSeq[String] =
        line.trim.split("\\s+").toIndexedSeq

end LinuxText
