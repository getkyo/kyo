package kyo.internal

/** Aeron offer return-value sentinels, mirroring the Aeron 1.50.2 offer return codes.
  *
  * `Topic.mapOfferResult` maps the raw `Long` from `AeronTransport.offer` through these to typed
  * `TopicException` leaves, so the mapping is identical across JVM, Native, and JS.
  *
  * Values -1L..-5L match io.aeron.Publication constants. Error (-6L) is the C client's
  * aeron_publication_offer error return, not a Java constant; the JVM path normalizes its oversize
  * IllegalArgumentException to -6. Names mirror the Aeron Java constants (`BackPressured` from
  * `Publication.BACK_PRESSURED`), hence the two-word spelling against the public
  * `TopicBackpressureException`.
  */
private[kyo] object AeronSentinels:
    inline val NotConnected        = -1L
    inline val BackPressured       = -2L
    inline val AdminAction         = -3L
    inline val Closed              = -4L
    inline val MaxPositionExceeded = -5L
    inline val Error               = -6L
end AeronSentinels
