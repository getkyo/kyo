# Stream-First Transport — Execution Plan

## Overview

8 implementation phases matching the design doc. Each phase has:
- Implementation files to produce
- Test files to produce
- Verification commands
- Checklist items

Files to read for context (all agents): `kyo-http/transport-design-c.md`

---

## Phase 1: ByteStream + TransportStream2

**Reads**: `kyo-http/transport-design-c.md` (Phase 1 section)
**Produces**:
- `kyo-http/shared/src/main/scala/kyo/internal/TransportStream2.scala`
- `kyo-http/shared/src/main/scala/kyo/internal/ByteStream.scala`
- `kyo-http/shared/src/test/scala/kyo/internal/ByteStreamTest.scala`

**Verify**: `sbt 'kyo-http/testOnly kyo.internal.ByteStreamTest'`

**Checklist**:
- [ ] TransportStream2 trait with `read: Stream[Span[Byte], Async]` and `write(Span[Byte]): Unit < Async`
- [ ] Transport2 trait with connect/listen/isAlive/closeNow/close
- [ ] TransportListener2 class with port, host, connections stream
- [ ] TlsConfig parameter on connect/listen (Maybe[TlsConfig])
- [ ] ByteStream object with readUntil, readExact, readLine, indexOf
- [ ] All return `(result, remainingStream)` tuples
- [ ] 30 tests passing

**Tests (30)**:
1. readUntil: delimiter in single span
2. readUntil: delimiter spans two spans
3. readUntil: delimiter at start
4. readUntil: delimiter at end
5. readUntil: multiple delimiters stops at first
6. readUntil: no delimiter, stream ends → Abort
7. readUntil: exceeds maxSize → Abort
8. readUntil: empty stream → Abort
9. readUntil: single-byte spans fragmentation
10. readUntil: 1-byte delimiter
11. readUntil: data equals delimiter
12. readUntil: large data 100KB in 8KB spans
13. readUntil: remaining stream consumable
14. readExact: exact fit
15. readExact: span larger than n
16. readExact: multiple spans needed
17. readExact: n = 0
18. readExact: stream ends before n → Abort
19. readExact: n = 1
20. readExact: large n across many spans
21. readExact: remaining consumable
22. readLine: simple line
23. readLine: two lines
24. readLine: sequential calls
25. readLine: empty line
26. readLine: exceeds maxSize → Abort
27. readLine: no CRLF → Abort
28. composition: readUntil then readExact
29. composition: readLine chain then readExact
30. composition: readUntil remaining delivers subsequent data

---

## Phase 2: Http1Protocol2 + StreamTestTransport (tests 1-20)

**Reads**: `kyo-http/transport-design-c.md` (Phase 2 section), Phase 1 files
**Produces**:
- `kyo-http/shared/src/main/scala/kyo/internal/Http1Protocol2.scala`
- `kyo-http/shared/src/test/scala/kyo/internal/StreamTestTransport.scala`
- `kyo-http/shared/src/test/scala/kyo/internal/Http1Protocol2Test.scala` (tests 1-20)

**Verify**: `sbt 'kyo-http/testOnly kyo.internal.Http1Protocol2Test'`

**Checklist**:
- [ ] Http1Protocol2 object with readRequest, readResponse, writeRequest, writeResponse
- [ ] All read functions return `(result, remainingStream)` tuples
- [ ] Uses ByteStream for all parsing
- [ ] StreamTestTransport implements Transport2 using Channels
- [ ] 20 tests passing

**Tests (20)**:
1. GET no body → HttpBody.Empty
2. POST Content-Length → HttpBody.Buffered
3. POST chunked → HttpBody.Buffered reassembled
4. PUT binary 0x00-0xFF → no corruption
5. All standard methods roundtrip
6. Query string preserved
7. Multiple same-name headers preserved
8. Empty header value
9. Large headers under MaxHeaderSize
10. Headers exceeding MaxHeaderSize → Abort
11. Unicode header values preserved
12. Custom headers roundtrip
13. 200 OK with body
14. 204 No Content → no body
15. 304 Not Modified → no body
16. HEAD with Content-Length → no body
17. 1xx → no body
18. Chunked response reassembled
19. Multi-chunk (3) concatenation
20. Chunk extension ignored

---

## Phase 3: Http1Protocol2 tests 21-35

**Reads**: Phase 2 files
**Produces**: Appends to `Http1Protocol2Test.scala` (tests 21-35)

**Verify**: `sbt 'kyo-http/testOnly kyo.internal.Http1Protocol2Test'`

**Tests (15)**:
21. Empty chunked body
22. 3 GETs on same stream (keep-alive)
23. POST then GET same stream (no byte leak)
24. Connection: close → isKeepAlive false
25. Default → isKeepAlive true
26. writeStreamingBody 3 spans → chunked
27. Empty spans filtered
28. Last chunk 0\r\n\r\n appended
29. writeResponseHead format correct
30. Malformed request line → Abort
31. Malformed status line → Abort
32. Invalid Content-Length → Abort
33. Content-Length exceeds maxSize → Abort
34. Connection closed mid-headers → Abort
35. Connection closed mid-body → Abort

---

## Phase 4: Native Transport (kqueue)

**Reads**: `kyo-http/transport-design-c.md` (Phase 3), Phase 1 files, existing native files
**Produces**:
- `kyo-http/native/src/main/scala/kyo/internal/EventLoop.scala`
- `kyo-http/native/src/main/scala/kyo/internal/KqueueNativeTransport2.scala`
- `kyo-http/native/src/test/scala/kyo/internal/KqueueTransport2Test.scala`

**Verify**: `sbt 'kyo-httpNative/testOnly kyo.internal.KqueueTransport2Test'`

