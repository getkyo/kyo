package kyo

import org.h2.jdbcx.JdbcDataSource

/** H2-specific scenarios beyond the shared [[JournalBackendTest]] contract suite (run separately
  * against [[Journal.Backend.h2]] in [[H2JournalBackendTest]]): cross-instance durability over a
  * genuinely closed and reopened backend, and the zero-prior-rows optimistic-append race that only
  * the database's own unique-constraint violation, not the precheck, can resolve.
  */
class H2JournalTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private def envelope(n: Int): Event.Pending =
        Event.Pending(
            id = valid(Event.Id(s"event-$n")),
            eventType = valid(Event.Type("UserRegistered")),
            payload = Span.from(s"""{"n":$n}""".getBytes("UTF-8")),
            metadata = Event.Metadata.empty
        )

    private def offset(value: Long): Event.StreamOffset =
        Event.StreamOffset(value).getOrElse(throw new AssertionError("valid offset"))

    private def version(n: Long): Event.StreamVersion =
        Event.StreamVersion(n).getOrElse(throw new AssertionError("valid version"))

    private def freshDataSource(name: String): JdbcDataSource =
        val dataSource = new JdbcDataSource()
        dataSource.setURL(s"jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1")
        dataSource
    end freshDataSource

    "a second Journal.Backend.h2 call against an already-initialized database succeeds and reads back a prior instance's appended events" in {
        val dataSource = freshDataSource(s"journal-reopen-${java.util.UUID.randomUUID()}")
        val streamId   = valid(Event.StreamId("reopen-stream"))
        for
            _ <- Scope.run(
                for
                    backend <- Journal.Backend.h2(dataSource)
                    _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0), envelope(1))))
                yield ()
            )
            events <- Scope.run(
                for
                    backend <- Journal.Backend.h2(dataSource)
                    read    <- Abort.run[JournalError](backend.read(streamId, Event.StreamOffset.first, 10))
                yield read
            )
        yield events match
            case Result.Success(es) =>
                assert(es.length == 2)
                assert(es(0).id == valid(Event.Id("event-0")))
                assert(es(1).id == valid(Event.Id("event-1")))
            case other =>
                fail(s"expected success, got: $other")
        end for
    }

    "two concurrent NoStream appends to a stream with zero prior rows: exactly one wins via the SQLState 23505 catch-and-requery path" in {
        val dataSource = freshDataSource(s"journal-race-${java.util.UUID.randomUUID()}")
        val streamId   = valid(Event.StreamId("race-stream"))
        for
            backend <- Journal.Backend.h2(dataSource)
            latch   <- Latch.init(1)
            fiber1 <- Fiber.initUnscoped(
                latch.await.andThen(Abort.run[JournalError](
                    backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0)))
                ))
            )
            fiber2 <- Fiber.initUnscoped(
                latch.await.andThen(Abort.run[JournalError](
                    backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(1)))
                ))
            )
            _  <- latch.release
            r1 <- fiber1.get
            r2 <- fiber2.get
        yield
            assert(r1.isSuccess != r2.isSuccess)
            val (winner, loser) = if r1.isSuccess then (r1, r2) else (r2, r1)
            winner match
                case Result.Success(result) =>
                    assert(result.firstOffset == offset(0))
                case other =>
                    fail(s"expected the winner to succeed, got: $other")
            end match
            loser match
                case Result.Failure(JournalConflictError(sid, expected, actual)) =>
                    assert(sid == streamId)
                    assert(expected == ExpectedOffset.NoStream)
                    assert(actual == StreamInfo.Existing(version(1L), offset(0)))
                case other =>
                    fail(s"expected the loser to conflict, got: $other")
            end match
        end for
    }

    "open surfaces a JDBC bootstrap failure as a typed JournalStorageError, not a panic" in {
        val dataSource = new JdbcDataSource()
        // An INIT clause with invalid SQL makes the bootstrap getConnection throw a SQLException,
        // which open must convert to a typed Abort rather than let escape as a panic.
        dataSource.setURL("jdbc:h2:mem:h2-open-failure;INIT=CREATE TABLE broken (")
        Abort.run[JournalStorageError](Scope.run(Journal.Backend.h2(dataSource).map(_ => ()))).map {
            case Result.Failure(e: JournalStorageError) => assert(e.detail.contains("Cannot open H2 journal"))
            case other                                  => assert(false, s"expected Failure(JournalStorageError), got $other")
        }
    }
end H2JournalTest
