# Analysis: Add failOnError parameter to Response methods

## Summary
Add `failOnError: Boolean = true` parameter to all 15 `*Response` methods plus `head` and `options` in HttpClient.scala. When true, use `sendUrlBody` (checks status). When false, use `sendUrl` (no check).

## Methods to change (17 total)

### JSON Response methods (5):
1. `getJsonResponse` (line 157) - no body param, uses `HttpRoute.getJson[A]("")`
2. `postJsonResponse` (line 180) - has body param, uses `HttpRoute.postJson[A, B]("")`
3. `putJsonResponse` (line 206) - has body param, uses `HttpRoute.putJson[A, B]("")`
4. `patchJsonResponse` (line 232) - has body param, uses `HttpRoute.patchJson[A, B]("")`
5. `deleteJsonResponse` (line 255) - no body param, uses `HttpRoute.deleteJson[A]("")`

### Text Response methods (5):
6. `getTextResponse` (line 275) - no body param, uses `routeGetText`
7. `postTextResponse` (line 294) - has body param, uses `routePostText`
8. `putTextResponse` (line 316) - has body param, uses `routePutText`
9. `patchTextResponse` (line 338) - has body param, uses `routePatchText`
10. `deleteTextResponse` (line 359) - no body param, uses `routeDeleteText`

### Binary Response methods (5):
11. `getBinaryResponse` (line 379) - no body param, uses `routeGetBinary`
12. `postBinaryResponse` (line 398) - has body param, uses `routePostBinary`
13. `putBinaryResponse` (line 420) - has body param, uses `routePutBinary`
14. `patchBinaryResponse` (line 442) - has body param, uses `routePatchBinary`
15. `deleteBinaryResponse` (line 463) - no body param, uses `routeDeleteBinary`

### HEAD and OPTIONS (2):
16. `head` (line 522) - no body param, uses `routeHeadRaw`
17. `options` (line 531) - no body param, uses `routeOptionsRaw`

## Pattern

For methods without body (GET, DELETE, HEAD, OPTIONS):
- Extract route into val
- if failOnError then sendUrlBody(u, route, headers, query)(identity)
- else sendUrl(u, route, headers, query)(identity)

For methods with body (POST, PUT, PATCH):
- Extract route into val
- if failOnError then sendUrlBody(u, route, body, headers, query)(identity)
- else sendUrl(u, route, body, headers, query)(identity)

For JSON methods that construct routes inline:
- Extract to val so we don't duplicate the construction

## Test changes
- Tests 8-11 (Response methods returning non-2xx without throwing): Change to expect throw
- Add 4 new tests for failOnError=false
- Update test 12 (differ test) to test failOnError=false difference