**Checklist**:
- [ ] EventLoop with shared kqueue fd, @blocking poll, Promise dispatch
- [ ] EventLoopGroup with round-robin assignment
- [ ] KqueueNativeTransport2 implements Transport2
- [ ] Connection extends TransportStream2 (read returns Stream)
- [ ] Uses existing PosixBindings and kyo_tcp.c
- [ ] 25 tests passing

**Tests (25)**:
1. Connect → isAlive true
2. Connect non-existent port → Abort
3. closeNow → isAlive false
4. Double closeNow idempotent
5. Server writes, client reads
6. Client writes, server reads
7. Write empty span
8. 1MB write all arrives
9. 10MB in 1KB writes ordered
10. Read after peer close → EOF
11. Write after peer close → error
12. read returns reusable Stream
13. Multiple pulls sequential
14. Stream ends on EOF
15. 50 concurrent connections
16. Each connection independent
17. Different event loops no interference
18. Listen port 0 → assigned port
19. connections stream yields accepted
20. Scope exit → server closed
21. Accepted connection is TransportStream2
22. Handler exception → server continues
23. Fast writer slow reader → backpressure
24. Reader resumes → writer unblocks
25. Scope exit → all fds closed

---

## Phase 5: HTTP/1.1 over Native TCP

**Reads**: `kyo-http/transport-design-c.md` (Phase 4), Phase 1-4 files
**Produces**:
- `kyo-http/native/src/test/scala/kyo/internal/Http1NativeTest.scala`

**Verify**: `sbt 'kyo-httpNative/testOnly kyo.internal.Http1NativeTest'`

**Tests (15)**:
1. GET 200 with body
2. POST 1KB JSON
3. PUT binary no corruption
4. HEAD no body
5. 5 sequential requests keep-alive
6. Stream threads correctly
7. Server chunked → client reassembles
8. Client chunked → server reassembles
9. 10 clients × 5 requests
10. No cross-contamination
11. Client disconnects mid-request → server aborts
12. Server disconnects mid-response → client aborts
13. Malformed response → protocol error
14. 1MB body complete
15. Body exceeds maxSize → Abort

---

## Phase 6: Http1Exchange

**Reads**: `kyo-http/transport-design-c.md` (Phase 5), `kyo-core/.../Exchange.scala`, Phase 1-2 files
**Produces**:
- `kyo-http/shared/src/main/scala/kyo/internal/Http1Exchange.scala`
- `kyo-http/shared/src/test/scala/kyo/internal/Http1ExchangeTest.scala`

**Verify**: `sbt 'kyo-http/testOnly kyo.internal.Http1ExchangeTest'`

**Checklist**:
- [ ] Uses Exchange.init with encode/send/receive/decode
- [ ] Channel(1) for write-then-signal coordination
- [ ] No AllowUnsafe
- [ ] 15 tests passing

**Tests (15)**:
1. exchange(GET /hello) → 200 OK
2. exchange(POST /submit) with body → response
3. Sequential: req1 then req2 both correct
4. exchange.close → pending fails with Closed
5. exchange.awaitDone suspends until close
6. Server closes → awaitDone completes
7. Server malformed response → HttpException
8. Connection drops → pending fails
9. After failure, awaitDone raises
10. Write-then-signal: bytes written before response read
11. Reader blocks on inflight.take until request sent
12. 5 sequential requests all correct
13. Large response body
14. Empty response body
15. exchange after close → Abort[Closed]

---

## Phase 7: Server + Client Backends

**Reads**: `kyo-http/transport-design-c.md` (Phase 6), all prior phase files, existing `HttpTransportServer.scala`, `HttpTransportClient.scala`
**Produces**:
- `kyo-http/shared/src/main/scala/kyo/internal/HttpTransportServer2.scala`
- `kyo-http/shared/src/main/scala/kyo/internal/HttpTransportClient2.scala`

**Verify**: `sbt 'kyo-http/compile'` (shared tests run on all platforms later)

**Checklist**:
- [ ] HttpTransportServer2 uses Transport2 + Http1Protocol2
- [ ] HttpTransportClient2 uses Exchange for request dispatch
- [ ] Connection type wraps transport.Connection + Exchange
- [ ] onRelease callback for pool integration
- [ ] No AllowUnsafe

---

## Phase 8: NioTransport2 (JVM)

**Reads**: `kyo-http/transport-design-c.md` (Phase 7), Phase 1 files, existing `NioTransport.scala`
**Produces**:
- `kyo-http/jvm/src/main/scala/kyo/internal/NioTransport2.scala`
- `kyo-http/jvm/src/test/scala/kyo/internal/NioTransport2Test.scala`

**Verify**: `sbt 'kyo-httpJVM/testOnly kyo.internal.NioTransport2Test'`

**Tests (15)**:
1-14: Same as Phase 4 transport tests adapted for NIO
15. Selector.wakeup on new registrations

---

## Phase 9: Integration + Flip Backends

**Reads**: All files
**Produces**: Updated `HttpPlatformBackend.scala` (native + JVM) to point to new backends

**Verify**: `sbt 'kyo-httpJVM/test'` and `sbt 'kyo-httpNative/test'`

**Checklist**:
- [ ] All existing tests pass with new backends
- [ ] No regressions

---

## Phase 10: Delete Old Backends

**Produces**: Deletions of old Transport.scala, Protocol.scala, Http1Protocol.scala, etc. Rename `*2` files.

**Verify**: Full test suite all platforms

---

## Phase 11: Audit

Audit agent reads analysis.md, all implementation files, all test files.
Counts tests against numbered lists. Reports deviations.

---

## Phase 12: Final Green Run

`sbt 'kyo-http/test'` on all platforms. Zero failures.
