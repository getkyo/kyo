# HttpContainerBackend Bug Analysis

## Bug 1: PidsStatsDto.limit — `Long` can't handle Docker null
- **Location**: Line 1611, `limit: Long = 0`
- **Issue**: Docker API returns `pids_stats.limit` as `null` sometimes
- **Fix**: Change to `Option[Long] = None`, update usage at line 1238

## Bug 2: create maps 404 to NotFound instead of ImageNotFound
- **Location**: Lines 199-201
- **Issue**: `withErrorMapping(nameForErrors)` calls `mapHttpError` which maps 404 to `ContainerException.NotFound` — wrong for create (should be `ImageNotFound`)
- **Fix**: Replace `withErrorMapping` with explicit `Abort.runWith[HttpException]` that maps 404 to `ImageNotFound`

## Bug 3: remove 404 handling — already correct via checkStatus
- **Location**: Lines 224-229
- **Status**: Already handled correctly. `checkStatus` maps 404 to `NotFound`.

## Bug 4: logStream uses buffered getBinary
- **Location**: Lines 410-435
- **Issue**: `getBinary` reads entire response body, blocks with `follow=true`
- **Status**: Documenting only — streaming HTTP requires larger architecture change

## Bug 5: Port bindings not returned in inspect
- **Location**: 
  - `CreateContainerRequest` (line 1388) missing `ExposedPorts` field
  - `InspectNetworkSettingsDto.Ports` (line 1535) uses `Seq[InspectPortMappingDto]` but Docker returns `null` for unmapped ports
- **Fix 1**: Add `ExposedPorts: Map[String, Map[String, String]]` to `CreateContainerRequest`, populate in `create`
- **Fix 2**: Change `Ports` type to `Option[Map[String, Option[Seq[InspectPortMappingDto]]]]` to handle null values

## Bug 6: exec env and cwd not passed
- **Location**: 
  - `ExecCreateRequest` (line 1626) missing `Env` and `WorkingDir` fields
  - `exec` method (line 316) doesn't pass `command.env` or `command.workDir`
- **Fix**: Add `Env` and `WorkingDir` to `ExecCreateRequest`, populate in `exec` and `execStream`

## Plan (in order)
1. Bug 1: PidsStatsDto.limit → Option[Long]
2. Bug 5: ExposedPorts + null-safe Ports parsing
3. Bug 6: ExecCreateRequest Env + WorkingDir
4. Bug 2: create 404 → ImageNotFound
5. Bug 4: Document logStream limitation
6. Compile and verify
