package kyo.test

import java.util.concurrent.atomic.AtomicInteger
import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Env
import kyo.Maybe
import kyo.Scope
import kyo.Sync
import kyo.internal.Platform
import kyo.kernel.<
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Tests for `.onlyBrowser` and `.notBrowser`.
  *
  * The same test classes run on the JVM, Scala Native, Node, and in a browser, so each registration leaf states its expectation against
  * the host it finds itself on: a filter that holds lets the body run, one that does not reports `Cancelled` without running it.
  */
// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class HostFilterTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    final case class Db(url: String)

    private val browserHosts = Seq(Platform.Host.BrowserMain, Platform.Host.BrowserWorker)
    private val otherHosts   =
        Seq(Platform.Host.Jvm, Platform.Host.Native, Platform.Host.Node, Platform.Host.Bun, Platform.Host.Deno, Platform.Host.OtherJs)

    private def runLeaf[S <: kyo.test.Test[Any]](make: => S): Future[(Chunk[String], TestResult)] =
        LeafHarness.runLeafWithPath(make)

    /** Asserts `result` is what a test whose filters hold exactly `inBrowser` reports on this host, and that its body ran only if so. */
    private def expectHost(inBrowser: Boolean, result: TestResult, bodyRuns: AtomicInteger, reason: String) =
        if Platform.isBrowser == inBrowser then
            assert(result.isInstanceOf[TestResult.Passed], s"expected Passed, got $result")
            assert(bodyRuns.get() == 1)
        else
            result match
                case TestResult.Cancelled(actual, _) => assert(actual.startsWith(reason), s"unexpected reason: $actual")
                case other                           => fail(s"expected Cancelled, got $other")
            assert(bodyRuns.get() == 0, "a cancelled test's body must not run")
        end if
    end expectHost

    "unmet" - {
        "onlyBrowser holds in a browser page or worker and names every other host" in {
            def onlyBrowserOn(host: Platform.Host) = HostFilter.unmet(Chunk(HostFilter.OnlyBrowser), host)
            browserHosts.foreach(host => assert(onlyBrowserOn(host) == Maybe.empty))
            assert(onlyBrowserOn(Platform.Host.Jvm) == Maybe("runs only in a browser; this host is the JVM"))
            assert(onlyBrowserOn(Platform.Host.Native) == Maybe("runs only in a browser; this host is Scala Native"))
            assert(onlyBrowserOn(Platform.Host.Node) == Maybe("runs only in a browser; this host is Node"))
            assert(onlyBrowserOn(Platform.Host.Bun) == Maybe("runs only in a browser; this host is Bun"))
            assert(onlyBrowserOn(Platform.Host.Deno) == Maybe("runs only in a browser; this host is Deno"))
            assert(
                onlyBrowserOn(Platform.Host.OtherJs) ==
                    Maybe("runs only in a browser; this host is a JavaScript host that is neither Node, Bun, Deno, nor a browser")
            )
            Future.successful(succeed)
        }

        "notBrowser holds on every host but a browser" in {
            otherHosts.foreach(host => assert(HostFilter.unmet(Chunk(HostFilter.NotBrowser), host) == Maybe.empty))
            assert(
                HostFilter.unmet(Chunk(HostFilter.NotBrowser), Platform.Host.BrowserMain) ==
                    Maybe("does not run in a browser; this host is a browser page")
            )
            assert(
                HostFilter.unmet(Chunk(HostFilter.NotBrowser), Platform.Host.BrowserWorker) ==
                    Maybe("does not run in a browser; this host is a browser worker")
            )
            Future.successful(succeed)
        }

        "no filters hold everywhere, and contradictory filters hold nowhere" in {
            (browserHosts ++ otherHosts).foreach { host =>
                assert(HostFilter.unmet(Chunk.empty, host) == Maybe.empty)
                assert(HostFilter.unmet(Chunk(HostFilter.OnlyBrowser, HostFilter.NotBrowser), host).isDefined)
            }
            Future.successful(succeed)
        }

        "the first filter that does not hold gives the reason" in {
            assert(
                HostFilter.unmet(Chunk(HostFilter.NotBrowser, HostFilter.OnlyBrowser), Platform.Host.Node) ==
                    Maybe("runs only in a browser; this host is Node")
            )
            assert(
                HostFilter.unmet(Chunk(HostFilter.OnlyBrowser, HostFilter.NotBrowser), Platform.Host.BrowserMain) ==
                    Maybe("does not run in a browser; this host is a browser page")
            )
            Future.successful(succeed)
        }
    }

    "registration" - {
        "onlyBrowser on a name" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    "x".onlyBrowser in Sync.defer(runs.incrementAndGet()).andThen(succeed)
            }.map { case (path, result) =>
                assert(path == Chunk("x"))
                expectHost(inBrowser = true, result, runs, "runs only in a browser")
            }
        }

        "notBrowser on a name" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    "x".notBrowser in Sync.defer(runs.incrementAndGet()).andThen(succeed)
            }.map { case (path, result) =>
                assert(path == Chunk("x"))
                expectHost(inBrowser = false, result, runs, "does not run in a browser")
            }
        }

        "a host filter after other decorators keeps them" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    "x".tagged("t").times(1).onlyBrowser in Sync.defer(runs.incrementAndGet()).andThen(succeed)
            }.map { case (path, result) =>
                assert(path == Chunk("x"))
                expectHost(inBrowser = true, result, runs, "runs only in a browser")
            }
        }

        "a host filter after a platform filter applies where the platform filter admits the test" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    // A platform filter that admits the test on the platform running this suite.
                    if Platform.isNative then "x".notJvm.notBrowser in Sync.defer(runs.incrementAndGet()).andThen(succeed)
                    else "x".notNative.notBrowser in Sync.defer(runs.incrementAndGet()).andThen(succeed)
            }.map { case (path, result) =>
                assert(path == Chunk("x"))
                expectHost(inBrowser = false, result, runs, "does not run in a browser")
            }
        }

        "a host filter after a platform filter leaves the test out where the platform filter excludes it" in {
            val ctx = new kyo.test.internal.TestContext(Chunk(0), discovery = true)
            kyo.test.internal.TestContext.setForInstantiation(ctx)
            val _ = new kyo.test.Test[Any]:
                "x".notJvm.notJs.notNative.onlyBrowser in succeed
            ctx.signalPastEnd()
            assert(ctx.peekRegisteredLeaf == Maybe.empty)
            Future.successful(succeed)
        }

        "a host filter before .handle carries into the enriched builder" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    "x".onlyBrowser.handle[Env[Db]]([A] => (b: A < (Env[Db] & Async & Abort[Any] & Scope)) => Env.run(Db("test"))(b)) in
                        Env.get[Db].map(_ => Sync.defer(runs.incrementAndGet())).andThen(succeed)
            }.map { case (path, result) =>
                assert(path == Chunk("x"))
                expectHost(inBrowser = true, result, runs, "runs only in a browser")
            }
        }

        "a filtered group whose filter does not hold is one cancelled entry" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    (if Platform.isBrowser then "g".notBrowser else "g".onlyBrowser) - {
                        "a" in Sync.defer(runs.incrementAndGet()).andThen(succeed)
                    }
            }.map { case (path, result) =>
                assert(path == Chunk("g"))
                result match
                    case TestResult.Cancelled(_, _) => assert(runs.get() == 0)
                    case other                      => fail(s"expected Cancelled, got $other")
            }
        }

        "a filtered group whose filter holds registers its leaves" in {
            val runs = new AtomicInteger(0)
            LeafHarness.runLeafWithPath(Chunk(0, 0)) {
                new kyo.test.Test[Any]:
                    (if Platform.isBrowser then "g".onlyBrowser else "g".notBrowser) - {
                        "a" in Sync.defer(runs.incrementAndGet()).andThen(succeed)
                    }
            }.map { case (path, result) =>
                assert(path == Chunk("g", "a"))
                assert(result.isInstanceOf[TestResult.Passed], s"expected Passed, got $result")
                assert(runs.get() == 1)
            }
        }

        "contradictory filters cancel on every host" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    "x".onlyBrowser.notBrowser in Sync.defer(runs.incrementAndGet()).andThen(succeed)
            }.map { case (_, result) =>
                assert(result.isInstanceOf[TestResult.Cancelled], s"expected Cancelled, got $result")
                assert(runs.get() == 0)
            }
        }

        "ignore takes precedence over a host filter" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "x".onlyBrowser.notBrowser.ignore("parked") in succeed
            }.map { case (_, result) =>
                assert(result == TestResult.Ignored("parked"))
            }
        }

        "a suite-level filter applies to a test that carries none of its own" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    override protected def hostFilters = Chunk(HostFilter.NotBrowser)
                    "x" in Sync.defer(runs.incrementAndGet()).andThen(succeed)
            }.map { case (path, result) =>
                assert(path == Chunk("x"))
                expectHost(inBrowser = false, result, runs, "does not run in a browser")
            }
        }

        "a suite-level filter that does not hold cancels a group as one entry" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    override protected def hostFilters =
                        Chunk(if Platform.isBrowser then HostFilter.NotBrowser else HostFilter.OnlyBrowser)
                    "g" - {
                        "a" in Sync.defer(runs.incrementAndGet()).andThen(succeed)
                    }
            }.map { case (path, result) =>
                assert(path == Chunk("g"))
                result match
                    case TestResult.Cancelled(_, _) => assert(runs.get() == 0)
                    case other                      => fail(s"expected Cancelled, got $other")
            }
        }

        "a suite-level filter applies to a platform-filtered test" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    override protected def hostFilters = Chunk(HostFilter.NotBrowser)
                    // A platform filter that admits the test on the platform running this suite.
                    if Platform.isNative then "x".notJvm in Sync.defer(runs.incrementAndGet()).andThen(succeed)
                    else "x".notNative in Sync.defer(runs.incrementAndGet()).andThen(succeed)
            }.map { case (path, result) =>
                assert(path == Chunk("x"))
                expectHost(inBrowser = false, result, runs, "does not run in a browser")
            }
        }

        "a suite-level filter applies to a handled test" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    override protected def hostFilters = Chunk(HostFilter.NotBrowser)
                    "x".handle[Env[Db]]([A] => (b: A < (Env[Db] & Async & Abort[Any] & Scope)) => Env.run(Db("test"))(b)) in
                        Env.get[Db].map(_ => Sync.defer(runs.incrementAndGet())).andThen(succeed)
            }.map { case (path, result) =>
                assert(path == Chunk("x"))
                expectHost(inBrowser = false, result, runs, "does not run in a browser")
            }
        }

        "a suite-level filter contradicting a test's own filter cancels on every host" in {
            val runs = new AtomicInteger(0)
            runLeaf {
                new kyo.test.Test[Any]:
                    override protected def hostFilters = Chunk(HostFilter.NotBrowser)
                    "x".onlyBrowser in Sync.defer(runs.incrementAndGet()).andThen(succeed)
            }.map { case (_, result) =>
                assert(result.isInstanceOf[TestResult.Cancelled], s"expected Cancelled, got $result")
                assert(runs.get() == 0)
            }
        }

        "a host filter that does not hold takes precedence over only(false)" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "x".onlyBrowser.notBrowser.only(false) in succeed
            }.map { case (_, result) =>
                assert(result.isInstanceOf[TestResult.Cancelled], s"expected Cancelled, got $result")
            }
        }
    }

end HostFilterTest
