# HttpContainerBackend Bug Fix Plan v2

## Bug 1: `mapHttpError` maps ALL 404s to `NotFound` — needs resource-aware mapping
- **Location**: Lines 44-69 (`mapHttpError` + `withErrorMapping`)
- **Issue**: Network ops should → `NetworkNotFound`, volume ops → `VolumeNotFound`, image ops → `ImageNotFound`
- **Fix**: Add `mapNotFound` parameter to `withErrorMapping` and `mapHttpError`, default to `ContainerException.NotFound(Container.Id(s))`
- Update callers: network methods, volume methods, image methods

## Bug 2: `PidsStatsDto.limit` overflows Long
- **Location**: Line 1638-1641 (`PidsStatsDto`)
- **Issue**: Docker returns `18446744073709551615` (uint64 max) which overflows `Long.MaxValue`
- **Fix**: Change `limit: Option[Long]` to `limit: Option[Double]`, update usage in `mapStatsResponse` (line 1256)

## Bug 3: Log demux — verify correctness
- **Location**: Lines 1180-1205 (`demuxStream`)
- **Status**: Code looks correct. streamType 1=stdout, 2=stderr. Logic checks out.

## Bug 4: `copyTo`/`copyFrom` — tar archive handling
- **Location**: Lines 460-504
- **Status**: Implementation looks correct — uses tar -c/-x with PUT/GET to Docker archive API.
- Possible issue: `tarBytes` from proc.stdout.run might be a Stream needing `.run`, not `.toArray`. Will verify.

## Bug 5: Network connect/disconnect error types
- **Location**: Lines 1019-1041
- **Fix**: Covered by Bug 1 — these use `withErrorMapping(network.value)` which will get `mapNotFound` for NetworkNotFound.

## Bug 6: `logStream` times out — needs streaming HTTP
- **Location**: Lines 431-456
- **Issue**: Uses `getBinary` which buffers entire response. With `follow=true`, response never ends.
- **Fix**: Use `getStreamBytes` and consume stream incrementally, demuxing each chunk.

## Execution order
1. Bug 1: Add `mapNotFound` param to `withErrorMapping`/`mapHttpError`, update all callers
2. Bug 2: Change `PidsStatsDto.limit` to `Option[Double]`, update `mapStatsResponse`
3. Bug 6: Switch `logStream` to use `getStreamBytes`
4. Compile and verify
