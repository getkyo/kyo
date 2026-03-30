# WebSocket Bug Research: zio-http, sttp, tapir

## Summary

Researched WebSocket bugs across zio-http (38+ closed issues), sttp (30+ issues), and tapir (30+ issues). Identified **14 distinct bug categories** with real production impact. Our test suite (`WebSocketTest.scala` with ~35 tests + `WebSocketLocalTest.scala` with ~25 tests) covers basic scenarios well but misses several critical edge cases discovered in these projects.

---

## Bug Catalog

### BUG 1: Send Before Handshake Complete (Race Condition)
- **Source**: zio-http #2737 (closed), tapir #3685 (open)
- **Scenario**: Server sends a WebSocket frame (text or ping) before the Netty pipeline has completed the HTTP-to-WebSocket upgrade. The frame is written to the HTTP encoder instead of the WebSocket encoder, causing `UnsupportedOperationException: unsupported message type: TextWebSocketFrame`.
- **Why it failed**: The server handler was invoked immediately on connection, but the Netty channel pipeline hadn't swapped the HTTP codec for the WebSocket codec yet. Messages sent during this window get routed to `HttpObjectEncoder` which rejects WebSocket frames.
- **Fix**: zio-http PR #3028 -- buffer outbound messages until `HandshakeComplete` event fires. The send method now awaits handshake completion internally.
- **Our coverage**: **COVERED** -- test "handler sends greeting then echoes" and "send waits for handshake" in zio-http's own tests. Our test "handler sends greeting then echoes" tests immediate server-side send. However, we do NOT explicitly test timing-sensitive race conditions (e.g., sending from server before client's handshake ack).
- **Recommendation**: Add a stress test that opens many concurrent connections where the server immediately sends a message, to trigger the race window.

### BUG 2: onClose Race Condition -- Effect Interrupted Before Execution
- **Source**: zio-http #1147
- **Scenario**: `WebSocketApp.onClose` callback's ZIO effect is forked and then immediately interrupted because the channel close triggers an interrupt before the fiber even starts executing.
- **Why it failed**: `HttpRuntime` forks the effect and sets up a channel-close listener that interrupts the fiber. When the channel is already closed (which triggered onClose in the first place), the interrupt races against fiber startup. If the fiber hasn't started yet, even `.uninterruptible` doesn't help because the interrupt arrives before the uninterruptible region begins.
- **Fix**: Changed to `unsafeRunAsync` without forking, so the close handler runs synchronously on the event loop.
- **Our coverage**: **PARTIALLY COVERED** -- we test "server handler returns immediately" and "close after exchange", but we don't verify that close-time cleanup logic actually executes to completion.
- **Recommendation**: Add a test where the server performs a side effect in a close handler (e.g., writes to an AtomicRef) and verify it executed.

### BUG 3: WebSocket Client Not Interruptible
- **Source**: zio-http #2199 (closed, $75 bounty)
- **Scenario**: A WebSocket client connection could not be interrupted/cancelled once established. The fiber running the WebSocket app would hang indefinitely even when interrupted.
- **Why it failed**: The internal implementation didn't propagate interruption correctly through the Netty event loop integration.
- **Fix**: PR #2200 by @vigoo -- properly wired interruption through the WebSocket client connection.
- **Our coverage**: **PARTIALLY COVERED** -- test "client disconnects mid-stream" exits after 3 messages, which implicitly tests interruption. But no explicit test that `Async.timeout` or fiber interruption works on a long-running WS connection.
- **Recommendation**: Add a test where a WebSocket operation is explicitly interrupted via timeout and verify it terminates cleanly.

### BUG 4: WebSocket Send Not Thread-Safe (Concurrent Sends)
- **Source**: sttp #902
- **Scenario**: Calling `ws.sendText()` from multiple fibers/threads concurrently causes `IllegalStateException: Send pending` with Java's `HttpClient` WebSocket implementation.
- **Why it failed**: Java's `HttpClient` WebSocket doesn't allow concurrent `sendText` calls. The previous send must complete before the next one starts.
- **Fix**: sttp 3.2.3 serialized sends internally.
- **Our coverage**: **COVERED** -- test "bidirectional concurrent exchange" uses `Async.gather` for concurrent sends. Our `WebSocket` implementation uses channels which naturally serialize.
- **Recommendation**: No additional test needed -- our channel-based design inherently serializes sends.

### BUG 5: Large Unicode Payload Causes Blocking/Frame Size Exceeded
- **Source**: sttp #901
- **Scenario**: Sending large Unicode text (3500+ chars of multi-byte Unicode) exceeds the default WebSocket frame size limit (10240 bytes in Netty), causing `CorruptedWebSocketFrameException: Max frame length exceeded` or blocking indefinitely.
- **Why it failed**: Multi-byte Unicode characters inflate the byte size. 3500 Unicode chars * 3 bytes = ~10500 bytes > 10240 byte default limit.
- **Fix**: Document and expose max frame size configuration.
- **Our coverage**: **PARTIALLY COVERED** -- test "large text frame" sends 65000 ASCII chars, and "unicode text roundtrip" tests a small Unicode string. But NO test for large Unicode payloads specifically (where byte count >> char count).
- **Recommendation**: Add a test sending a large Unicode payload (e.g., 10000 multi-byte Unicode characters) to verify frame size limits are handled correctly.

