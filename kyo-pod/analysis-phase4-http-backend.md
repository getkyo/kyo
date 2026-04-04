# Phase 4: HttpContainerBackend — File ops, Attach, Image ops

## Methods to implement

### File Operations (4 methods)
1. **copyTo** — Use Command("tar") to create tar, then PUT /containers/{id}/archive?path=...
2. **copyFrom** — GET /containers/{id}/archive?path=..., pipe response to Command("tar") for extraction
3. **stat** — HEAD /containers/{id}/archive?path=..., parse X-Docker-Container-Path-Stat header (base64 JSON)
4. **exportFs** — GET /containers/{id}/export, stream raw bytes

### Attach (1 method in backend)
5. **attach** — NotSupported (bidirectional HTTP upgrade)

### Image Operations (13 methods)
6. **imagePull** — POST /images/create?fromImage=...&tag=... with auth header; wait for NDJSON completion
7. **imageEnsure** — GET /images/{ref}/json first; pull if 404
8. **imagePullWithProgress** — Same as pull but stream PullProgress from NDJSON
9. **imageList** — GET /images/json?all=...&filters=...; parse JSON array
10. **imageInspect** — GET /images/{ref}/json; parse to ContainerImage.Info
11. **imageRemove** — DELETE /images/{ref}?force=...&noprune=...; parse delete response array
12. **imageTag** — POST /images/{ref}/tag?repo=...&tag=...; 204 success
13. **imageBuild** — NotSupported (stream-based context)
14. **imageBuildFromPath** — Command("tar") + POST /build?...
15. **imagePush** — POST /images/{ref}/push with auth header
16. **imageSearch** — GET /images/search?term=...&limit=...
17. **imageHistory** — GET /images/{ref}/history
18. **imagePrune** — POST /images/prune?filters=...
19. **imageCommit** — POST /commit?container=...&repo=...&tag=...

### Registry Auth (1 method)
20. **registryAuthFromConfig** — Copy ShellBackend logic: read ~/.docker/config.json

## DTOs needed
- FileStatDto (for base64 JSON in X-Docker-Container-Path-Stat header)
- ImageInspectDto (for GET /images/{ref}/json)
- ImageListDto (for GET /images/json)
- ImageDeleteDto (for DELETE /images/{ref})
- ImageSearchDto (for GET /images/search)
- ImageHistoryDto (for GET /images/{ref}/history)
- ImagePruneDto (for POST /images/prune)
- ImageCommitDto (for POST /commit)
- PullProgressDto (for NDJSON pull progress)
- BuildProgressDto (for NDJSON build progress)

## Implementation order
1. DTOs (small JSON case classes)
2. registryAuthFromConfig (copy from ShellBackend)
3. attach (NotSupported)
4. File operations
5. Image operations
