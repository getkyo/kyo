package kyo

/** Stub `Backend.file` extension for JS and Wasm. The file-backed journal requires platform I/O
  * unavailable on these targets; this stub satisfies the type so shared tests that reference
  * `Journal.Backend.file` compile, while actual invocations fail at runtime with a typed
  * [[JournalStorageError]] rather than a link error.
  */
extension (backend: Journal.Backend.type)
    def file(dir: Path, config: FileJournal.Config = FileJournal.Config.default)(using
        Frame
    )
        : Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError]) =
        Abort.fail(JournalStorageError("Journal.Backend.file is not available on this platform", Absent))
end extension