### BUG 6: Binary Frame Reference Count (Netty ByteBuf Leak)
- **Source**: zio-http #1004
- **Scenario**: Echoing a binary WebSocket frame causes `IllegalReferenceCountException: refCnt: 0` because the Netty ByteBuf was released before being sent back.
- **Why it failed**: Netty ByteBufs are reference-counted. When a received frame's buffer is directly passed to an outbound frame, Netty releases it after reading, making the outbound write fail.
- **Fix**: Copy the buffer data before sending it back.
- **Our coverage**: **COVERED** -- test "binary echo" works. Our implementation likely copies data during the Span conversion, avoiding the ByteBuf issue.
- **Recommendation**: No additional test needed if we copy data at the Netty boundary.

### BUG 7: Response.fromSocketApp Erases Headers
- **Source**: zio-http #2278
- **Scenario**: Custom headers set on a WebSocket upgrade response (e.g., `Sec-WebSocket-Protocol` for subprotocol negotiation) are silently dropped during the conversion from `SocketApp` to `Response`.
- **Why it failed**: The conversion code created a new Response without carrying over the headers from the original.
- **Fix**: PR #2338 -- preserve headers through the conversion.
- **Our coverage**: **COVERED** -- test "request headers accessible" verifies headers are available in the WS handler. But we don't test that response headers from the server are visible to the client during upgrade.
- **Recommendation**: Add a test that the server can set custom response headers during WS upgrade and the client can read them.

### BUG 8: Multiple Concurrent Connections Limited
- **Source**: zio-http #623
- **Scenario**: Server could not handle more than ~5 concurrent WebSocket connections when the Response was wrapped in ZIO.
- **Why it failed**: The effectful response creation blocked the event loop, preventing new connections from being accepted.
- **Fix**: Ensure WebSocket responses are created asynchronously.
- **Our coverage**: **COVERED** -- tests "multiple client connections", "5 concurrent clients", and the 1024-connection stress test in zio-http.
- **Recommendation**: Consider adding a higher-concurrency stress test (e.g., 50+ concurrent WS connections).

### BUG 9: Pong Send Fails on Already-Closed WebSocket
- **Source**: sttp #2236
- **Scenario**: Auto-pong responds to a ping, but the WebSocket closes between receiving the ping and sending the pong. This causes `IOException: Output closed` from Java's HttpClient.
- **Why it failed**: The default `pongOnPing` behavior doesn't check if the connection is still open before sending.
- **Fix**: Catch and ignore send failures when the WS is already closed.
- **Our coverage**: **NOT COVERED** -- no test for ping/pong behavior or for the race between close and control frame sending.
- **Recommendation**: If we support auto ping/pong, add a test where close arrives while a pong is being sent.

### BUG 10: Fragmented Frame Concatenation Broken
- **Source**: tapir #3339, zio-http #892, zio-http #3342 (open)
- **Scenario**: WebSocket text frames can be fragmented (non-final frames). The `concatenateFragmentedFrames` option in tapir was a complete no-op. In zio-http, calling `.text` on a non-final `TextWebSocketFrame` can produce corrupted UTF-8 because a multi-byte character might be split across frames.
- **Why it failed**: tapir: accumulator was always `None` so accumulation never happened. zio-http: the `.text` call decodes bytes immediately per-frame without waiting for the final frame.
- **Fix**: tapir: still open. zio-http: deferred to 4.0 redesign.
- **Our coverage**: **NOT COVERED** -- no tests for fragmented WebSocket frames.
- **Recommendation**: Add tests that verify behavior when receiving fragmented text and binary frames (continuation frames with FIN=0 followed by a final frame with FIN=1).

### BUG 11: Close Frame Handling -- Immediate Reply Terminates In-Flight Work
- **Source**: tapir #3776
- **Scenario**: When a `Close` frame arrives, Netty's `NettyControlFrameHandler` immediately responds with a `Close` frame and closes the channel. This terminates all in-flight message processing -- messages that were queued for processing but not yet handled are lost.
- **Why it failed**: The Close frame is handled at the Netty pipeline level, bypassing the application's message processing queue.
- **Fix**: Let Close frames pass through to the application layer so they're processed in order after all queued messages.
- **Our coverage**: **PARTIALLY COVERED** -- tests "close after exchange" and "client sends then immediately closes" test close behavior, but don't verify that in-flight messages are fully processed before close takes effect.
- **Recommendation**: Add a test where the client sends several messages followed by a close frame, and verify the server processes ALL messages before the close takes effect.

