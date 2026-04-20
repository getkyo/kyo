# Analysis: getStreamBytes and postStreamBytes

## Plan

### 1. Add methods to HttpClient.scala (line ~627, after NDJSON section)

Add `getStreamBytes` and `postStreamBytes` following the same pattern as `getSseJson`, `getSseText`, `getNdJson`.

Key observations:
- The existing streaming methods use `sendUrlBody` which checks for non-2xx status before streaming
- Route uses `HttpRoute.getRaw("").response(_.bodyStream)` for GET
- Route uses `HttpRoute.postRaw("").request(_.bodyBinary).response(_.bodyStream)` for POST
- Return type: `Stream[Span[Byte], Async & Abort[HttpException]]`
- Need `Tag[Emit[Chunk[Span[Byte]]]]` using context bound

### 2. Add cached routes

Add `routeGetStream` and `routePostStream` to the cached routes section (like `routeSseText`).

### 3. Add tests to HttpClientTest.scala

Add a "stream bytes" section inside "convenience methods" with 4 tests:
1. `getStreamBytes returns chunks` - server returns text, verify stream has data
2. `getStreamBytes completes when server closes` - verify all data received
3. `getStreamBytes fails on non-2xx` - server returns 500, verify HttpStatusException
4. `postStreamBytes sends body and streams response` - echo test

### Types

- `bodyStream` in ResponseDef returns `ResponseDef[Out & "body" ~ Stream[Span[Byte], Async]]`
- The body field type is `Stream[Span[Byte], Async]`
- `sendUrlBody` extracts `_.fields.body` which gives `Stream[Span[Byte], Async]`
- Then `.map(_.emit)` emits the stream for the outer `Stream(...)` wrapper
