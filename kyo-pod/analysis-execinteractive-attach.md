# Analysis: Implement execInteractive and attach using connectRaw

## Current State
- `execInteractive` returns `NotSupported` error
- `attach` uses CLI fallback via `createAttachSession` (PipedOutputStream/PipedInputStream)
- Both should use `HttpClient.connectRaw` for proper HTTP-based bidirectional streaming

## Plan

### Step 1: Add `AttachStdin` to `ExecCreateRequest` DTO
- Add `AttachStdin: Boolean = false` field

### Step 2: Add `buildAttachSession` helper method
- Takes `HttpRawConnection`, `Container.Id`, and `isTty: Boolean`
- Returns `AttachSession` that:
  - `write(String)`: encodes to UTF-8 bytes, calls `conn.write`
  - `write(Chunk[Byte])`: converts to Span, calls `conn.write`
  - `read`: demuxes `conn.read` stream using multiplexed format (non-TTY) or raw text (TTY)
  - `resize`: calls POST to `/exec/{id}/resize` or `/containers/{id}/resize`

### Step 3: Implement `execInteractive`
1. Create exec instance with `AttachStdin=true`
2. POST to `/exec/{execId}/start` using `connectRaw` (returns 101 UPGRADED)
3. Wrap `HttpRawConnection` as `AttachSession` via `buildAttachSession`

### Step 4: Implement `attach`
1. Check if container uses TTY via `isContainerTty`
2. POST to `/containers/{id}/attach?stream=true&...` using `connectRaw`
3. Wrap `HttpRawConnection` as `AttachSession` via `buildAttachSession`

### Step 5: Remove `createAttachSession` CLI fallback
- Only used by `attach` — safe to remove after attach is HTTP-native

## Key Considerations
- `demuxStream` works on `Span[Byte]` (entire buffer) but streaming needs per-chunk demux
- TTY mode: raw bytes, no 8-byte header framing
- Non-TTY mode: 8-byte header (stream type + size) + payload
- Stream chunks may split across frame boundaries — need to handle partial frames
- `connectRaw` returns `HttpRawConnection < (Async & Abort[HttpException] & Scope)` — need `withErrorMapping`
