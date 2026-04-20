# Analysis: Replace CLI fallbacks with connectRaw

## Current State
- `execInteractive`: throws `NotSupported` (line 434-440)
- `attach`: uses CLI subprocess via `createAttachSession` (line 444-453)
- `createAttachSession`: private helper using PipedOutputStream/PipedInputStream (line 460-508)

## Plan

### Subtask 1: Add `AttachStdin` and `Tty` fields to `ExecCreateRequest` DTO
- Currently missing `AttachStdin` and `Tty` fields
- Need both for exec interactive

### Subtask 2: Add streaming demux helper for `HttpRawConnection`
- `demuxStream` works on a complete `Span[Byte]` — need a streaming version
- For `HttpRawConnection.read` which produces `Stream[Span[Byte], Async]`
- Need to handle Docker's 8-byte header format across chunk boundaries
- Also handle TTY mode (no demux, raw text as stdout)

### Subtask 3: Implement `execInteractive` via connectRaw
- Step 1: Create exec instance with `AttachStdin=true`
- Step 2: Start exec via `connectRaw` to `/exec/{execId}/start`
- Step 3: Wrap `HttpRawConnection` as `AttachSession`

### Subtask 4: Implement `attach` via connectRaw
- Use `connectRaw` to POST `/containers/{id}/attach?stream=true&stdin=...&stdout=...&stderr=...`
- Check TTY mode for demux handling
- Wrap as `AttachSession`

### Subtask 5: Remove `createAttachSession` method
- Only used by `attach` — safe to remove after subtask 4
- `cliCommand` is used elsewhere (execStream, imagePull, etc.) — keep it

## Key interfaces

### AttachSession (Container.scala line 788)
```scala
abstract class AttachSession:
    def write(data: String): Unit < (Async & Abort[ContainerException])
    def write(data: Chunk[Byte]): Unit < (Async & Abort[ContainerException])
    def read: Stream[LogEntry, Async & Abort[ContainerException]]
    def resize(width: Int, height: Int): Unit < (Async & Abort[ContainerException])
```

### HttpRawConnection (HttpRawConnection.scala)
```scala
final class HttpRawConnection private[kyo] (
    val read: Stream[Span[Byte], Async],
    val write: Span[Byte] => Unit < Async
)
```

### ExecCreateRequest needs: AttachStdin, Tty fields added
