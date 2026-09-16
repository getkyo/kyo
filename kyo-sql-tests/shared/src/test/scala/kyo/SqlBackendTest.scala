package kyo

import kyo.internal.SqlTestBackend
import kyo.internal.SqlTestBackends
import kyo.test.TestBuilder

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
      *
      * `where` restricts the leaves to backends with a capability, for a behavior only some engines have. Use it in complementary pairs, one
      * call per side, so every backend is claimed by exactly one leaf and the engine that lacks the capability still asserts what it does
      * instead. It reads a descriptor FLAG, never an engine name.
      *
      * `timeout` bounds each generated leaf, for a body whose failure mode is a WEDGE rather than a wrong answer. It changes nothing about
      * what passes or fails: a backend that works returns in milliseconds either way. What it buys is that a stuck session reports red in
      * seconds instead of holding the whole run at the suite default, which is long enough to make a battery run unusable.
      */
    def forEachBackend(
        config: SqlConfig = SqlConfig(),
        pendingUntilFixed: Maybe[String] = Absent,
        where: SqlTestBackend => Boolean = _ => true,
        timeout: Maybe[Duration] = Absent
    )(
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
            // A capability filter matching nothing registers nothing, which is not a coverage hole: `where` is meant to be used with
            // its complement, so a backend the filter excludes is claimed by the leaf stating what it does instead. The empty-backends
            // case above stays red because THAT one is missing infrastructure rather than a capability a backend honestly lacks.
            backends.filter(where).foreach { backend =>
                val base = timeout.fold(TestBuilder(s"[${backend.label}]"))(d => s"[${backend.label}]".timeout(d))
                // Named lambda rather than eta-expansion: `base.pendingUntilFixed` resolves to the field of that name, not the decorator.
                val leaf = pendingUntilFixed.fold(base)(reason => base.pendingUntilFixed(reason))
                leaf in { runOn(backend, config)(f) }
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
      * wrong in the same way. It also decides what this leaf needs to be worth running: a pinned leaf asserts something over one backend, an
      * unpinned one does not, so only the unpinned case fails below two. No backends at all always fails.
      *
      * `where` restricts the comparison to backends a shared answer is meaningful for. Agreement is only a finding among engines the property
      * applies to: an engine that legitimately behaves otherwise, because it honours a different set of levels or defaults to another one, is
      * not disagreeing but answering a different question, and including it would turn a real difference into a false failure. Use it in
      * complementary pairs so no backend is dropped, and it reads a descriptor FLAG, never an engine name.
      */
    def agreeAcrossBackends(
        config: SqlConfig = SqlConfig(),
        expected: Maybe[String] = Absent,
        where: SqlTestBackend => Boolean = _ => true
    )(
        f: (SqlTestBackend, SqlClient, SqlTestBackend.Schema) => String < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame, kyo.test.AssertScope): Unit < (Async & Abort[SqlException | ContainerException] & Scope) =
        val backends = SqlTestBackends.available.filter(where)
        // Vacuity is the thing being guarded against, and it depends on `expected`. An UNPINNED leaf over one backend
        // verifies nothing, because every answer agrees with itself. A PINNED leaf over one backend still asserts that
        // this engine answers this exact string, which is the half that survives two engines being wrong together, so
        // running it is strictly better than failing it. Guarding both alike would switch off real coverage on every
        // platform where only one engine is reachable.
        if backends.isEmpty || (backends.sizeIs < 2 && expected.isEmpty) then
            fail(
                s"a cross-engine agreement leaf needs either two backends or a pinned expectation, and this run discovered " +
                    s"${backends.map(_.label)} with ${if expected.isEmpty then "no expectation" else "an expectation"}: " +
                    "asserted over one engine with nothing pinned, every answer agrees with itself and the leaf verifies nothing"
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
