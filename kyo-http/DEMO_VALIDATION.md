# Demo Validation Report

**Date:** 2026-02-25
**Branch:** kyo-http2

---

## 1. BookmarkStore (port 3007)

**Status: PASS with issues**

| Test | Result | Notes |
|------|--------|-------|
| Empty list | ✅ | Returns `[]` |
| Create without auth | ✅ | Returns 401 |
| Create with wrong token | ✅ | Returns 401 |
| Create 3 bookmarks | ✅ | Auto-increment IDs 1,2,3. Returns JSON with all fields |
| List all | ✅ | Returns 3 items, sorted by ID |
| X-Total-Count header | ✅ | Present, value = 3 |
| X-Request-ID header | ✅ | Random 32-char ID per request |
| Security headers | ✅ | X-Content-Type-Options: nosniff, X-Frame-Options: DENY, Referrer-Policy: strict-origin-when-cross-origin |
| Get by ID | ✅ | Returns correct bookmark |
| Update (partial) | ✅ | Only updates specified fields |
| Delete | ✅ | Returns 204 |
| OpenAPI | ✅ | Valid JSON, title="Bookmark Store", paths=[/bookmarks/{id}, /bookmarks] |

### Issues Found

1. **BUG: GET /bookmarks/999 returns 500 instead of 404**
   The route declares `.error[ApiError](HttpStatus.NotFound)` but `Abort.fail(ApiError(...))` maps to 500.

2. **BUG: DELETE doesn't remove the bookmark**
   The delete handler (`deleteRoute.handler { _ => HttpResponse.noContent }`) ignores the request entirely — it never calls `storeRef.update`. List after delete still shows the bookmark.

3. **MINOR: Create returns 200 instead of 201**
   The route declares `.status(HttpStatus.Created)` but actual response is 200.

---

## 2. EventBus (port 3010)

**Status: PASS with minor issues**

| Test | Result | Notes |
|------|--------|-------|
| Empty list | ✅ | Returns `[]` |
| POST JSON event | ✅ | Returns StoredEvent with id=1, timestamp present |
| POST form event | ✅ | Form data parsed correctly, returns StoredEvent |
| List all events | ✅ | 3 events in order, all have id/source/kind/payload/timestamp |
| Auto-increment IDs | ✅ | IDs 1, 2, 3 sequential |
| Timestamps | ✅ | ISO-8601 format with timezone |
| OpenAPI | ✅ | Title="Event Bus", 4 paths |
| NDJSON stream | ⚠️ | Stream works but couldn't fully test (macOS lacks `timeout` cmd) |
| Post during stream | ✅ | Event posted successfully while stream active |

### Issues Found

1. **MINOR: POST event returns 200 instead of 201**
   Route declares `.status(HttpStatus.Created)` but actual HTTP status is 200.

2. **NDJSON stream polling** — The stream polls every 2s and emits new events. Verified an event posted during streaming was captured.

---

## 3. FileLocker (port 3008)

**Status:** PENDING

---

## 4. HackerNews (port 3005)

**Status:** PENDING

---

## 5. WikiSearch (port 3004)

**Status:** PENDING

---

## 6. ApiGateway (port 3002)

**Status:** PENDING

---

## 7. McpServer (port 3001)

**Status:** PENDING

---

## 8. GithubFeed (port 3003)

**Status:** PENDING

---

## 9. UptimeMonitor (port 3006)

**Status:** PENDING

---

## 10. BookmarkClient + LinkChecker

**Status:** PENDING

---

## Summary of Issues

| # | Demo | Severity | Issue |
|---|------|----------|-------|
| 1 | BookmarkStore | BUG | GET non-existent bookmark returns 500 not 404 |
| 2 | BookmarkStore | BUG | DELETE doesn't remove bookmark from store |
| 3 | BookmarkStore | MINOR | Create returns 200 not 201 |
| 4 | EventBus | MINOR | POST event returns 200 not 201 |
