package kyo

import kyo.internal.SqlTestBackend
import kyo.internal.SqlTestBackends

/** Base class for the backend-agnostic conformance suites: it runs a body once per available backend, mirroring kyo-pod's
  * `BasePodTest.runBackends`, over the DISCOVERED descriptor set rather than a hardcoded engine list.
  *
  * A conformance suite extends this and writes its behavior once through [[forEachBackend]]; the base opens the client, installs it as the
  * ambient [[DB]] client, and hands the body the backend descriptor, that client, and a fresh schema. It never names an engine: a body
  * branches on the descriptor's capability flags, and a leaf is named by the descriptor's own label.
  *
  * [[agreeAcrossBackends]] is the other shape, for a behaviour whose finding IS the comparison: it runs the body on every backend inside ONE
  * leaf and asserts they answered alike, which is the only shape a known divergence can be stated in. Reach for it when the leaf's claim is
  * "the engines agree" and for [[forEachBackend]] when the claim is "each engine does this".
  *
  * Container operations share one daemon, so leaves run sequentially.
  */
abstract class SqlBackendTest extends SqlContainerTest:

    override def timeout: Duration = 5.minutes

    /** Registers one leaf per available backend, each running `f` against that backend's opened client and fresh schema. Analogue of
      * `BasePodTest.runBackends`, with the leaf named by the descriptor's own label.
      *
      * When no backend is available it registers a single FAILING leaf rather than zero leaves, so a run with no reachable container is RED
      * rather than a green run with no coverage.
      *
      * `pendingUntilFixed` marks the generated leaves, which is the shape a defect present on EVERY backend needs. The decorator has to be
      * applied here, on each leaf this registers, because the enclosing group does not pass it down: a group written
      * `"...".pendingUntilFixed(r) - { forEachBackend() { ... } }` registers ordinary leaves and reports plain failures. Reach for it only
      * when every backend is wrong in the same way; a defect on one engine and not the other belongs in [[agreeAcrossBackends]], whose leaf
      * can state the disagreement.
      */
    def forEachBackend(config: SqlConfig = SqlConfig(), pendingUntilFixed: Maybe[String] = Absent)(
        f: (SqlTestBackend, SqlClient, SqlTestBackend.Schema) => kyo.test.AssertScope ?=> Unit < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame): Unit =
        val backends = SqlTestBackends.available
        if backends.isEmpty then
            "no SQL backend available" in {
                fail(
                    "no SQL backend available: expected at least one registered test-backend descriptor with a reachable container runtime"
                )
            }
        else
            backends.foreach { backend =>
                val name = s"[${backend.label}]"
                pendingUntilFixed match
                    case Present(reason) => name.pendingUntilFixed(reason) in { runOn(backend, config)(f) }
                    case Absent          => name in { runOn(backend, config)(f) }
                end match
            }
        end if
    end forEachBackend

    /** Runs `f` on every available backend inside ONE leaf and asserts they all answered the same thing.
      *
      * [[forEachBackend]] gives each backend its own leaf, so a behaviour one engine has and the other lacks is a green leaf beside a red one,
      * with no single node whose outcome is "these disagree". Here the body answers a STRING describing what it observed, and the assertion is
      * that every backend answered the same one.
      *
      * A body that fails answers its exception's class name, so "both refuse this" is agreement. The message is excluded: engines word errors
      * differently, and requiring identical wording would report a divergence that is not one.
      *
      * `expected` pins WHAT they must answer, and a leaf states it whenever it knows: agreement alone is satisfied by two engines that are both
      * wrong in the same way. Fewer than two backends fails rather than passing vacuously.
      */
    def agreeAcrossBackends(config: SqlConfig = SqlConfig(), expected: Maybe[String] = Absent)(
        f: (SqlTestBackend, SqlClient, SqlTestBackend.Schema) => String < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame, kyo.test.AssertScope): Unit < (Async & Abort[SqlException | ContainerException] & Scope) =
        val backends = SqlTestBackends.available
        if backends.size < 2 then
            fail(
                s"a cross-engine agreement leaf compares at least two backends, and this run discovered ${backends.map(_.label)}: " +
                    "asserted over one engine every answer agrees with itself and the leaf verifies nothing"
            )
        else
            Kyo.foreach(Chunk.from(backends))(backend => answerOf(backend, config)(f).map(backend.label -> _)).map { answers =>
                val rendered = answers.map((label, answer) => s"$label answered $answer").mkString("; ")
                expected match
                    case Present(one) =>
                        assert(
                            answers.forall(_._2 == one),
                            s"every backend must answer '$one', and $rendered"
                        )
                    case Absent =>
                        assert(answers.map(_._2).distinct.size == 1, s"the backends disagree: $rendered")
                end match
            }
        end if
    end agreeAcrossBackends

    /** What one backend answers to the program `f` describes: the description it produced, or the name of the class it failed with. */
    private def answerOf(backend: SqlTestBackend, config: SqlConfig)(
        f: (SqlTestBackend, SqlClient, SqlTestBackend.Schema) => String < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame): String < (Async & Abort[SqlException | ContainerException] & Scope) =
        Scope.run {
            backend.withFreshSchema { schema =>
                SqlClient.initUnscoped(schema.url, config).flatMap { client =>
                    Scope.ensure(client.close).andThen {
                        Abort.run[SqlException](DB.run(client)(f(backend, client, schema))).map {
                            case Result.Success(answer) => answer
                            case Result.Failure(ex)     => s"refused with ${ex.getClass.getSimpleName}"
                            case Result.Panic(ex)       => s"panicked with ${ex.getClass.getSimpleName}"
                        }
                    }
                }
            }
        }

    private def runOn(backend: SqlTestBackend, config: SqlConfig)(
        f: (SqlTestBackend, SqlClient, SqlTestBackend.Schema) => kyo.test.AssertScope ?=> Unit < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame, kyo.test.AssertScope) =
        Scope.run {
            backend.withFreshSchema { schema =>
                SqlClient.initUnscoped(schema.url, config).flatMap { client =>
                    Scope.ensure(client.close).andThen(DB.run(client)(f(backend, client, schema)))
                }
            }
        }

end SqlBackendTest
