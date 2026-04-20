# kyo-http Usability Improvements -- Progress Tracker

## Completed Work (not tracked in phases below)

- **Status check behavior**: `sendUrlBody`/`sendUrl` split implemented and tested (12 tests, 2097 total passing)
- **`failOnError` parameter**: `*Response` methods getting `failOnError: Boolean = true` parameter (agent in progress)

## Phase Status

| Phase | Description | Status | Tests | Notes |
|-------|-------------|--------|-------|-------|
| 1 | Fix HttpStatusException message + tests | NOT STARTED | 1-2 (2 tests) | Fix 1 |
| 2 | Fix unix socket error messages + tests | NOT STARTED | 3-6 (4 tests) | Fix 2 |
| 3 | Streaming convenience methods + tests | NOT STARTED | 7-10 (4 tests) | Fix 3 |
| 4 | Unit convenience methods + tests | NOT STARTED | 11-17 (7 tests) | Fix 4 |
| 5 | Documentation (README, scaladocs) | NOT STARTED | -- | Docs |
| 6 | Final validation | NOT STARTED | All ~17 pass | Full suite + cross-compile |

## Removed from Plan

- **HttpStatusException body capture** (old tests 9-12, old Fix 1): Security risk -- response bodies can leak tokens, PII, internal server details into logs. kyo-http already strips query params from URLs for the same reason.
- **Status check verification tests** (old Phase 5, old tests 1-8): Already done as part of the `sendUrlBody`/`sendUrl` split.

## Test Inventory (~17 tests)

### HttpStatusException message tests (Phase 1) -- HttpClientTest.scala
- [ ] 1. `HttpStatusException message does not mention route`
- [ ] 2. `HttpStatusException message includes status code and name`

### Unix socket error tests (Phase 2) -- HttpClientUnixTest.scala
- [ ] 3. `unix socket connect failure mentions socket path`
- [ ] 4. `unix socket connect failure does not say localhost:80`
- [ ] 5. `unix socket pool exhausted mentions socket path`
- [ ] 6. `unix socket timeout mentions socket path`

### Streaming convenience method tests (Phase 3) -- HttpClientTest.scala
- [ ] 7. `getStreamBytes returns chunks`
- [ ] 8. `getStreamBytes completes when server closes`
- [ ] 9. `getStreamBytes fails on non-2xx`
- [ ] 10. `postStreamBytes sends body and streams response`

### Unit convenience method tests (Phase 4) -- HttpClientTest.scala
- [ ] 11. `postUnit succeeds on 200`
- [ ] 12. `postUnit succeeds on 204`
- [ ] 13. `postUnit fails on 404`
- [ ] 14. `deleteUnit succeeds on 200`
- [ ] 15. `deleteUnit fails on 500`
- [ ] 16. `putUnit succeeds on 200`
- [ ] 17. `patchUnit succeeds on 200`

## Production Files Modified

| File | Phase(s) | Change Summary |
|------|----------|----------------|
| `HttpException.scala` | 1 | Fix message (remove "route's expected type" line) |
| `HttpClient.scala` | 3, 4 | Add `getStreamBytes`, `postStreamBytes`, `postUnit`, `deleteUnit`, `putUnit`, `patchUnit` |
| `HttpClientBackend.scala` | 2 | Add `targetInfo(url)` helper; use at 4 error sites |

## Verification Commands

```bash
# Per-phase targeted tests
sbt 'kyo-http/testOnly kyo.HttpClientTest -- -z "HttpStatusException message"'
sbt 'kyo-http/testOnly kyo.HttpClientUnixTest -- -z "unix socket error"'
sbt 'kyo-http/testOnly kyo.HttpClientTest -- -z "stream bytes"'
sbt 'kyo-http/testOnly kyo.HttpClientTest -- -z "unit methods"'

# Full suite
sbt 'kyo-http/test'

# Cross-compile
sbt 'kyo-httpJS/compile'
sbt 'kyo-httpNative/compile'
```
