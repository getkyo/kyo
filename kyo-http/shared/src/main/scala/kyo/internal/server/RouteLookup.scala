package kyo.internal.server

import kyo.*

/** Connection-scoped, zero-allocation route lookup result populated by HttpRouter.findParsed.
  *
  * Replaces the allocating RouteMatch case class: instead of building a Dict[String, String] of path captures during routing, the trie walk
  * records which ParsedRequest segment indices are captures. The segments are decoded lazily in buildCaptures only after routing succeeds.
  *
  * reset() must be called between requests to clear stale match state.
  */
final private[kyo] class RouteLookup(maxCaptures: Int):
    /** Index into HttpRouter.endpoints for the matched handler, or -1 on miss. */
    private[internal] var endpointIdx: Int = -1

    /** Number of path segments captured during the trie walk. */
    private[internal] var captureCount: Int = 0

    /** Segment indices (into ParsedRequest) of each captured path segment, in order. */
    val captureSegmentIndices: Array[Int] = new Array[Int](maxCaptures)

    /** Whether the matched endpoint expects a streaming request body. */
    private[internal] var isStreamingRequest: Boolean = false

    /** Whether the matched endpoint produces a streaming response. */
    private[internal] var isStreamingResponse: Boolean = false

    /** Index of the rest capture in captureSegmentIndices, or -1 if no rest capture. When >= 0, the capture at this index spans all
      * remaining path segments from the stored segment index onward (used for HttpPath.Rest routes).
      */
    private[internal] var restCaptureIdx: Int = -1

    /** True when a valid endpoint was matched. */
    def matched: Boolean = endpointIdx >= 0

    /** Clear match state for reuse on the next request. */
    def reset(): Unit =
        endpointIdx = -1
        captureCount = 0
        restCaptureIdx = -1
    end reset
end RouteLookup
