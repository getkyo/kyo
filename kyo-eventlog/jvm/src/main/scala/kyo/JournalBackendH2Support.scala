package kyo

import javax.sql.DataSource

/** JVM-only public entry point for [[kyo.internal.H2Journal]], added as an extension method on
  * the already-locked [[Journal.Backend]] companion (the same `extension (self: X.type)`
  * mechanism `FileJournalConfigurationSupport.scala` uses for `FileJournal.type`) rather than
  * editing `Journal.scala` directly: `Journal.scala` is a cross-platform, materialized source
  * compiled on JS, Native, and Wasm too, and `javax.sql.DataSource` does not exist there. Keeping
  * this extension in the `jvm` sourceset means `Journal.Backend.h2` exists only on the JVM
  * platform, while `Journal.Backend`'s own shared declaration stays completely unmodified.
  */
extension (self: Journal.Backend.type)
    /** Opens a JDBC-backed journal (H2 by default) implementing [[Journal.Backend]] directly
      * against one `events` table, with no [[FileSystem]] involvement: the db-shaped sibling of
      * [[Journal.Backend.file]]/[[Journal.Backend.fileOver]]. See [[kyo.internal.H2Journal]] for
      * the schema, the optimistic-append mechanism, and the blocking-offload pattern.
      */
    def h2(dataSource: DataSource)(using Frame): Journal.Backend[Async] < (Async & Scope & Abort[JournalStorageError]) =
        kyo.internal.H2Journal.open(dataSource)
end extension
