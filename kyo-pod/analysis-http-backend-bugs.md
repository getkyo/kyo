# HTTP Backend Bug Analysis

## Bug 1: Duplicate mount point (HTTP 400)
**Location**: `create` method, lines 147-150, 188
**Problem**: `mountsList` collects volume mounts for the `Mounts` field, but `binds` already includes them. Docker rejects duplicate.
**Fix**: Remove `mountsList` variable and set `Mounts = Seq.empty` in HostConfig.

## Bug 2: Copy operations return NotFound
**Location**: `copyTo` (line 489), `copyFrom` (line 517)
**Problem**: The `path` query parameter value is already URL-encoded by the `url()` method via `URLEncoder.encode`. But `containerPath.toString` might produce a path like `/tmp/test.txt` which gets double-encoded or the container path might need to be the directory, not the file.
**Actual issue**: Looking more carefully, the `url()` method does encode query params. The `copyTo` passes `containerPath` as the destination directory. The `copyFrom` also passes `containerPath`. These look correct for Docker API. The NotFound might be because `putBinary` and `getBinary` throw HttpStatusException that gets mapped. Need to check if the container is running when copy is called. Actually, the URL construction looks fine. Let me look for other issues... The `containerPath.toString` should produce a valid path string. This seems likely correct.
**Revisit**: After fixing bugs 1, 3, 4, 5 - re-check if copy issues persist.

## Bug 3: Network connect returns General
**Location**: `networkConnect` method, line 1064-1065
**Problem**: `postJson[EmptyResponse]` tries to decode the response body as JSON. Docker's `POST /networks/{id}/connect` returns 200 with an empty body (not `{}`). JSON decode of empty string fails, causing a non-HttpStatusException error that maps to General.
**Fix**: Use `postText` instead of `postJson[EmptyResponse]`, since we don't need the response body.

## Bug 4: Update returns "HTTP request failed"
**Location**: `update` method, line 607
**Problem**: `postJson[UpdateResponse]` should work. Docker's update endpoint is POST and returns JSON with Warnings. The error "HTTP request failed" suggests a non-HttpStatusException. Need to check if the URL is correct. The URL `/containers/{id}/update` with POST is correct per Docker API.
**Possible issue**: The UpdateRequest sends all zero values for fields not being updated. Docker might reject 0 for Memory/MemorySwap. But that should give a 400, not "HTTP request failed".
**Alternative**: The `UpdateResponse` expects `Warnings` field but Docker might return `{"Warnings":null}` vs `{"Warnings":[]}`. Actually with `Option[Seq[String]]` that should be fine, but it uses `Seq[String]` (not Option). Let me check... Line 1523: `Warnings: Seq[String] = Seq.empty`. Docker returns `{"Warnings":null}`. A `null` for `Seq[String]` might fail deserialization.
**Fix**: Change `UpdateResponse.Warnings` to `Option[Seq[String]]`.

## Bug 5: Image pull progress streaming
**Location**: `imagePullWithProgress`, line 742-744
**Problem**: `postText` fails with HttpStatusException on non-2xx. But Docker image pull returns 200 with NDJSON streaming body. The issue is `postText` succeeds. The "HTTP request failed" error is a General error from `mapHttpError` with `case other =>` branch. This means a non-HttpStatusException is thrown. The problem is likely that `postText` returns the full streamed response and something in the JSON parsing fails, which wouldn't be an HttpException at all...
**Wait**: Re-reading the bug description: "HTTP request failed for nginx:latest". That's the message from `mapHttpError` case `other => ContainerException.General(s"HTTP request failed for $id", other)`. This is the catch-all for non-HttpStatusException HttpExceptions. This might be a connection/timeout issue during the pull. But it could also be that the `postText` call itself fails because the response is chunked/streaming and the HTTP client can't handle it as a simple text response.
**Fix**: Use `postTextResponse` with `failOnError = false` to get the raw response and handle status manually, similar to how `postUnitAccept304` works.

## Bug 6: Secondary issues - defer
