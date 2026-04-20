# Analysis: Revert checkStatus workaround

## Context
kyo-http now throws `HttpStatusException` on non-2xx from convenience methods (`getJson`, `postJson`, etc.), making the `checkStatus` workaround unnecessary.

## Subtasks

All edits are in `HttpContainerBackend.scala`.

### 1. JSON pattern: `getJsonResponse` + `checkStatus` -> `getJson`
Sites (line numbers):
- L271-276: `stats` — `getJsonResponse[StatsResponse]` -> `getJson[StatsResponse]`
- L293-297: `top` — `getJsonResponse[TopResponse]` -> `getJson[TopResponse]`
- L305-309: `changes` — `getJsonResponse[Seq[ChangeEntryDto]]` -> `getJson[Seq[ChangeEntryDto]]`
- L597-600: `list` — `getJsonResponse[Seq[ListContainerEntry]]` -> `getJson[Seq[ListContainerEntry]]`
- L676-679: `imageEnsure` (first check) — `getJsonResponse[ImageInspectDto]` -> `getJson[ImageInspectDto]`
- L694-697: `imagePullWithProgress` (check) — `getJsonResponse[ImageInspectDto]` -> `getJson[ImageInspectDto]`
- L741-744: `imageList` — `getJsonResponse[Seq[ImageListEntryDto]]` -> `getJson[Seq[ImageListEntryDto]]`
- L770-773: `imageInspect` — `getJsonResponse[ImageInspectDto]` -> `getJson[ImageInspectDto]`
- L915-918: `imageSearch` — `getJsonResponse[Seq[ImageSearchEntryDto]]` -> `getJson[Seq[ImageSearchEntryDto]]`
- L934-937: `imageHistory` — `getJsonResponse[Seq[ImageHistoryEntryDto]]` -> `getJson[Seq[ImageHistoryEntryDto]]`
- L1024-1027: `networkList` — `getJsonResponse[Seq[NetworkInfoDto]]` -> `getJson[Seq[NetworkInfoDto]]`
- L1096-1099: `volumeList` — `getJsonResponse[VolumeListResponse]` -> `getJson[VolumeListResponse]`

### 2. JSON pattern: `postJsonResponse` + `checkStatus` -> `postJson`
- L571-574: `update` — `postJsonResponse[UpdateResponse]` -> `postJson[UpdateResponse]`, drop `.map { resp => checkStatus(...) }`
- L635-638: `prune` — `postJsonResponse[PruneContainersResponse]` -> `postJson[PruneContainersResponse]`
- L957-960: `imagePrune` — `postJsonResponse[ImagePruneResponseDto]` -> `postJson[ImagePruneResponseDto]`
- L982-985: `imageCommit` — `postJsonResponse[ImageCommitResponseDto]` -> `postJson[ImageCommitResponseDto]`
- L1068-1071: `networkPrune` — `postJsonResponse[NetworkPruneResponse]` -> `postJson[NetworkPruneResponse]`
- L1118-1121: `volumePrune` — `postJsonResponse[VolumePruneResponse]` -> `postJson[VolumePruneResponse]`

### 3. JSON pattern: `deleteJsonResponse` + `checkStatus` -> `deleteJson`
- L794-799: `imageRemove` — `deleteJsonResponse[Seq[ImageDeleteEntryDto]]` -> `deleteJson[Seq[ImageDeleteEntryDto]]`

### 4. Text pattern: `deleteTextResponse` + `checkStatus` -> `deleteText().unit`
- L232-236: `remove`
- L1036-1038: `networkRemove`
- L1110-1112: `volumeRemove`

### 5. Text pattern: `postTextResponse` + `checkStatus` -> `postText().unit`
- L665-667: `imagePull`
- L813-814: `imageTag`
- L904-906: `imagePush`

### 6. Special: `imagePullWithProgress` (L714-731)
`postTextResponse` + `checkStatus` + `resp.fields.body` -> `postText` returning body directly

## DO NOT TOUCH
- `create` method (~L195) — custom error handling for ImageNotFound
- `postUnit` method — already fixed
- `postUnitAccept304` method — already fixed
- `detect` method — already fixed
