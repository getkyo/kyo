package kyo.internal

/** Aeron offer return-value sentinels, mirroring the Aeron 1.50.2 offer return codes.
  *
  * Values -1L..-5L match io.aeron.Publication constants. Error (-6L) is the C client's
  * aeron_publication_offer error return, NOT a Java io.aeron.Publication constant; the JVM
  * path throws IllegalArgumentException for oversize and normalizes it to -6.
  *
  * Each constant name mirrors its Aeron Java constant (e.g. `BackPressured` from
  * `Publication.BACK_PRESSURED`), which is why "back pressure" appears here as two words while
  * the public `TopicBackpressureException` type uses kyo's one-word spelling.
  *
  * These constants are used by the shared `Topic` logic (via `Topic.mapOfferResult`) to map
  * the raw `Long` returned by `AeronTransport.offer` to typed `TopicException` leaves,
  * so the mapping is byte-identical across JVM, Native, and JS platforms.
  */
private[kyo] object AeronSentinels:
    inline val NotConnected        = -1L
    inline val BackPressured       = -2L
    inline val AdminAction         = -3L
    inline val Closed              = -4L
    inline val MaxPositionExceeded = -5L
    inline val Error               = -6L
end AeronSentinels
