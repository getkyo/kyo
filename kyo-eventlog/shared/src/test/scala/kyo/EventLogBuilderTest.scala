package kyo

/** Focused spec for the additive [[EventLog.builder]] / [[EventLog.Builder]] / [[EventLog.RouteCoverage]]
  * route-coverage builder. Reuses the top-level `QuestEvent`/`ColorEvent`/`ShapeEvent`
  * fixtures declared in `EventLogTest.scala` (same `kyo` package). The builder centralizes the
  * scattered per-member `given Event.Definition`s into one fluent expression baked behind
  * `import log.given`, and proves route completeness at `build` through a compile-time
  * `RouteCoverage` (a `Mirror.SumOf` iteration for enum/sealed, a union-decomposition macro for a
  * bare union).
  */
class EventLogBuilderTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private def freshJournalId(suffix: String)(using Frame): JournalId =
        JournalId.validate(s"builder-test-$suffix").getOrElse(throw new AssertionError("valid journal id"))

    // The bare-union QuestEvent has no CanEqual instance, so union payloads are compared through a
    // total String projection instead of `==` (the same member-by-member style EventLogTest uses).
    private def questId(event: QuestEvent): String =
        event match
            case QuestStarted(id)        => s"started:$id"
            case PartyJoined(id, member) => s"joined:$id:$member"

    "builder builds a fully-routed log and round-trips values (no .codecs() call, defaults apply)" in {
        val journalId = freshJournalId("happy")
        val qsStream  = valid(Event.StreamId("happy-qs"))
        val pjStream  = valid(Event.StreamId("happy-pj"))
        for
            log <- EventLog.builder[QuestEvent](journalId)
                .define[QuestStarted](stream = Event.StreamSelector.constant(qsStream))
                .define[PartyJoined](stream = Event.StreamSelector.constant(pjStream))
                .build
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                import log.given
                Journal.run(backend) {
                    for
                        _      <- log.append(QuestStarted("q1"))
                        events <- log.read(qsStream, Event.StreamOffset.first, 10)
                    yield events
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 1)
                assert(questId(events(0).payload) == "started:q1")
                assert(events(0).eventType.value == summon[Schema[QuestStarted]].structure.name)
                assert(events(0).ref == JournalEntryRef(journalId, qsStream, Event.StreamOffset.first))
            case other => fail(s"expected success, got: $other")
        end for
    }

    "an EventLog.init log appended through import log.given without a hand-written definition aborts loud" in {
        // The `routed` given is meaningful only on a builder-built log, where every append routes by
        // the runtime value. On an init log (empty routes) `import log.given` brings `routed` into
        // scope but there is no member route, so the append must fail loud with a typed
        // PreparationFailure, never land silently on a placeholder stream.
        val journalId = freshJournalId("init-misuse")
        val target    = valid(Event.StreamId("init-misuse-target"))
        for
            codecs  <- EventLogCodecs.schema[QuestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            // Both the default directive and every override facet must fail loud: a stream-id
            // override must not slip the event past the missing route onto its target stream.
            plain <- Abort.run[KyoException] {
                import log.given
                Journal.run(backend)(log.append(QuestStarted("q1")))
            }
            overridden <- Abort.run[KyoException] {
                import log.given
                Journal.run(backend)(log.append(QuestStarted("q1"), EventLog.AppendDirective.replaceStreamId(target)))
            }
            targetEvents <- Abort.run[JournalError](Journal.run(backend)(Journal.read(target, Event.StreamOffset.first, 10)))
        yield
            def abortsPrep(r: Result[KyoException, AppendResult], label: String): Unit =
                r match
                    case Result.Failure(_: EventLog.PreparationFailure) => ()
                    case other => fail(s"$label: expected a typed PreparationFailure abort, got: $other")
            abortsPrep(plain, "default directive")
            abortsPrep(overridden, "replaceStreamId directive")
            targetEvents match
                case Result.Success(events) => assert(events.isEmpty, s"nothing must land on the override target: $events")
                case other                  => fail(s"reading the override target must succeed, got: $other")
        end for
    }

    "a supertype-typed value routes by its runtime member" in {
        // A decider folds to Chunk[QuestEvent]; each element is typed at the union supertype, not
        // the concrete leaf. The append routes by the runtime value to the correct member's stream.
        val journalId = freshJournalId("supertype")
        val qsStream  = valid(Event.StreamId("supertype-qs"))
        val pjStream  = valid(Event.StreamId("supertype-pj"))
        for
            log <- EventLog.builder[QuestEvent](journalId)
                .define[QuestStarted](stream = Event.StreamSelector.constant(qsStream))
                .define[PartyJoined](stream = Event.StreamSelector.constant(pjStream))
                .build
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                import log.given
                Journal.run(backend) {
                    val ev: QuestEvent = QuestStarted("q1") // ascribed at the union supertype
                    for
                        _      <- log.append(ev)
                        events <- log.read(qsStream, Event.StreamOffset.first, 10)
                    yield events
                    end for
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 1, s"expected the supertype-typed append to land one event, got: $events")
                assert(questId(events(0).payload) == "started:q1")
                assert(events(0).ref == JournalEntryRef(journalId, qsStream, Event.StreamOffset.first))
            case other => fail(s"expected the supertype-typed append to succeed, got: $other")
        end for
    }

    "a decider-shaped fold appends each supertype-typed element to its member stream" in {
        val journalId = freshJournalId("decider-fold")
        val qsStream  = valid(Event.StreamId("fold-qs"))
        val pjStream  = valid(Event.StreamId("fold-pj"))
        // Adjacent (tag + content) union representation so QuestStarted and PartyJoined decode
        // unambiguously; the build below picks it up through its `using Schema[QuestEvent]`.
        given Schema[QuestEvent] = summon[Schema[QuestEvent]].adjacent("type", "content")
        for
            log <- EventLog.builder[QuestEvent](journalId)
                .define[QuestStarted](stream = Event.StreamSelector.constant(qsStream))
                .define[PartyJoined](stream = Event.StreamSelector.constant(pjStream))
                .build
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                import log.given
                Journal.run(backend) {
                    // Elements are typed at the union supertype, as a decider's decided Chunk[A] is.
                    val decided: Chunk[QuestEvent] = Chunk(QuestStarted("q1"), PartyJoined("q1", "alice"))
                    for
                        _  <- Kyo.foreach(decided)(ev => log.append(ev))
                        qs <- log.read(qsStream, Event.StreamOffset.first, 10)
                        pj <- log.read(pjStream, Event.StreamOffset.first, 10)
                    yield (qs, pj)
                    end for
                }
            }
        yield result match
            case Result.Success((qs, pj)) =>
                assert(qs.map(r => questId(r.payload)) == Chunk("started:q1"), s"quest-started stream mismatch: $qs")
                assert(pj.map(r => questId(r.payload)) == Chunk("joined:q1:alice"), s"party-joined stream mismatch: $pj")
            case other => fail(s"expected the folded supertype-typed batch to succeed, got: $other")
        end for
    }

    "define is order-independent and routes each batch member to its own stream" in {
        val jidA     = freshJournalId("order-a")
        val jidB     = freshJournalId("order-b")
        val qsStream = valid(Event.StreamId("order-qs"))
        val pjStream = valid(Event.StreamId("order-pj"))
        // QuestStarted's single field is a structural subset of PartyJoined's two fields, so the
        // default untagged union schema cannot always tell them apart on decode; an adjacent
        // (tag + content) representation disambiguates by construction (the build below picks it up
        // through its `using Schema[QuestEvent]`).
        given Schema[QuestEvent] = summon[Schema[QuestEvent]].adjacent("type", "content")

        def runBatch(log: EventLog[QuestEvent], backend: Journal.Backend[Sync]) =
            import log.given
            Journal.run(backend) {
                for
                    p1 <- log.prepare(QuestStarted("q"))
                    p2 <- log.prepare(PartyJoined("q", "m"))
                    p3 <- log.prepare(QuestStarted("q2"))
                    _  <- log.appendAll(p1, p2, p3)
                    qs <- log.read(qsStream, Event.StreamOffset.first, 10)
                    pj <- log.read(pjStream, Event.StreamOffset.first, 10)
                yield (qs, pj)
            }
        end runBatch

        for
            logA <- EventLog.builder[QuestEvent](jidA)
                .define[QuestStarted](stream = Event.StreamSelector.constant(qsStream))
                .define[PartyJoined](stream = Event.StreamSelector.constant(pjStream))
                .build
            logB <- EventLog.builder[QuestEvent](jidB)
                .define[PartyJoined](stream = Event.StreamSelector.constant(pjStream))
                .define[QuestStarted](stream = Event.StreamSelector.constant(qsStream))
                .build
            backendA <- Journal.Backend.inMemory
            backendB <- Journal.Backend.inMemory
            resultA  <- Abort.run[JournalError](runBatch(logA, backendA))
            resultB  <- Abort.run[JournalError](runBatch(logB, backendB))
        yield (resultA, resultB) match
            case (Result.Success((qsA, pjA)), Result.Success((qsB, pjB))) =>
                assert(qsA.map(r => questId(r.payload)) == Chunk("started:q", "started:q2"))
                assert(qsA.map(_.ref.offset.value) == Chunk(0L, 1L))
                assert(pjA.map(r => questId(r.payload)) == Chunk("joined:q:m"))
                assert(pjA(0).ref.offset.value == 0L)
                assert(qsB.map(r => questId(r.payload)) == qsA.map(r => questId(r.payload)))
                assert(pjB.map(r => questId(r.payload)) == pjA.map(r => questId(r.payload)))
                assert(qsB.map(_.ref.offset.value) == qsA.map(_.ref.offset.value))
            case other => fail(s"expected both batches to succeed, got: $other")
        end for
    }

    "builder(...).build and init(...) with hand-written givens produce identical append/read" in {
        val jidA     = freshJournalId("equiv-a")
        val jidB     = freshJournalId("equiv-b")
        val qsStream = valid(Event.StreamId("equiv-qs"))
        val pjStream = valid(Event.StreamId("equiv-pj"))
        // Adjacent (tag + content) union representation so QuestStarted and PartyJoined decode
        // unambiguously; both the builder build and the init codecs pick it up in scope.
        given Schema[QuestEvent] = summon[Schema[QuestEvent]].adjacent("type", "content")
        for
            logA <- EventLog.builder[QuestEvent](jidA)
                .define[QuestStarted](stream = Event.StreamSelector.constant(qsStream))
                .define[PartyJoined](stream = Event.StreamSelector.constant(pjStream))
                .build
            codecsB  <- EventLogCodecs.schema[QuestEvent]()
            logB     <- EventLog.init(codecsB, jidB)
            backendA <- Journal.Backend.inMemory
            backendB <- Journal.Backend.inMemory
            resultA <- Abort.run[JournalError] {
                import logA.given
                Journal.run(backendA) {
                    for
                        _   <- logA.append(QuestStarted("q1"))
                        _   <- logA.append(PartyJoined("q1", "alice"))
                        qsr <- logA.read(qsStream, Event.StreamOffset.first, 10)
                        pjr <- logA.read(pjStream, Event.StreamOffset.first, 10)
                    yield (qsr, pjr)
                }
            }
            resultB <- Abort.run[JournalError] {
                given Event.Definition[QuestEvent, QuestStarted] =
                    Event.Definition.schema[QuestEvent, QuestStarted](Event.StreamSelector.constant(qsStream))
                given Event.Definition[QuestEvent, PartyJoined] =
                    Event.Definition.schema[QuestEvent, PartyJoined](Event.StreamSelector.constant(pjStream))
                Journal.run(backendB) {
                    for
                        _   <- logB.append(QuestStarted("q1"))
                        _   <- logB.append(PartyJoined("q1", "alice"))
                        qsr <- logB.read(qsStream, Event.StreamOffset.first, 10)
                        pjr <- logB.read(pjStream, Event.StreamOffset.first, 10)
                    yield (qsr, pjr)
                }
            }
        yield (resultA, resultB) match
            case (Result.Success((qsA, pjA)), Result.Success((qsB, pjB))) =>
                assert(qsA.map(r => questId(r.payload)) == qsB.map(r => questId(r.payload)))
                assert(pjA.map(r => questId(r.payload)) == pjB.map(r => questId(r.payload)))
                assert(qsA.map(_.eventType) == qsB.map(_.eventType))
                assert(pjA.map(_.eventType) == pjB.map(_.eventType))
                assert(qsA.map(_.ref.offset.value) == qsB.map(_.ref.offset.value))
                assert(pjA.map(_.ref.offset.value) == pjB.map(_.ref.offset.value))
                assert(qsA.map(r => questId(r.payload)) == Chunk("started:q1"))
                assert(pjA.map(r => questId(r.payload)) == Chunk("joined:q1:alice"))
            case other => fail(s"expected both paths to succeed, got: $other")
        end for
    }

    "a Builder missing a member's .define fails to compile (union and sealed sub-cases)" in {
        val unionMissing = scala.compiletime.testing.typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val jid: kyo.JournalId = ???
            kyo.EventLog.builder[kyo.QuestEvent](jid)
                .define[kyo.QuestStarted](stream = ???)
                .build
            """
        ).map(_.message)
        assert(unionMissing.nonEmpty, "expected a union Builder missing PartyJoined to fail to compile")
        assert(
            unionMissing.exists(_.contains("PartyJoined")),
            s"expected the union coverage error to name PartyJoined, got: $unionMissing"
        )

        val sealedMissing = scala.compiletime.testing.typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val jid: kyo.JournalId = ???
            kyo.EventLog.builder[kyo.ShapeEvent](jid)
                .define[kyo.ShapeEvent.Circle](stream = ???)
                .build
            """
        ).map(_.message)
        assert(sealedMissing.nonEmpty, "expected a sealed Builder missing ShapeEvent.Square to fail to compile")

        val fullyRouted = scala.compiletime.testing.typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val jid: kyo.JournalId = ???
            kyo.EventLog.builder[kyo.QuestEvent](jid)
                .define[kyo.QuestStarted](stream = ???)
                .define[kyo.PartyJoined](stream = ???)
                .build
            """
        ).map(_.message)
        assert(fullyRouted.isEmpty, s"expected a fully-routed Builder (no .codecs()) to type-check, got: $fullyRouted")
    }

    "runtime dynamic resolution aborts PreparationFailure naming an unrouted member" in {
        // Exercises Event.Routes#resolveDynamic (the runtime-value dispatch path) directly: a
        // registry that routes QuestStarted only, with a matcher that maps a PartyJoined value to
        // its structure name (the unrouted key). If the dynamic path were ever flipped to accept an
        // unrouted member instead of failing loud, this leaf breaks.
        val qsDef = Event.Definition.schema[QuestEvent, QuestStarted](
            Event.StreamSelector.constant(valid(Event.StreamId("dyn-qs")))
        )
        val partyKey = summon[Schema[PartyJoined]].structure.name
        val routes = Event.Routes.empty[QuestEvent]
            .added[QuestStarted](qsDef)
            .withMatcher((_: QuestEvent) => partyKey)
        val partyValue: QuestEvent = PartyJoined("q", "m")
        Abort.run[EventLog.PreparationFailure](routes.resolveDynamic(partyValue)).map {
            case Result.Failure(failure) =>
                assert(failure.reason.contains(partyKey), s"expected the failure to name $partyKey, got: ${failure.reason}")
            case other =>
                fail(s"expected a PreparationFailure naming $partyKey, got: $other")
        }
    }

    "build fails with EventCodecConfigurationError when two members share a structure name" in {
        // Routing keys members by Schema[E].structure.name. Two routes under one structure name are
        // ambiguous, so build must fail loud rather than let one member silently shadow the other.
        // Two same-structure-name members are not cheaply constructible as distinct types, so this
        // drives Event.Routes.added twice with the same schema and asserts build's Abort behavior.
        val journalId  = freshJournalId("dup-name")
        val qsStream   = valid(Event.StreamId("dup-qs"))
        val qsDef      = Event.Definition.schema[QuestEvent, QuestStarted](Event.StreamSelector.constant(qsStream))
        val duplicated = Event.Routes.empty[QuestEvent].added[QuestStarted](qsDef).added[QuestStarted](qsDef)
        val questKey   = summon[Schema[QuestStarted]].structure.name
        assert(
            duplicated.duplicates.contains(questKey),
            s"expected the duplicate key set to record $questKey, got: ${duplicated.duplicates}"
        )

        val builder = new EventLog.Builder[QuestEvent, QuestStarted & PartyJoined](journalId, Absent, duplicated)
        Abort.run[EventLog.EventCodecConfigurationError](builder.build).map {
            case Result.Failure(error) =>
                assert(
                    error.reason.contains(questKey),
                    s"expected the build failure to name the colliding key $questKey, got: ${error.reason}"
                )
            case other =>
                fail(s"expected build to abort EventCodecConfigurationError naming $questKey, got: $other")
        }
    }

    "codecs() choice flows into build (observable in the stored value framing)" in {
        val journalId = freshJournalId("codecs")
        val qsStream  = valid(Event.StreamId("codecs-qs"))
        val pjStream  = valid(Event.StreamId("codecs-pj"))
        for
            log <- EventLog.builder[QuestEvent](journalId)
                .codecs(binary = MsgPack())
                .define[QuestStarted](stream = Event.StreamSelector.constant(qsStream))
                .define[PartyJoined](stream = Event.StreamSelector.constant(pjStream))
                .build
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                import log.given
                Journal.run(backend) {
                    for
                        _       <- log.append(QuestStarted("q1"))
                        raw     <- Journal.read(qsStream, Event.StreamOffset.first, 10)
                        decoded <- log.read(qsStream, Event.StreamOffset.first, 10)
                    yield (raw, decoded)
                }
            }
        yield result match
            case Result.Success((raw, decoded)) =>
                assert(raw.size == 1)
                assert(decoded.size == 1)
                assert(questId(decoded(0).payload) == "started:q1")
                // MsgPack frames the single-field QuestStarted object as a fixmap (leading byte in
                // 0x80-0x8f); the default IonBinary would begin with the Ion version marker. The
                // custom .codecs(binary = MsgPack()) choice therefore reached EventLog.Codecs.schema.
                val leading = raw(0).payload.toArray(0) & 0xff
                assert(leading >= 0x80 && leading <= 0x8f, s"expected MsgPack fixmap framing, got leading byte $leading")
            case other => fail(s"expected success, got: $other")
        end for
    }

    "enum and sealed domain types both build under derivedSum and round-trip" in {
        val enumJournalId   = freshJournalId("enum")
        val sealedJournalId = freshJournalId("sealed")
        val colorStream     = valid(Event.StreamId("color-builder"))
        val shapeStream     = valid(Event.StreamId("shape-builder"))
        for
            colorLog <- EventLog.builder[ColorEvent](enumJournalId)
                .define[ColorEvent.Red](stream = Event.StreamSelector.constant(colorStream))
                .define[ColorEvent.Green](stream = Event.StreamSelector.constant(colorStream))
                .build
            shapeLog <- EventLog.builder[ShapeEvent](sealedJournalId)
                .define[ShapeEvent.Circle](stream = Event.StreamSelector.constant(shapeStream))
                .define[ShapeEvent.Square](stream = Event.StreamSelector.constant(shapeStream))
                .build
            colorBackend <- Journal.Backend.inMemory
            shapeBackend <- Journal.Backend.inMemory
            colorResult <- Abort.run[JournalError] {
                import colorLog.given
                Journal.run(colorBackend) {
                    for
                        _      <- colorLog.append(ColorEvent.Red(1): ColorEvent.Red)
                        events <- colorLog.read(colorStream, Event.StreamOffset.first, 10)
                    yield events
                }
            }
            shapeResult <- Abort.run[JournalError] {
                import shapeLog.given
                Journal.run(shapeBackend) {
                    for
                        _      <- shapeLog.append(ShapeEvent.Circle(3): ShapeEvent.Circle)
                        events <- shapeLog.read(shapeStream, Event.StreamOffset.first, 10)
                    yield events
                }
            }
        yield
            colorResult match
                case Result.Success(events) =>
                    assert(events.size == 1)
                    assert(events(0).payload == ColorEvent.Red(1))
                case other => fail(s"expected enum success, got: $other")
            end match
            shapeResult match
                case Result.Success(events) =>
                    assert(events.size == 1)
                    assert(events(0).payload == ShapeEvent.Circle(3))
                case other => fail(s"expected sealed success, got: $other")
            end match
        end for
    }

end EventLogBuilderTest
