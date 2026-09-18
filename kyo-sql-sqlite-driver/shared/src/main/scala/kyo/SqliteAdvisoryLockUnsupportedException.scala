package kyo

/** An advisory lock was asked for, and SQLite has none: its concurrency is one writer over the whole database rather than per key. */
final case class SqliteAdvisoryLockUnsupportedException(key: Long)(using Frame)
    extends SqlUnsupportedBackendException(
        s"SQLite has no advisory locks, so the lock named $key cannot be taken. Its concurrency is one writer over the whole " +
            "database, which is the mutual exclusion an advisory lock would be asked for."
    )
