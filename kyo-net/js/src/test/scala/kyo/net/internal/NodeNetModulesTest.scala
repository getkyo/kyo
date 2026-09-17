package kyo.net.internal

import kyo.*
import kyo.net.NetException
import kyo.net.Test
import kyo.scheduler.IOPromise
import scala.scalajs.js as sjs

/** The dynamic import behind [[JsTransport]]: an operation started before Node's modules are loaded waits for them, one interrupted while it
  * waits never runs, and one that throws while starting settles its fiber.
  *
  * Each leaf forgets the loaded modules first, so it exercises the first load. Operations other suites already started keep the modules they
  * were given; a later operation loads them again from Node's module cache.
  */
class NodeNetModulesTest extends Test:

    import AllowUnsafe.embrace.danger

    override def config = super.config.sequential

    /** Completes once the in-flight import settles. */
    private def loadSettled()(using Frame): Unit < Async =
        val settled = new IOPromise[Nothing, Unit]
        discard(NodeNetModules.load().`then`[Unit](
            { (_: NodeNetModules.Modules) => settled.completeDiscard(Result.unit) }: sjs.Function1[NodeNetModules.Modules, Unit],
            sjs.defined({ (_: scala.Any) => settled.completeDiscard(Result.unit) }: sjs.Function1[scala.Any, Unit])
        ))
        settled.asInstanceOf[Fiber.Unsafe[Unit, Any]].safe.get
    end loadSettled

    "an operation started before the first load runs with the loaded modules".notBrowser in {
        NodeNetModules.forgetForTesting()
        val fiber = NodeNetModules.afterLoad { modules =>
            Fiber.Unsafe.fromResult(Result.succeed(sjs.typeOf(modules.net.createServer) + " " + sjs.typeOf(modules.tls.connect)))
        }
        assert(!fiber.done())
        Abort.run[NetException](fiber.safe.get).map { result =>
            assert(result == Result.succeed("function function"))
        }
    }

    "an operation interrupted before the load completes never runs" in {
        NodeNetModules.forgetForTesting()
        var ran = false
        val fiber = NodeNetModules.afterLoad { _ =>
            ran = true
            Fiber.Unsafe.fromResult(Result.succeed(()))
        }
        assert(fiber.interrupt())
        loadSettled().andThen {
            assert(!ran)
        }
    }

    "an operation that throws while starting fails its fiber with the throw".notBrowser in {
        NodeNetModules.forgetForTesting()
        val boom  = new IllegalStateException("thrown while starting")
        val fiber = NodeNetModules.afterLoad[Unit](_ => throw boom)
        Abort.run[Throwable](fiber.safe.get).map { result =>
            assert(result == Result.panic(boom))
        }
    }

    "once loaded, an operation runs immediately".notBrowser in {
        loadSettled().andThen {
            var ran = false
            discard(NodeNetModules.afterLoad { _ =>
                ran = true
                Fiber.Unsafe.fromResult(Result.succeed(()))
            })
            assert(ran)
        }
    }

end NodeNetModulesTest
