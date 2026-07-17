package kyo

import kyo.AllowUnsafe.embrace.danger
import kyo.internal.*

class EventInterpolatorsTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private def validStreamName(value: String): Event.StreamName =
        Abort.run[EventLog.PreparationFailure](Event.StreamName(value)).eval match
            case Result.Success(name) => name
            case other                => throw new AssertionError(s"expected a valid stream name, got: $other")

    private def validJournalId(value: String): JournalId =
        JournalId.validate(value)(using Frame.internal).getOrElse(throw new AssertionError("expected a valid journal id"))

    "key" - {
        "a valid literal equals the runtime constructor's result" in {
            assert(key"trace.correlation_id" == valid(Event.Metadata.Key("trace.correlation_id")))
        }
        "an empty literal is a compile error" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                import kyo.internal.*
                key""
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("invalid Event.Metadata.Key literal")))
        }
        "an interpolated argument is a compile error naming the runtime constructor" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                import kyo.internal.*
                val x = "trace"
                key"$x.correlation_id"
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("does not accept interpolated arguments")))
            assert(errors.exists(_.contains("Event.Metadata.Key(value)")))
        }
    }

    "streamId" - {
        "a valid literal equals the runtime constructor's result" in {
            assert(streamId"users-1" == valid(Event.StreamId("users-1")))
        }
        "an empty literal is a compile error" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                import kyo.internal.*
                streamId""
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("invalid Event.StreamId literal")))
        }
    }

    "streamName" - {
        "a valid literal equals the runtime constructor's result" in {
            assert(streamName"orders".value == validStreamName("orders").value)
        }
        "an empty literal is a compile error" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                import kyo.internal.*
                streamName""
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("invalid Event.StreamName literal")))
        }
    }

    "eventType" - {
        "a valid literal equals the runtime constructor's result" in {
            assert(eventType"UserRegistered" == valid(Event.Type("UserRegistered")))
        }
    }

    "eventId" - {
        "a valid literal equals the runtime constructor's result" in {
            assert(eventId"event-1" == valid(Event.Id("event-1")))
        }
        "an interpolated argument is a compile error naming the runtime constructor" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                import kyo.internal.*
                val n = 1
                eventId"event-$n"
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("does not accept interpolated arguments")))
            assert(errors.exists(_.contains("Event.Id(value)")))
        }
    }

    "journalId" - {
        "a valid literal equals the runtime constructor's result" in {
            assert(journalId"fleet-main" == validJournalId("fleet-main"))
        }
        "an empty literal is a compile error" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                import kyo.internal.*
                journalId""
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("invalid JournalId literal")))
        }
        "an interpolated argument is a compile error naming the runtime constructor" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                import kyo.internal.*
                val suffix = "main"
                journalId"fleet-$suffix"
                """
            ).map(_.message)
            assert(errors.nonEmpty)
            assert(errors.exists(_.contains("does not accept interpolated arguments")))
            assert(errors.exists(_.contains("JournalId(value)")))
        }
    }

    "single-source predicate" - {
        // Unsafe: JVM-only self-audit of this module's own compiled sources on disk (a dev-time
        // grep, not a runtime capability); mirrors kyo-website's WebsiteBuildGraphTest repo-root
        // and file-read helpers.
        def repoRoot(): Path =
            @scala.annotation.tailrec
            def loop(dir: Path): Path =
                if (dir / "build.sbt").unsafe.exists() then dir
                else
                    dir.parent match
                        case Maybe.Present(parent) => loop(parent)
                        case Maybe.Absent          => throw new RuntimeException("repo root with build.sbt not found")
            loop(Path(java.lang.System.getProperty("user.dir").nn))
        end repoRoot

        def sourceLines(relativePath: String): List[String] =
            (repoRoot() / "kyo-eventlog" / "shared" / "src" / "main" / "scala" / "kyo" / relativePath)
                .unsafe.read().getOrThrow.linesIterator.toList

        "each opaque type's isValid/validate predicate has exactly one definition, called by its paired EventInterpolatorMacros impl method, never re-implemented".onlyJvm in {
            val eventLines    = sourceLines("JournalEvent.scala")
            val metadataLines = sourceLines("JournalMetadata.scala")
            val macroLines    = sourceLines("internal/EventInterpolatorMacros.scala")

            val isValidDefCount  = (eventLines ++ metadataLines).count(_.trim.contains("def isValid"))
            val validateDefCount = (eventLines ++ metadataLines).count(_.trim.contains("def validate"))
            assert(
                isValidDefCount == 5,
                s"expected exactly five isValid definitions (Id, Type, StreamId, StreamName, Metadata.Key), found $isValidDefCount"
            )
            assert(validateDefCount == 1, s"expected exactly one validate definition (JournalId), found $validateDefCount")

            // The macro file itself defines no isValid/validate of its own: it only calls the
            // shared predicates each runtime constructor already calls.
            assert(!macroLines.exists(_.trim.contains("def isValid")))
            assert(!macroLines.exists(_.trim.contains("def validate")))

            val macroText = macroLines.mkString("\n")
            val pairedCalls = List(
                "Event.Metadata.Key.isValid(literal)",
                "Event.StreamId.isValid(literal)",
                "Event.StreamName.isValid(literal)",
                "Event.Type.isValid(literal)",
                "Event.Id.isValid(literal)",
                "JournalId.validate(literal)"
            )
            val missing = pairedCalls.filterNot(macroText.contains)
            assert(missing.isEmpty, s"macro impl missing calls to shared predicates: ${missing.mkString(", ")}")
        }
    }
end EventInterpolatorsTest
