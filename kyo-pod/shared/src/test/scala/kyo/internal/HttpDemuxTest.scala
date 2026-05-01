package kyo.internal

import kyo.*

class HttpDemuxTest extends Test:

    "HttpDemux" - {
        "frame whose declared size exceeds remaining buffer is held until next chunk" in pending
        //
        // The HTTP demux parser in HttpContainerBackend maintains its own 8-byte frame-header
        // state machine (separate from internal/LineAssembler.scala). The partial-frame branch
        // — declared size > remaining buffer bytes — is currently exercised only via end-to-end
        // log-stream integration tests against a daemon and is impossible to reach in isolation
        // because the parser is `private` to HttpContainerBackend.
        //
        // To activate this test:
        //   1. Promote the parser (or a thin wrapper around it) to `private[kyo]` on
        //      object HttpContainerBackend.
        //   2. Construct a 50-byte chunk encoding header `(stdout, size=100)` followed by
        //      50 payload bytes. Feed to the parser; expect emit-nothing.
        //   3. Feed the remaining 50 bytes; expect a single emission with the full
        //      100-byte payload.
    }
end HttpDemuxTest
