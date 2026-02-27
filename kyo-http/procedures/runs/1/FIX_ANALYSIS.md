# Fix Analysis — Run 1

## Root Causes

### Root Cause 1: Client response decoding ignores HTTP status code (Bug #2)

**Affected tests:** All 4 "convenience method error responses" tests
**Platforms:** JVM, JS, Native (all)
**Location:** `RouteUtil.decodeBufferedResponse` — decodes response body using the route's success type schema regardless of status code. When the server returns a non-2xx response with a typed error body, the client tries to parse it as the success type and gets a ParseError.

**Fix approach:** In `RouteUtil.decodeBufferedResponse`, when the status code is non-2xx, either:
- Skip body decoding and return the raw response with status, OR
- Return an HttpError that includes the status code and raw body

The convenience methods use routes like `HttpRoute.getJson[A]("")` which have no error mappings, so error type routing isn't available. The fix should make the HttpError accessible with status code and body content.

### Demo fixes (no tests)

**Demo Bug #4 (BookmarkClient):** Server started with auth-protected routes; client can't send auth headers. Fix: Create no-auth handlers in BookmarkClient.

**Demo Bug #5 (ApiGateway):** `fetchWeather` uses `HttpError.ConnectionError` for unknown cities. Fix: Use `ApiError` to match the route's error mapping.
