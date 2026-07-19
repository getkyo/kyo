package kyo

import org.h2.jdbcx.JdbcDataSource

/** Cross-medium proof for [[EventLog.migrate]]: a host-file journal source migrated into a
  * fresh H2-backed [[Journal.Backend]] target, a medium that bypasses [[FileSystem]] entirely.
  * JVM-only: [[javax.sql.DataSource]] is a JDK-only type, matching [[Journal.Backend.h2]]'s own
  * JVM-only placement.
  */
class EventLogMigrationH2Test extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private def journalId(name: String): JournalId =
        JournalId.validate(name)(using Frame.internal).getOrElse(throw new AssertionError("valid journal id"))

    private def offset(value: Long): Event.StreamOffset =
        Event.StreamOffset(value).getOrElse(throw new AssertionError("valid offset"))

    private def envelope(id: String, n: Int): Event.New =
        Event.New(
            id = valid(Event.Id(id)),
            eventType = valid(Event.Type("MigrateH2Event")),
            payload = Span.from(s"""{"n":$n}""".getBytes("UTF-8")),
            metadata = Event.Metadata.empty
        )

    /** A fresh real host temp dir, removed at the enclosing [[Scope]]'s exit. */
    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h =>
            // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
            Sync.Unsafe.defer(h.remove())
        ).map(_.path)

    private def freshDataSource(name: String): JdbcDataSource =
        val dataSource = new JdbcDataSource()
        dataSource.setURL(s"jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1")
        dataSource
    end freshDataSource

    "migrate copies a stream from a host-file journal into a fresh H2 Journal.Backend[Async], a medium that bypasses FileSystem entirely" in {
        val streamId   = valid(Event.StreamId("migrate-h2"))
        val dataSource = freshDataSource(s"migrate-h2-${java.util.UUID.randomUUID()}")
        Scope.run {
            hostTempDir("kyo-eventlog-migrate-h2").map { dir =>
                for
                    codecs       <- EventLogCodecs.bytes()
                    sourceConfig <- FileJournal.Binary.configuration(journalId("migrate-h2-source"), codecs, FileJournal.Options.default)
                    source       <- Journal.Backend.file(dir, sourceConfig)
                    target       <- Journal.Backend.h2(dataSource)
                    _ <- Abort.run[JournalError](source.append(
                        streamId,
                        ExpectedOffset.NoStream,
                        Chunk(envelope("migrate-h2-0", 0), envelope("migrate-h2-1", 1))
                    ))
                    migrated   <- Abort.run[JournalError](EventLog.migrate(source, target, Chunk(streamId)))
                    targetRead <- Abort.run[JournalError](target.read(streamId, Event.StreamOffset.first, 10))
                yield
                    assert(migrated == Result.succeed(EventLog.MigrationReport(Chunk(
                        EventLog.MigrationReport.StreamSummary(streamId, 2L, Present(offset(1)))
                    ))))
                    targetRead match
                        case Result.Success(events) =>
                            assert(events.length == 2)
                            assert(events(0).id == valid(Event.Id("migrate-h2-0")))
                            assert(events(1).id == valid(Event.Id("migrate-h2-1")))
                        case other => fail(s"expected success, got: $other")
                    end match
            }
        }
    }

end EventLogMigrationH2Test
