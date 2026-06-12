package kyo.net

import kyo.*

/** Coverage for the JS read-dispatch re-entry under a long run of back-to-back reads over the REAL Node transport.
  *
  * JS has no poll loop: a read completes when Node fires a `"data"` event, and the read pump re-registers for the next read from INSIDE its own
  * `onComplete` callback (`requestNextRead -> awaitRead -> socket.resume()`). Because `IOPromise.complete` fires `onComplete()` inline (it is NOT
  * a trampoline), the re-entry is stack-safe only if each read completion lands on a FRESH stack frame rather than synchronously nested inside the
  * `resume()` that armed it. The real JS path satisfies this: Node never delivers `"data"` synchronously within `resume()`; a paused socket
  * re-armed in its data handler delivers the next chunk on a LATER event-loop turn (verified directly: even a 1 MB backlog re-armed in the handler
  * reaches max nested-call depth 1, never nested). The single inline-completion path in the driver, the leftover delivery in
  * `JsIoDriver.awaitRead`, clears the leftover before completing, so it completes inline at most once per chain and then falls back to the async
  * `resume()` path; it cannot chain. So a long stream drives many real re-entry cycles, each on its own frame.
  *
  * This test pins that behavior end to end over a real in-process Node loopback connection: the server streams a multi-megabyte payload that Node
  * fragments into many `"data"` chunks (the read pump re-arms once per chunk), and the client reassembles every byte and asserts the full payload
  * is reconstructed exactly and completely. A regression that made the read dispatch deliver synchronously, lose a re-arm, or drop / reorder a
  * chunk would corrupt the reassembled payload or hang. Determinism: the reader loops until it has read exactly the known payload length (driven
  * by completion of each `inbound.take`), with no sleep, poll, or retry.
  *
  * Note: a fake socket whose `resume()` delivered data SYNCHRONOUSLY does overflow the re-entry chain, but that delivery pattern is not reachable
  * through the real driver on real Node (verified), so the synchronous-recursion case is reported as a latent fragility rather than asserted here.
  */
class JsReadDispatchTest extends Test:

    import AllowUnsafe.embrace.danger

    // A few MB so Node fragments the write into many "data" chunks: each chunk drives one real read-pump re-entry cycle (awaitRead -> resume ->
    // data -> complete -> onComplete -> requestNextRead), exercising a long run of back-to-back reads on the actual JS dispatch.
    private val payloadSize = 4 * 1024 * 1024

    private def payloadByte(i: Int): Byte = ((i * 2654435761L) & 0xff).toByte

    /** Send `payloadSize` bytes of the deterministic payload on `conn.outbound` in bounded slices, so the writer does not allocate the whole
      * payload as one span. The server side streams; the client side asserts the reassembled bytes.
      */
    private def streamPayload(conn: Connection)(using Frame): Unit < (Async & Abort[Closed]) =
        val slice = 64 * 1024
        Loop(0) { off =>
            if off >= payloadSize then Loop.done(())
            else
                val len = math.min(slice, payloadSize - off)
                val arr = Array.tabulate[Byte](len)(j => payloadByte(off + j))
                conn.outbound.safe.put(Span.fromUnsafe(arr)).andThen(Loop.continue(off + len))
        }
    end streamPayload

    "JS read dispatch re-entry over the real Node transport" - {

        "a multi-megabyte stream drives many back-to-back reads, reassembled exactly and in order (no StackOverflow, no lost re-arm)" in {
            val transport: Transport = NetPlatform.transport
            for
                listener <- transport.listen("127.0.0.1", 0, 128) { serverConn =>
                    // Server: stream the whole payload, then leave the connection for the client to drain and close.
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed](streamPayload(serverConn)).unit
                        }
                    })
                }.safe.get
                port = listener.port
                conn <- transport.connect("127.0.0.1", port).safe.get
                // Read until the full payload length is collected. Each take is one (or more) Node "data" chunk(s); the read pump re-arms after
                // each, so this loop walks the real re-entry path payloadSize bytes deep.
                collected <- Loop[Array[Byte], Array[Byte], Async & Abort[Closed]](Array.emptyByteArray) { acc =>
                    if acc.length >= payloadSize then Loop.done(acc)
                    else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
                }
            yield
                conn.close()
                listener.close()
                assert(collected.length == payloadSize, s"expected $payloadSize bytes reassembled, got ${collected.length}")
                // Verify the full byte sequence: any reorder / drop / duplication from a misdriven re-entry corrupts this.
                val ordered =
                    var i  = 0
                    var ok = true
                    while i < payloadSize && ok do
                        if collected(i) != payloadByte(i) then ok = false
                        i += 1
                    ok
                end ordered
                assert(ordered, "reassembled payload did not match: the read dispatch dropped, reordered, or duplicated bytes")
                succeed
            end for
        }
    }

end JsReadDispatchTest
