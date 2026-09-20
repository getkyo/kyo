package kyo.internal

import kyo.*
import kyo.Container.AttachSession
import kyo.Container.LogEntry

class AttachOutputTest extends kyo.BasePodTest:

    private def out(source: LogEntry.Source, text: String): AttachSession.Output =
        AttachSession.Output(source, Span.from(text.getBytes("UTF-8")))

    private def stdout(text: String): AttachSession.Output = out(LogEntry.Source.Stdout, text)
    private def stderr(text: String): AttachSession.Output = out(LogEntry.Source.Stderr, text)

    private def lines(chunks: AttachSession.Output*)(using Frame): Chunk[LogEntry] < (Async & Abort[ContainerException]) =
        AttachOutput.lines(Stream.init(chunks)).run

    "AttachOutput.lines" - {

        "one chunk carrying one line" in {
            lines(stdout("hello\n")).map(r => assert(r == Chunk(LogEntry(LogEntry.Source.Stdout, "hello"))))
        }

        "a line split across chunks is joined before it is emitted" in {
            lines(stdout("he"), stdout("llo\nwor"), stdout("ld\n")).map { r =>
                assert(r == Chunk(
                    LogEntry(LogEntry.Source.Stdout, "hello"),
                    LogEntry(LogEntry.Source.Stdout, "world")
                ))
            }
        }

        "each stream carries its own partial line" in {
            // Interleaved halves: a shared carry would splice "ou" onto "er" and lose both lines.
            lines(stdout("ou"), stderr("er"), stdout("t\n"), stderr("r\n")).map { r =>
                assert(r == Chunk(
                    LogEntry(LogEntry.Source.Stdout, "out"),
                    LogEntry(LogEntry.Source.Stderr, "err")
                ))
            }
        }

        "a multi-byte character split across chunks survives" in {
            val bytes = "→".getBytes("UTF-8")
            lines(
                AttachSession.Output(LogEntry.Source.Stdout, Span.from(bytes.take(1))),
                AttachSession.Output(LogEntry.Source.Stdout, Span.from(bytes.drop(1) ++ "\n".getBytes("UTF-8")))
            ).map(r => assert(r == Chunk(LogEntry(LogEntry.Source.Stdout, "→"))))
        }

        "empty lines are dropped" in {
            lines(stdout("a\n\n\nb\n")).map { r =>
                assert(r == Chunk(
                    LogEntry(LogEntry.Source.Stdout, "a"),
                    LogEntry(LogEntry.Source.Stdout, "b")
                ))
            }
        }

        "a last line without a trailing newline is emitted when the output ends" in {
            lines(stdout("done\nno-newline")).map { r =>
                assert(r == Chunk(
                    LogEntry(LogEntry.Source.Stdout, "done"),
                    LogEntry(LogEntry.Source.Stdout, "no-newline")
                ))
            }
        }

        "output with no lines at all emits nothing" in {
            lines().map(r => assert(r.isEmpty))
        }
    }

end AttachOutputTest
