# Analysis: connectRaw Implementation (Phase 1)

## Subtasks

1. **Create `HttpRawConnection.scala`** — New public API file with `final class`, `private[kyo]` constructor, `read` and `write` fields.

2. **Add `connectRaw` to `HttpClientBackend.scala`** — Safe method after `connectWebSocket` (line ~515). Follows connectWebSocket pattern:
   - Connect via transport.connect / connectUnix
   - Timeout handling
   - Closed/Timeout error mapping
   - Create Http1ClientConnection, send HTTP request via `sendDirect`
   - Read response promise, validate status (101 or 2xx)
   - Handle `lastBodySpan` for initial bytes
   - Create `ConnectionBackedStream` for read/write
   - Register `Scope.ensure` for cleanup
   - Return `HttpRawConnection`

3. **Add `connectRaw` extension on `HttpClient`** — After WebSocket methods section (line ~767), before internal helpers.

4. **Add tests to `HttpClientTest.scala`** — New `"connectRaw"` section at the end.

## Key Differences from connectWebSocket

- `connectRaw` does NOT use WebSocketCodec — raw bytes flow directly
- `connectRaw` accepts `method`, `body`, `headers` params (not just URL+headers+config)
- `connectRaw` validates status code (2xx or 101), not just 101
- `connectRaw` returns `HttpRawConnection` (read stream + write fn), not runs a handler function
- `connectRaw` uses `Scope` for lifecycle (returns connection), not runs-then-cleans-up

## Implementation Order

1. HttpRawConnection.scala (new file)
2. HttpClientBackend.scala (add connectRaw method)  
3. HttpClient.scala (add public extension)
4. HttpClientTest.scala (add tests)
5. Compile and test