### BUG 12: WebSocket Doesn't Work with Request Streaming Enabled
- **Source**: zio-http #2977
- **Scenario**: When `enableRequestStreaming` server config is enabled, WebSocket upgrade fails with `PrematureChannelClosureException: Channel closed while still aggregating message`.
- **Why it failed**: Request streaming mode changes how Netty aggregates HTTP messages. The WebSocket upgrade handshake response gets caught in the aggregation pipeline instead of being passed through as a complete response.
- **Fix**: zio-http specific pipeline configuration issue.
- **Our coverage**: **NOT APPLICABLE** -- this is specific to a configuration mode we likely don't have.

### BUG 13: Flaky WebSocket Tests (CI Timing)
- **Source**: zio-http #1234
- **Scenario**: WebSocket server tests intermittently fail in CI but pass locally. The `nonFlaky` annotation wasn't sufficient.
- **Why it failed**: CI environments have higher latency and less predictable scheduling. Race conditions in test assertions (checking state too early) would surface under load.
- **Our coverage**: **AWARENESS** -- this is a test infrastructure concern rather than a bug.
- **Recommendation**: Ensure our WebSocket tests use proper synchronization (latches, promises) rather than sleeps or timing assumptions.

### BUG 14: Client Should Fail on Non-101 Response
- **Source**: zio-http #3464 (open)
- **Scenario**: When the server responds to a WebSocket upgrade request with "Bad Request" or any non-101 status, the client silently returns the response instead of raising an error. The WebSocket app is never launched, but there's no error signal.
- **Why it failed**: The client code checked for 101 to launch the WS app, but returned the non-101 response as a "success" instead of an error.
- **Fix**: Deferred to zio-http 4.0.
- **Our coverage**: **NOT EXPLICITLY TESTED** -- no test that connects to a non-WebSocket endpoint and verifies an error is raised.
- **Recommendation**: Add a test that attempts a WebSocket connection to a regular HTTP endpoint and verifies it fails with an appropriate error.

---

## Coverage Gap Summary

### Already Well-Covered in Our Tests
1. Basic text/binary echo (BUG 6)
2. Multiple concurrent connections (BUG 8)
3. Concurrent sends via channel design (BUG 4)
4. Close handshake basics
5. Server handler patterns
6. Sequential connection reuse

### Gaps Needing New Tests

| Priority | Gap | Related Bug(s) | Test Idea |
|----------|-----|----------------|-----------|
| **HIGH** | Fragmented frame handling | BUG 10 | Send continuation frames, verify correct reassembly |
| **HIGH** | Close frame vs in-flight messages | BUG 11 | Send N messages + close, verify all N processed |
| **HIGH** | Large Unicode payload | BUG 5 | Send 10K multi-byte Unicode chars |
| **HIGH** | WS upgrade to non-WS endpoint fails | BUG 14 | Connect WS to HTTP endpoint, expect error |
| **MEDIUM** | Close handler side-effects execute | BUG 2 | Server sets AtomicRef in cleanup, verify |
| **MEDIUM** | Explicit interruption/timeout of WS | BUG 3 | Timeout a long-running WS, verify clean exit |
| **MEDIUM** | Stress test: immediate server send | BUG 1 | 100 concurrent connects with immediate server send |
| **MEDIUM** | Ping/pong during close race | BUG 9 | Close while ping is in-flight |
| **LOW** | WS upgrade response headers to client | BUG 7 | Server sets custom header, client reads it |
| **LOW** | High-concurrency stress (50+ clients) | BUG 8 | Scale up existing concurrent test |

### Existing zio-http/sttp/tapir Test Scenarios We Should Consider Adopting

1. **zio-http**: "send waits for handshake to complete" -- explicit test that immediate server sends work
2. **zio-http**: "on close interruptibility" -- close handler can be interrupted and still completes
3. **zio-http**: "Multiple websocket upgrades" (1024 concurrent) -- stress test
4. **sttp**: "stress test with 1000 messages" -- high-volume message exchange
5. **sttp**: "send & receive messages concurrently" (32 concurrent) -- concurrent bidirectional
6. **sttp**: "failed connection" -- WS to non-existent endpoint
7. **sttp**: "response header reception" -- custom headers in upgrade response
8. **sttp**: "subprotocol header negotiation" -- Sec-WebSocket-Protocol
9. **tapir**: "pong on ping" -- auto ping/pong handling
10. **tapir**: "failing pipe" -- error in message transformation
11. **tapir**: "concatenate fragmented text frames" -- frame fragmentation
12. **tapir**: "receive a client-sent close frame as a None" -- close frame as semantic message
13. **tapir**: "reject WS handshake, then accept a corrected one" -- handshake rejection
14. **tapir**: "empty client stream" -- open WS but send nothing
15. **tapir**: "switch to WS after a normal HTTP request" -- protocol switching with pre-validation
