# Analysis: Convert JSON calls to checked variants

## Summary
Convert all `withErrorMapping(id) { HttpClient.getJson/postJson/deleteJson }` patterns to use status-checked variants.

## Two patterns:

### Simple (no query params) → use `getJsonChecked` / `postJsonChecked`
- waitForExit (line 262): postJson → postJsonChecked
- checkpoint (line 270): postJson → postJsonChecked
- inspect (line 286): getJson → getJsonChecked
- state (line 291): getJson → getJsonChecked
- exec create (line 353): postJson → postJsonChecked
- exec inspect (line 370): getJson → getJsonChecked
- execStream create (line 387): postJson → postJsonChecked
- networkCreate (line 1011): postJson → postJsonChecked
- networkInspect (line 1028): getJson → getJsonChecked
- networkConnect (line 1047): postJson → postJsonChecked
- networkDisconnect (line 1056): postJson → postJsonChecked
- volumeCreate (line 1083): postJson → postJsonChecked
- volumeInspect (line 1102): getJson → getJsonChecked

### Complex (with query params) → use `*JsonResponse` + `checkStatus`
- stats (line 296): getJson with stream param
- top (line 316): getJson with ps_args param
- changes (line 326): getJson (no params but already listed as complex)
- update (line 592): postJson with update body
- list containers (line 616): getJson with params
- prune containers (line 652): postJson with params
- imageEnsure (line 691): getJson with no params but inside Abort.run
- imagePullWithProgress (line 707): same as imageEnsure
- imageList (line 752): getJson with params
- imageInspect (line 779): getJson (no params, but listed as complex)
- imageRemove (line 802): deleteJson with params
- imageSearch (line 920): getJson with params
- imageHistory (line 937): getJson (no params)
- imagePrune (line 958): postJson with params
- imageCommit (line 981): postJson with params
- networkList (line 1022): getJson with params
- networkPrune (line 1067): postJson with params
- volumeList (line 1094): getJson with params
- volumePrune (line 1115): postJson with params

## Notes
- `create` method (line 216) already uses `postJsonResponse` — DO NOT TOUCH
- `changes` has no params but the task says complex, so do complex
- `imageInspect` has no params but user listed as complex
- `imageHistory` has no params but user listed as complex
- For imageEnsure/imagePullWithProgress: these use getJson inside Abort.run, so convert to getJsonResponse+checkStatus
