# Analysis: HttpClient Unit Methods

## Goal
Add `postUnit`, `putUnit`, `patchUnit`, `deleteUnit` convenience methods to HttpClient for fire-and-forget REST operations where the response body is irrelevant.

## Subtasks
1. Add unit methods section to HttpClient.scala after binary methods (line 579)
2. Compile
3. Add 7 tests to HttpClientTest.scala
4. Run tests

## Design
- Use existing `routePostText`, `routePutText`, `routePatchText`, `routeDeleteText` cached routes
- Call `sendUrlBody` which handles non-2xx status check automatically
- Discard body via `(_ => ())`
- `deleteUnit` has no body parameter (DELETE typically has no body)
- `postUnit`, `putUnit`, `patchUnit` have `body: String = ""` default

## Test Plan
- postUnit succeeds on 200
- postUnit succeeds on 204
- postUnit fails on 404
- deleteUnit succeeds on 200
- deleteUnit fails on 500
- putUnit succeeds on 200
- patchUnit succeeds on 200
