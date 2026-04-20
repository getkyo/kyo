# Analysis: Unix Socket Error Messages

## Problem
When a unix socket URL fails to connect, error messages show `localhost:80` because `url.host` defaults to `"localhost"` and `url.port` defaults to `80`. The actual socket path is in `url.unixSocket`.

## Subtasks

### Subtask 1: Add `hostPort` helper to HttpClientBackend
- Add private method that returns `(String, Int)` based on `url.unixSocket`
- For unix sockets: `("unix:/path/to/socket", 0)`
- For regular URLs: `(url.host, url.port)`

### Subtask 2: Update error sites in HttpClientBackend
Four sites need updating:
1. **Line 76** - `connect()` method: `HttpConnectException(url.host, url.port, ...)`
2. **Line 499** - `connectWebSocket()`: `HttpConnectTimeoutException(host, port, ...)`
3. **Line 501-505** - `connectWebSocket()`: `HttpConnectException(host, port, ...)`
4. **Line 746-751** - `poolWithImpl()`: `HttpPoolExhaustedException(url.host, url.port, ...)`

### Subtask 3: Add tests to HttpClientUnixTest
- Test 1: connect failure mentions socket path
- Test 2: connect failure does not say localhost
- Test 3: pool exhausted mentions socket path (may skip if unreliable)
- Test 4: timeout mentions socket path
