# Stream-First Transport — Analysis

## Problem Statement

Replace the existing Transport/Protocol/Http1Protocol stack with a stream-first architecture:
- Pull-based `Stream[Span[Byte], Async]` reads (vs current `read(buf): Int`)
- Pure `ByteStream` functions threading `(result, remainingStream)` (vs current mutable BufferedStream)
- `Exchange`-based client (vs current manual send/ensure lifecycle)
- Shared event loop groups (vs current per-connection event fd)

## Design Document

Full design with API signatures, implementation code, and test plans:
`kyo-http/transport-design-c.md`

## Effect Rationale

| Callback/Method | Effect | Why |
|----------------|--------|-----|
| ByteStream.readUntil/readExact/readLine | `Async & Abort[HttpException]` | Reads from stream (fiber parking), can fail on EOF/overflow |
| TransportStream2.read | `Stream[Span[Byte], Async]` | Pull-based, suspends fiber on each pull |
| TransportStream2.write | `Async` | Writes to socket, may suspend on backpressure |
| Transport2.connect | `Async & Abort[HttpException]` | Network I/O + can fail |
| Transport2.listen | `Async & Scope` | Creates server socket resource |
| Transport2.isAlive | `Sync` | Quick fd check, no I/O |
| Transport2.closeNow | `Sync` | Immediate fd close |
| Transport2.close | `Async` | Graceful shutdown with drain |
| Http1Protocol2 read/write | `Async & Abort[HttpException]` | Protocol I/O over stream |
| Exchange.init encode | pure | Just wraps request in wire type |
| Exchange.init send | `Async` | Writes to transport stream |
| Exchange.init receive | `Stream[Chunk[Wire], Async]` | Reads responses from transport |
| Exchange.init decode | pure | Extracts response from wire type |
| Http1Exchange inflight channel | `Sync` | Channel put/take for serialization |

## Error Semantics

| Cause | What fails | With what |
|-------|-----------|-----------|
| Connection closed during read | ByteStream operation | `Abort[HttpConnectionClosedException]` |
| Data exceeds maxSize | ByteStream operation | `Abort[HttpProtocolException]` or `Abort[HttpPayloadTooLargeException]` |
| Malformed HTTP | Protocol parse | `Abort[HttpProtocolException]` |
| DNS/connect failure | Transport.connect | `Abort[HttpConnectException]` |
| Server sends bad response | Exchange request | `Abort[HttpException]` |
| Exchange closed | Pending requests | `Abort[Closed]` |
| Connect timeout | connectWith | `Abort[HttpTimeoutException]` |

## Resource Safety

| Resource | Acquisition | Cleanup |
|----------|------------|---------|
| Transport connection | `transport.connect(...)` | `Sync.ensure` in handler / pool eviction |
| Server socket | `transport.listen(...)` | `Scope` finalization |
| Accept loop fiber | Inside listen | Interrupted on Scope exit |
| Exchange | `Exchange.init(...)` | `Scope` finalization (interrupts reader/sender) |
| Inflight channel | `Channel.init(1)` | Closed when Exchange closes |

## Test Plan

See each phase in `execution-plan.md` for numbered test lists.
Total: ~164 new tests across 8 design phases.
