package kyo

sealed trait AccountEvent derives Schema, CanEqual
object AccountEvent:
    final case class Deposited(amount: Int) extends AccountEvent derives Schema, CanEqual
    final case class Withdrawn(amount: Int) extends AccountEvent derives Schema, CanEqual
end AccountEvent

sealed trait AccountCommand
object AccountCommand:
    final case class Deposit(amount: Int)  extends AccountCommand
    final case class Withdraw(amount: Int) extends AccountCommand
end AccountCommand

class EventSourcedTest extends kyo.test.Test[Any]:

    private type AccountFailure =
        JournalError | EventSourced.EventCodecError | EventSourced.DecideFailure | EventSourced.EvolveFailure | EventLog.PreparationFailure

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private def freshJournalId(suffix: String)(using Frame): JournalId =
        JournalId.validate(s"event-sourced-test-$suffix").getOrElse(throw new AssertionError("valid journal id"))

    private val accountStreamId: Event.StreamId = valid(Event.StreamId("account-stream"))
    private val accountEventType: Event.Type    = valid(Event.Type("AccountEvent"))

    private given Event.Definition[AccountEvent, AccountEvent] =
        Event.Definition[AccountEvent, AccountEvent](
            eventType = accountEventType,
            stream = Event.StreamSelector.constant(accountStreamId),
            eventId = Event.IdPolicy.generated[AccountEvent],
            metadata = EventLog.Metadata.empty[AccountEvent]
        )

    // The codec's own decode checks the recorded eventType against the type this codec
    // owns, distinct from a second byte-decode: EventLog.read already decoded the payload.
    private def accountCodec(using Frame): EventSourced.EventCodec[AccountEvent] =
        new EventSourced.EventCodec[AccountEvent]:
            def prepare(log: EventLog[AccountEvent])(event: AccountEvent, directive: EventLog.AppendDirective)(using
                Frame
            ): log.Command < (Sync & Abort[EventSourced.EventCodecError] & Abort[EventLog.PreparationFailure]) =
                log.prepare(event, directive)
            def decode(record: Event.Record[AccountEvent])(using Frame): AccountEvent < Abort[EventSourced.EventCodecError] =
                if record.eventType == accountEventType then record.payload
                else Abort.fail(EventSourced.EventCodecError(s"unrecognized event type: ${record.eventType.value}"))

    // decide rejects an overdraft; evolve rejects a stored withdrawal that exceeds the
    // folded balance (only reachable via history appended outside decide's own check).
    private def accountDecider(using Frame): EventSourced.Decider[AccountCommand, Int, AccountEvent] =
        new EventSourced.Decider[AccountCommand, Int, AccountEvent]:
            def initialState: Int = 0
            def decide(state: Int, command: AccountCommand)(using Frame): Chunk[AccountEvent] < Abort[EventSourced.DecideFailure] =
                command match
                    case AccountCommand.Deposit(amount) if amount > 0 =>
                        Chunk(AccountEvent.Deposited(amount))
                    case AccountCommand.Deposit(amount) =>
                        Abort.fail(EventSourced.DecideFailure(s"deposit amount must be positive, got $amount"))
                    case AccountCommand.Withdraw(amount) if amount > 0 && amount <= state =>
                        Chunk(AccountEvent.Withdrawn(amount))
                    case AccountCommand.Withdraw(amount) =>
                        Abort.fail(EventSourced.DecideFailure(s"insufficient balance: cannot withdraw $amount from $state"))
            def evolve(state: Int, event: AccountEvent)(using Frame): Int < Abort[EventSourced.EvolveFailure] =
                event match
                    case AccountEvent.Deposited(amount) => state + amount
                    case AccountEvent.Withdrawn(amount) if amount <= state =>
                        state - amount
                    case AccountEvent.Withdrawn(amount) =>
                        Abort.fail(EventSourced.EvolveFailure(s"cannot evolve: withdrawal $amount exceeds state $state"))

    "EventStore.init binds log and codec; decide runs end-to-end in Journal.run" in {
        val journalId = freshJournalId("decide-e2e")
        for
            codecs  <- EventLogCodecs.schema[AccountEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[AccountFailure] {
                Journal.run(backend) {
                    for
                        store  <- EventSourced.EventStore.init(log, accountCodec)
                        r1     <- EventSourced.decide(store, accountStreamId, AccountCommand.Deposit(100), accountDecider)
                        r2     <- EventSourced.decide(store, accountStreamId, AccountCommand.Deposit(50), accountDecider)
                        events <- store.read(accountStreamId, Event.StreamOffset.first, 10)
                    yield (r1, r2, events)
                }
            }
        yield result match
            case Result.Success((r1, r2, events)) =>
                assert(r1.size == 1, s"expected one appended result for the first decision, got: $r1")
                assert(r2.size == 1, s"expected one appended result for the second decision, got: $r2")
                assert(r1(0).firstOffset.value == 0L)
                assert(r2(0).firstOffset.value == 1L)
                assert(events.size == 2)
                assert(events(0).payload == AccountEvent.Deposited(100))
                assert(events(1).payload == AccountEvent.Deposited(50))
                assert(events(0).ref.offset.value == 0L)
                assert(events(1).ref.offset.value == 1L)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "decide takes an EventStore and no call-site EventDefinition evidence (source scan)" in {
        val positiveErrors = scala.compiletime.testing.typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val store: kyo.EventSourced.EventStore[kyo.AccountEvent] = ???
            val streamId: kyo.Event.StreamId = ???
            val decider: kyo.EventSourced.Decider[kyo.AccountCommand, Int, kyo.AccountEvent] = ???
            kyo.EventSourced.decide(store, streamId, kyo.AccountCommand.Deposit(1), decider)
            """
        ).map(_.message)
        assert(positiveErrors.isEmpty, s"expected decide to type-check with no Event.Definition given in scope, got: $positiveErrors")

        val negativeErrors = scala.compiletime.testing.typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val notAStore: kyo.EventLog[kyo.AccountEvent] = ???
            val streamId: kyo.Event.StreamId = ???
            val decider: kyo.EventSourced.Decider[kyo.AccountCommand, Int, kyo.AccountEvent] = ???
            kyo.EventSourced.decide(notAStore, streamId, kyo.AccountCommand.Deposit(1), decider)
            """
        ).map(_.message)
        assert(negativeErrors.nonEmpty, "expected decide's first parameter to reject a non-EventStore value")
    }

    "decider decision failure surfaces as Abort[DecideFailure], distinct from evolve" in {
        val decideJournalId = freshJournalId("decide-failure")
        val evolveJournalId = freshJournalId("evolve-failure")
        for
            decideResult <-
                for
                    codecs  <- EventLogCodecs.schema[AccountEvent]()
                    log     <- EventLog.init(codecs, decideJournalId)
                    backend <- Journal.Backend.inMemory
                    result <- Abort.run[AccountFailure] {
                        Journal.run(backend) {
                            for
                                store <- EventSourced.EventStore.init(log, accountCodec)
                                r     <- EventSourced.decide(store, accountStreamId, AccountCommand.Withdraw(50), accountDecider)
                            yield r
                        }
                    }
                yield result
            evolveResult <-
                for
                    codecs  <- EventLogCodecs.schema[AccountEvent]()
                    log     <- EventLog.init(codecs, evolveJournalId)
                    backend <- Journal.Backend.inMemory
                    result <- Abort.run[AccountFailure] {
                        Journal.run(backend) {
                            for
                                store <- EventSourced.EventStore.init(log, accountCodec)
                                // Bypasses decide's own overdraft check: appendAll carries no
                                // business-rule validation, so this seeds history evolve must reject.
                                _ <- store.appendAll(Chunk(AccountEvent.Withdrawn(999)))
                                r <- EventSourced.decide(store, accountStreamId, AccountCommand.Deposit(10), accountDecider)
                            yield r
                        }
                    }
                yield result
        yield
            val isDecideFailure = decideResult match
                case Result.Failure(_: EventSourced.DecideFailure) => true
                case _                                             => false
            val isEvolveFailure = evolveResult match
                case Result.Failure(_: EventSourced.EvolveFailure) => true
                case _                                             => false
            val decideIsNotEvolve = decideResult match
                case Result.Failure(_: EventSourced.EvolveFailure) => false
                case _                                             => true
            val evolveIsNotDecide = evolveResult match
                case Result.Failure(_: EventSourced.DecideFailure) => false
                case _                                             => true
            assert(isDecideFailure, s"expected an overdraft decision to abort DecideFailure, got: $decideResult")
            assert(isEvolveFailure, s"expected a corrupt-history fold to abort EvolveFailure, got: $evolveResult")
            assert(decideIsNotEvolve && evolveIsNotDecide, "expected DecideFailure and EvolveFailure to be distinct, non-overlapping rows")
        end for
    }

    "EventCodec.decode surfaces a domain-mapping failure as EventCodecError, distinct from a read-time JournalReadFailure" in {
        val mappingJournalId    = freshJournalId("codec-mapping")
        val corruptionJournalId = freshJournalId("codec-corruption")
        val wrongType           = valid(Event.Type("UnknownAccountEvent"))
        val injectedId          = valid(Event.Id("injected-1"))
        for
            mappingResult <-
                for
                    codecs  <- EventLogCodecs.schema[AccountEvent]()
                    log     <- EventLog.init(codecs, mappingJournalId)
                    backend <- Journal.Backend.inMemory
                    result <- Abort.run[AccountFailure] {
                        Journal.run(backend) {
                            for
                                store <- EventSourced.EventStore.init(log, accountCodec)
                                // Bypasses the typed log entirely (raw Journal.append) to store a
                                // validly-encoded payload under an eventType this codec does not
                                // recognize; the byte decode at EventLog.read succeeds, so the
                                // failure is purely the domain-mapping check inside decode.
                                bytes <- EventLogCodecs.encodeValue(codecs.value, AccountEvent.Deposited(10))
                                _ <- Journal.append(
                                    accountStreamId,
                                    ExpectedOffset.NoStream,
                                    Chunk(Event.Pending(injectedId, wrongType, bytes, Event.Metadata.empty))
                                )
                                records <- store.read(accountStreamId, Event.StreamOffset.first, 10)
                                decoded <- Kyo.foreach(records)(record => store.codec.decode(record))
                            yield decoded
                        }
                    }
                yield result
            corruptionResult <-
                for
                    codecs  <- EventLogCodecs.schema[AccountEvent]()
                    log     <- EventLog.init(codecs, corruptionJournalId)
                    backend <- Journal.Backend.inMemory
                    result <- Abort.run[AccountFailure] {
                        Journal.run(backend) {
                            for
                                store <- EventSourced.EventStore.init(log, accountCodec)
                                // Corrupt bytes never reach a decoded Record; EventLog.read folds
                                // the failure into JournalCorruptedError before EventCodec.decode runs.
                                _ <- Journal.append(
                                    accountStreamId,
                                    ExpectedOffset.NoStream,
                                    Chunk(Event.Pending(
                                        injectedId,
                                        accountEventType,
                                        Span.from(Array[Byte](0xff.toByte, 0x00.toByte, 0x01.toByte)),
                                        Event.Metadata.empty
                                    ))
                                )
                                records <- store.read(accountStreamId, Event.StreamOffset.first, 10)
                            yield records
                        }
                    }
                yield result
        yield
            val mappingIsCodecError = mappingResult match
                case Result.Failure(_: EventSourced.EventCodecError) => true
                case _                                               => false
            val corruptionIsReadFailure = corruptionResult match
                case Result.Failure(_: JournalCorruptedError) => true
                case _                                        => false
            assert(mappingIsCodecError, s"expected the mismatched-eventType record to surface EventCodecError, got: $mappingResult")
            assert(
                corruptionIsReadFailure,
                s"expected corrupted bytes to surface JournalCorruptedError before EventCodec.decode runs, got: $corruptionResult"
            )
        end for
    }

    "EventStore captures no Journal backend (source scan)" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            val store: kyo.EventSourced.EventStore[kyo.AccountEvent] = ???
            store.backend
            """
        ).map(_.message)
        assert(errors.nonEmpty, "expected EventStore[A] to carry no backend field or accessor")
        assert(errors.exists(_.contains("backend")))
    }

    "appendAll with empty events performs no append" in {
        val journalId = freshJournalId("empty-append")
        for
            codecs  <- EventLogCodecs.schema[AccountEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[AccountFailure] {
                Journal.run(backend) {
                    for
                        store   <- EventSourced.EventStore.init(log, accountCodec)
                        results <- store.appendAll(Chunk.empty)
                        events  <- store.read(accountStreamId, Event.StreamOffset.first, 10)
                    yield (results, events)
                }
            }
        yield result match
            case Result.Success((results, events)) =>
                assert(results.isEmpty, s"expected an empty appendAll to produce an empty result, got: $results")
                assert(events.isEmpty, s"expected an empty appendAll to leave the stream unchanged, got: $events")
            case other => fail(s"expected success, got: $other")
        end for
    }

    "excluded J3 integrations are absent module-wide and EventSourced adds no new MetadataValue mirror" in {
        val integrationErrors = scala.compiletime.testing.typeCheckErrors(
            """
            val a: kyo.EventSourcedFlow = ???
            val b: kyo.EventSourcedActor = ???
            val c: kyo.EventSourcedAeron = ???
            """
        ).map(_.message)
        assert(integrationErrors.nonEmpty, "expected the excluded J3 integration types to not resolve anywhere in the module")
        assert(integrationErrors.exists(_.contains("EventSourcedFlow")))
        assert(integrationErrors.exists(_.contains("EventSourcedActor")))
        assert(integrationErrors.exists(_.contains("EventSourcedAeron")))

        val mirrorErrors = scala.compiletime.testing.typeCheckErrors(
            """
            val m: kyo.EventSourced.MetadataValue = ???
            """
        ).map(_.message)
        assert(mirrorErrors.nonEmpty, "expected EventSourced to declare no MetadataValue type, alias, or mirror")
        assert(mirrorErrors.exists(_.contains("MetadataValue")))
    }
end EventSourcedTest
