package kyo

final case class FjEvent(name: String, value: Int) derives Schema, CanEqual

class FileJournalTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("valid identifier"))
    private def env(n: Int, md: Event.Metadata = Event.Metadata.empty): Event.New =
        Event.New(valid(Event.Id(s"e-$n")), valid(Event.Type("T")), Span.from(s"payload-$n".getBytes("UTF-8")), md)

    private def off(value: Long): Event.StreamOffset = valid(Event.StreamOffset(value))

    private def journalId(suffix: String)(using Frame): JournalId =
        JournalId.validate(s"fj-test-$suffix").getOrElse(throw new AssertionError("valid journal id"))

    private def binaryConfiguration(suffix: String, options: FileJournal.Options = FileJournal.Options.default)(using
        Frame
    ) =
        for
            codecs        <- EventLogCodecs.bytes()
            configuration <- FileJournal.Binary.configuration(journalId(suffix), codecs, options)
        yield configuration

    // A fresh temp root. Path.tempDir carries PathWrite & Sync & Scope; Path.run discharges
    // PathWrite and adds Abort[FileException]. A temp-dir failure is test-infra breakage, surfaced
    // as a defect. Scope propagates so Scope-managed resources (e.g. LOCK) live until the test ends.
    private def freshDir(prefix: String)(using Frame): Path < (Sync & Scope) =
        Abort.run[FileException](Path.run(Path.tempDir(prefix))).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    // Bind a journal op, surfacing any modelled failure as a test defect and keeping the backend's Scope open
    // so it lives until the leaf finishes (the runner closes the baseline Scope of kyo.test.Test).
    private def discharge[A](effect: A < (Sync & Scope & Abort[JournalError]))(using Frame): A < (Sync & Scope) =
        Abort.run[JournalError](effect).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new AssertionError(s"unexpected journal failure: $err")
            case panic: Result.Panic => throw panic.exception
        }

    // Discharges an EventLog program's row, which raises Abort[EventLog.PreparationFailure] in
    // addition to the JournalError family (discharge above only covers JournalError).
    private def dischargeLog[A](effect: A < (Sync & Abort[JournalError | EventLog.PreparationFailure]))(using
        Frame
    ): A < Sync =
        Abort.run[JournalError | EventLog.PreparationFailure](effect).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new AssertionError(s"unexpected journal failure: $err")
            case panic: Result.Panic => throw panic.exception
        }

    private def appendAll(backend: Journal.Backend[Sync], streamId: Event.StreamId, range: Range)(using
        Frame
    )
        : Unit < (Sync & Abort[JournalError]) =
        Kyo.foreach(range.toList)(n => backend.append(streamId, ExpectedOffset.Any, Chunk(env(n)))).map(_ => ())

    // The on-disk stream directory names under streams/ (raw list; the safe unsafe-tier read is fine in a test).
    private def streamDirNames(dir: Path)(using Frame): Chunk[String] < Sync =
        // Unsafe: directory listing for an on-disk injectivity assertion.
        Sync.Unsafe.defer {
            (dir / "streams").unsafe.list() match
                case Result.Success(paths) => paths.map(_.parts.last)
                case Result.Failure(err)   => throw err
                case panic: Result.Panic   => throw panic.exception
        }

    // Make dir/LOCK a directory so opening the LOCK file channel throws an IOException: this is the failed-open
    // path that carries a present cause (a plain non-directory root returns an Absent-cause error instead).
    private def lockAsDirectory(dir: Path)(using Frame): Unit < Sync =
        // Unsafe: create the root and a directory where the LOCK file is expected.
        Sync.Unsafe.defer {
            discard(dir.unsafe.mkDir())
            discard((dir / "LOCK").unsafe.mkDir())
        }

    "rotation and cross-segment read" - {
        "reads a slice straddling a rotation boundary in order, and writes an oversized record" in {
            // Tiny segmentSize so 20 single-event batches force >= 1 rotation; read a slice that starts in
            // segment 0 and ends in a later segment and assert contiguity, ordering, and payloads.
            // Then append one oversized record (payload > segmentSize) and read it back.
            val sid = valid(Event.StreamId("s"))
            val big = Event.New(valid(Event.Id("big")), valid(Event.Type("T")), Span.from(new Array[Byte](512)), Event.Metadata.empty)
            for
                dir    <- freshDir("fj-rot")
                config <- binaryConfiguration("rot", FileJournal.Options(fsync = FileJournal.Fsync.Always, segmentSize = 256L.bytes))
                backend <-
                    discharge(Journal.Backend.file(dir, config))
                _     <- discharge(appendAll(backend, sid, 0 until 20))
                slice <- discharge(backend.read(sid, off(8), 6))
                _     <- discharge(backend.append(sid, ExpectedOffset.Any, Chunk(big)))
                back  <- discharge(backend.read(sid, off(20), 1))
            yield
                assert(slice.map(_.offset.value) == (8L to 13L).toList)
                assert(slice.map(e => new String(e.payload.toArray, "UTF-8")) == (8 to 13).map(n => s"payload-$n").toList)
                assert(back.map(_.offset.value) == List(20L))
                assert(back.head.payload.size == 512)
            end for
        }
    }

    "streamId encoding" - {
        "maps a/b and a%2Fb to independent on-disk directories and contents" in {
            // "a/b" and "a%2Fb" must map to two distinct on-disk directory names and never collide; each
            // stream reads back only its own event.
            val idSlash = valid(Event.StreamId("a/b"))
            val idPct   = valid(Event.StreamId("a%2Fb"))
            for
                dir      <- freshDir("fj-sid")
                config   <- binaryConfiguration("sid")
                backend  <- discharge(Journal.Backend.file(dir, config))
                _        <- discharge(backend.append(idSlash, ExpectedOffset.Any, Chunk(env(0))))
                _        <- discharge(backend.append(idPct, ExpectedOffset.Any, Chunk(env(1))))
                slashEvs <- discharge(backend.read(idSlash, Event.StreamOffset.first, 10))
                pctEvs   <- discharge(backend.read(idPct, Event.StreamOffset.first, 10))
                names    <- streamDirNames(dir)
            yield
                assert(slashEvs.map(e => new String(e.payload.toArray, "UTF-8")) == List("payload-0"))
                assert(pctEvs.map(e => new String(e.payload.toArray, "UTF-8")) == List("payload-1"))
                assert(names.toSet.size == 2)
            end for
        }
    }

    "single-owner lock" - {
        "a second open of a held root fails, and a new open succeeds after the first closes" in {
            // Open the root in a scope; while it is held, open the same root again and assert the second open
            // fails. After the first scope closes and releases the LOCK, a third open succeeds.
            for
                dir    <- freshDir("fj-lock")
                config <- binaryConfiguration("lock")
                second <- Scope.run(Journal.Backend.file(dir, config).map(_ =>
                    Abort.run[JournalStorageError](Journal.Backend.file(dir, config))
                ))
                third <- Scope.run(Abort.run[JournalStorageError](Journal.Backend.file(dir, config)))
            yield
                assert(second match
                    case Result.Failure(_: JournalStorageError) => true;
                    case _                                      => false)
                assert(third match
                    case Result.Success(_) => true;
                    case _                 => false)
        }
    }

    "failed open" - {
        "surfaces the underlying IOException as the cause when the LOCK file cannot be opened" in {
            // Put a directory where the LOCK file is expected so opening its FileChannel throws an
            // IOException; assert Backend.file fails with a JournalStorageError whose cause is a present
            // java.io.IOException. Deterministic, no fault wrapper.
            for
                dir    <- freshDir("fj-fail")
                config <- binaryConfiguration("fail")
                _      <- lockAsDirectory(dir)
                opened <- Scope.run(Abort.run[JournalStorageError](Journal.Backend.file(dir, config)))
            yield
                val err = opened match
                    case Result.Failure(e: JournalStorageError) => e
                    case other                                  => throw new AssertionError(s"expected JournalStorageError, got $other")
                assert(err.cause match
                    case Present(t) => t.isInstanceOf[java.io.IOException];
                    case Absent     => false)
        }
    }

    // --- typed FileJournal.Configuration leaves (checkpoint 3 acceptance) -----------------------

    private val fjEventStreamId: Event.StreamId              = valid(Event.StreamId("fj-event-stream"))
    private val fjEventStream: Event.StreamSelector[FjEvent] = Event.StreamSelector.constant(fjEventStreamId)
    private given Event.Definition[FjEvent, FjEvent] =
        Event.Definition.schema[FjEvent, FjEvent](fjEventStream)

    "Binary.configuration" - {
        "opens a .seg backend and round-trips" in {
            val event = FjEvent("alice", 1)
            for
                dir           <- freshDir("fj-binary-roundtrip")
                jid           <- Sync.defer(journalId("binary-roundtrip"))
                codecs        <- EventLogCodecs.schema[FjEvent]()
                configuration <- FileJournal.Binary.configuration(jid, codecs)
                log           <- EventLog.init(codecs, jid)
                backend       <- discharge(Journal.Backend.file(dir, configuration))
                decoded <- dischargeLog(Journal.run(backend) {
                    for
                        _      <- log.append(event)
                        events <- log.read(fjEventStreamId, Event.StreamOffset.first, 10)
                    yield events
                })
                segPresent <- Sync.Unsafe.defer {
                    (dir / "streams" / fjEventStreamId.value / "00000000000000000000.seg").unsafe.exists()
                }
            yield
                assert(segPresent, "expected a .seg segment file to exist after append")
                assert(decoded.size == 1)
                assert(decoded(0).payload == event)
            end for
        }
    }

    "Jsonl.configuration" - {
        "opens a .jsonl backend with JSON payloads" in {
            val event = FjEvent("bob", 2)
            for
                dir           <- freshDir("fj-jsonl-roundtrip")
                jid           <- Sync.defer(journalId("jsonl-roundtrip"))
                codecs        <- EventLogCodecs.schema[FjEvent]()
                configuration <- FileJournal.Jsonl.configuration(jid, codecs)
                log           <- EventLog.init(codecs, jid)
                backend       <- discharge(Journal.Backend.file(dir, configuration))
                _             <- dischargeLog(Journal.run(backend)(log.append(event)))
                line <- Sync.Unsafe.defer {
                    val segPath = dir / "streams" / fjEventStreamId.value / "00000000000000000000.jsonl"
                    val bytes   = segPath.unsafe.readBytes().getOrElse(Span.empty[Byte])
                    new String(bytes.toArray, "UTF-8").linesIterator.next()
                }
            yield
                assert(line.contains("\"offset\":0"))
                assert(line.contains("\"eventId\":"))
                assert(line.contains("\"eventType\":"))
                assert(line.contains("\"metadata\":"))
                assert(line.contains("\"payload\":"))
                assert(line.contains("\"crc\":"))
                assert(line.contains("\"name\":\"bob\""))
                assert(line.contains("\"value\":2"))
            end for
        }
    }

    "profileName and MANIFEST" - {
        "Binary.configuration resolves the literal profileName and the MANIFEST carries it (R-136)" in {
            for
                binaryJid    <- Sync.defer(journalId("profilename-binary"))
                jsonlJid     <- Sync.defer(journalId("profilename-jsonl"))
                codecs       <- EventLogCodecs.schema[FjEvent]()
                binaryConfig <- FileJournal.Binary.configuration(binaryJid, codecs)
                jsonlConfig  <- FileJournal.Jsonl.configuration(jsonlJid, codecs)
                binaryDir    <- freshDir("fj-profilename-binary")
                jsonlDir     <- freshDir("fj-profilename-jsonl")
                _            <- discharge(Journal.Backend.file(binaryDir, binaryConfig))
                _            <- discharge(Journal.Backend.file(jsonlDir, jsonlConfig))
                binaryManifest <- Sync.Unsafe.defer {
                    val bytes = (binaryDir / "MANIFEST").unsafe.readBytes().getOrElse(Span.empty[Byte])
                    new String(bytes.toArray, "UTF-8")
                }
                jsonlManifest <- Sync.Unsafe.defer {
                    val bytes = (jsonlDir / "MANIFEST").unsafe.readBytes().getOrElse(Span.empty[Byte])
                    new String(bytes.toArray, "UTF-8")
                }
            yield
                assert(binaryConfig.profileName == "binary")
                assert(jsonlConfig.profileName == "jsonl")
                assert(binaryManifest.contains("format: binary"))
                assert(jsonlManifest.contains("format: jsonl"))
        }

        "Binary.configuration and Jsonl.configuration resolve profileName directly with no ProfileName typeclass in scope" in {
            for
                binaryJid    <- Sync.defer(journalId("profilename-direct-binary"))
                jsonlJid     <- Sync.defer(journalId("profilename-direct-jsonl"))
                codecs       <- EventLogCodecs.schema[FjEvent]()
                binaryConfig <- FileJournal.Binary.configuration(binaryJid, codecs)
                jsonlConfig  <- FileJournal.Jsonl.configuration(jsonlJid, codecs)
            yield
                assert(binaryConfig.profileName == "binary")
                assert(jsonlConfig.profileName == "jsonl")
                assert(binaryConfig.profileName != jsonlConfig.profileName)
        }
    }

    "Codec.mediaType" - {
        "derives Configuration's payloadMediaType/metadataMediaType for Binary and Jsonl and the MANIFEST round-trips them (R-135)" in {
            for
                binaryDir    <- freshDir("fj-mediatype-binary")
                jsonlDir     <- freshDir("fj-mediatype-jsonl")
                binaryJid    <- Sync.defer(journalId("mediatype-binary"))
                jsonlJid     <- Sync.defer(journalId("mediatype-jsonl"))
                codecs       <- EventLogCodecs.schema[FjEvent](binary = IonBinary(), json = Json(), metadata = IonBinary())
                binaryConfig <- FileJournal.Binary.configuration(binaryJid, codecs)
                jsonlConfig  <- FileJournal.Jsonl.configuration(jsonlJid, codecs)
                _            <- discharge(Journal.Backend.file(binaryDir, binaryConfig))
                _            <- discharge(Journal.Backend.file(jsonlDir, jsonlConfig))
                binaryManifest <- Sync.Unsafe.defer {
                    val bytes = (binaryDir / "MANIFEST").unsafe.readBytes().getOrElse(Span.empty[Byte])
                    new String(bytes.toArray, "UTF-8")
                }
                jsonlManifest <- Sync.Unsafe.defer {
                    val bytes = (jsonlDir / "MANIFEST").unsafe.readBytes().getOrElse(Span.empty[Byte])
                    new String(bytes.toArray, "UTF-8")
                }
            yield
                assert(binaryConfig.payloadMediaType == "application/vnd.amazon.ion")
                assert(binaryConfig.metadataMediaType == "application/vnd.amazon.ion")
                assert(jsonlConfig.payloadMediaType == "application/json")
                assert(binaryManifest.contains(s"payload-media-type: ${binaryConfig.payloadMediaType}"))
                assert(binaryManifest.contains(s"metadata-media-type: ${binaryConfig.metadataMediaType}"))
                assert(jsonlManifest.contains(s"payload-media-type: ${jsonlConfig.payloadMediaType}"))
        }
    }

    "MANIFEST-less root" - {
        "a MANIFEST-less root with existing streams/ entries still opens, inferring Binary with default media types (INV-PATHCAP-19)" in {
            for
                dir    <- freshDir("fj-manifestless")
                jid    <- Sync.defer(journalId("manifestless"))
                codecs <- EventLogCodecs.bytes()
                config <- FileJournal.Binary.configuration(jid, codecs)
                _ <- Sync.Unsafe.defer {
                    // Simulate a crash between segment creation and the first-open MANIFEST write.
                    discard((dir / "streams").unsafe.mkDir())
                    discard((dir / "streams" / "0000000000000001").unsafe.mkDir())
                }
                opened <- Scope.run(Abort.run[JournalStorageError](Journal.Backend.file(dir, config)))
            yield
                assert(opened match
                    case Result.Success(_) => true
                    case _                 => false)
                import AllowUnsafe.embrace.danger
                assert(!(dir / "MANIFEST").unsafe.exists())
        }
    }

    "direct Journal.Backend[S] subclass (tier two)" - {
        // The in-memory backend is a genuinely hand-written Journal.Backend[Sync] that does not
        // route through FileJournalCore/SegmentCodec at all (a distinct implementation, not a
        // configuration of the shared segmented-append engine), so it demonstrates the direct
        // Journal.Backend[S] SPI. The full contract suite is already exercised end to end by
        // InMemoryJournalBackendTest; this leaf asserts the same contract slice directly rather
        // than re-running the whole suite twice.
        "append/read/streamInfo/per-op-failure all pass against a hand-written backend" in {
            val sid = valid(Event.StreamId("direct-backend"))
            for
                backend    <- Journal.Backend.inMemory
                appended   <- Abort.run[JournalAppendFailure](backend.append(sid, ExpectedOffset.NoStream, Chunk(env(0))))
                conflicted <- Abort.run[JournalAppendFailure](backend.append(sid, ExpectedOffset.NoStream, Chunk(env(1))))
                read       <- Abort.run[JournalReadFailure](backend.read(sid, Event.StreamOffset.first, 10))
                info       <- Abort.run[JournalStreamInfoFailure](backend.streamInfo(sid))
            yield
                assert(appended match
                    case Result.Success(r) => r.firstOffset == off(0)
                    case _                 => false)
                assert(conflicted match
                    case Result.Failure(_: JournalConflictError) => true
                    case _                                       => false)
                assert(read match
                    case Result.Success(evs) => evs.map(_.offset.value) == List(0L)
                    case _                   => false)
                assert(info match
                    case Result.Success(_: StreamInfo.Existing) => true
                    case _                                      => false)
            end for
        }
    }

    "absent-symbol guards" - {
        "SegmentFormat/Config/Assembly, EventPayloadCodec, and EventMetadataCodec are absent" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                val a = kyo.FileJournal.SegmentFormat.Binary
                val b: kyo.FileJournal.Config = ???
                val c: kyo.FileJournal.Assembly = ???
                val d: kyo.EventPayloadCodec = ???
                val e: kyo.EventMetadataCodec = ???
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("SegmentFormat")))
            assert(errors.exists(_.contains("Config")))
            assert(errors.exists(_.contains("Assembly")))
            assert(errors.exists(_.contains("EventPayloadCodec")))
            assert(errors.exists(_.contains("EventMetadataCodec")))
        }

        "the SegmentedFamily/SegmentedComponents/Components/segmented apparatus is absent from the public FileJournal surface" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                val a: kyo.FileJournal.SegmentedFamily = ???
                val b: kyo.FileJournal.SegmentedComponents[kyo.FileJournal.Binary] = ???
                val c: kyo.FileJournal.Components[kyo.FileJournal.Binary] = ???
                val d = kyo.FileJournal.segmented
                val e: kyo.StorageEncoding[kyo.FileJournal.Binary] = ???
                val f: kyo.Framing[kyo.FileJournal.Binary] = ???
                val g: kyo.FileLifecycle[kyo.FileJournal.Binary] = ???
                val h: kyo.SegmentedTopology[kyo.FileJournal.Binary] = ???
                val i: kyo.Discovery[kyo.FileJournal.Binary] = ???
                val j: kyo.CommitProtocol[kyo.FileJournal.Binary] = ???
                val k: kyo.RecoveryProtocol[kyo.FileJournal.Binary] = ???
                val l: kyo.IndexProtocol[kyo.FileJournal.Binary] = ???
                val m: kyo.RotationOrPublication[kyo.FileJournal.Binary] = ???
                val n: kyo.Validation[kyo.FileJournal.Binary] = ???
                val o: kyo.RootMarker[kyo.FileJournal.Binary] = ???
                val p: kyo.Swmr[kyo.FileJournal.Binary] = ???
                val q: kyo.internal.EngineComponents.type = ???
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("SegmentedFamily")))
            assert(errors.exists(_.contains("SegmentedComponents")))
            assert(errors.exists(_.contains("segmented")))

            // Configuration has exactly the six locked fields: journalId, codecs, options,
            // profileName, metadataMediaType, payloadMediaType (no profile: P, no components).
            // Compile-time field-set check (cross-platform; no JVM reflection): the six named
            // fields resolve cleanly, and a `profile`/`components` accessor does not.
            val presenceErrors = scala.compiletime.testing.typeCheckErrors(
                """
                val c: kyo.FileJournal.Configuration[Int] = ???
                val _: kyo.JournalId = c.journalId
                val _: kyo.EventLog.Codecs[Int] = c.codecs
                val _: kyo.FileJournal.Options = c.options
                val _: String = c.profileName
                val _: String = c.metadataMediaType
                val _: String = c.payloadMediaType
                """
            ).map(_.message)
            assert(presenceErrors.isEmpty, s"expected all six Configuration fields to resolve cleanly, got: $presenceErrors")
            val absenceErrors = scala.compiletime.testing.typeCheckErrors(
                """
                val c: kyo.FileJournal.Configuration[Int] = ???
                c.profile
                """
            ).map(_.message)
            assert(absenceErrors.nonEmpty, "Configuration must not carry a profile: P field")
            val componentsErrors = scala.compiletime.testing.typeCheckErrors(
                """
                val c: kyo.FileJournal.Configuration[Int] = ???
                c.components
                """
            ).map(_.message)
            assert(componentsErrors.nonEmpty, "Configuration must not carry a components field")
        }

        "FileJournal.Configuration has no P (compile-shape): the two-parameter form does not resolve" in {
            val twoParamErrors = scala.compiletime.testing.typeCheckErrors(
                """
                val c: kyo.FileJournal.Configuration[Int, kyo.FileJournal.Binary] = ???
                """
            ).map(_.message)
            assert(twoParamErrors.nonEmpty, "expected the two-parameter Configuration form to fail to type-check")
            val oneParamErrors = scala.compiletime.testing.typeCheckErrors(
                """
                val c: kyo.FileJournal.Configuration[Int] = ???
                """
            ).map(_.message)
            assert(oneParamErrors.isEmpty, s"expected the one-parameter Configuration form to type-check cleanly, got: $oneParamErrors")
        }

        "no SegmentCodec or BoundCodecs symbol is present in internal/" in {
            val absentErrors = scala.compiletime.testing.typeCheckErrors(
                """
                val a: kyo.internal.SegmentCodec = ???
                val b: kyo.internal.BinarySegmentCodec = ???
                val c: kyo.internal.BoundCodecs[Int] = ???
                """
            ).map(_.message)
            assert(absentErrors.nonEmpty)
            assert(absentErrors.exists(_.contains("SegmentCodec")))
            assert(absentErrors.exists(_.contains("BoundCodecs")))
            val presentErrors = scala.compiletime.testing.typeCheckErrors(
                """
                val a: kyo.internal.SegmentFormat = ???
                val b: kyo.internal.BinarySegmentFormat = ???
                val c: kyo.internal.BoundValueAccess[Int] = ???
                """
            ).map(_.message)
            assert(
                presentErrors.isEmpty,
                s"expected SegmentFormat/BinarySegmentFormat/BoundValueAccess to resolve cleanly, got: $presentErrors"
            )
        }

        "FileJournal.Profile, Binary/Jsonl (as sealed traits), and ProfileName are absent (compile-negative)" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                val a: kyo.FileJournal.Profile = ???
                val b: kyo.FileJournal.ProfileName[kyo.FileJournal.Binary] = ???
                val c = new kyo.FileJournal.Binary {}
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("Profile")))
            assert(errors.exists(_.contains("ProfileName")))
            val stillResolveErrors = scala.compiletime.testing.typeCheckErrors(
                """
                given kyo.Frame = kyo.Frame.internal
                val a = kyo.FileJournal.Binary.configuration(???, ???)
                val b = kyo.FileJournal.Jsonl.configuration(???, ???)
                """
            ).map(_.message)
            assert(
                stillResolveErrors.isEmpty,
                s"expected Binary/Jsonl.configuration to resolve with no ProfileName typeclass in scope, got: $stillResolveErrors"
            )
        }
    }

    "lifecycle-container surface" - {
        "FileJournal.XmlContainer and FileJournal.RollingFile are absent" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                val a: kyo.FileJournal.XmlContainer = ???
                val b = kyo.FileJournal.RollingFile.configuration
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("XmlContainer")))
            assert(errors.exists(_.contains("RollingFile")))
        }
    }

end FileJournalTest
