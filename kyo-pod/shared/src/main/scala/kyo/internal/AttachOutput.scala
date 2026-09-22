package kyo.internal

import kyo.*
import kyo.Container.AttachSession
import kyo.Container.LogEntry

/** Line view over an attach session's byte output.
  *
  * This lives outside `object Container` on purpose. `Container` declares `opaque type Id = String`, and inside that scope the compiler
  * substitutes `String` for `Id` before `Tag` can see it, so `Tag[Emit[Chunk[(String, LogEntry.Source)]]]` cannot be derived there at all.
  * Assembling the lines here derives those tags against the real `String`.
  */
private[kyo] object AttachOutput:

    /** Join `output`'s chunks into lines per stream, drop the empty ones, and tag each line with the stream it came from. */
    def lines(output: Stream[AttachSession.Output, Async & Abort[ContainerException]])(
        using Frame
    ): Stream[LogEntry, Async & Abort[ContainerException]] =
        output
            .mapChunkPure(chunks => chunks.map(chunk => (chunk.bytes, chunk.source)))
            .into(LineAssembler.partitionedPipe[LogEntry.Source])
            .mapChunkPure(lines => lines.collect { case (line, source) if line.nonEmpty => LogEntry(source, line) })

end AttachOutput
