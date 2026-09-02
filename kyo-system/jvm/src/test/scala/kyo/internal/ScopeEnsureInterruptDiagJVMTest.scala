package kyo.internal

import kyo.*

/** TEMPORARY diagnostic (not for merge). Lock-free reproduction of the lost-finalizer strand: a
  * fiber registers a Scope.ensure, does a little Sync work, and is interrupted from outside. Every
  * registered finalizer must eventually run. No filesystem, no locks: if this strands, the loss is
  * in core finalizer delivery, independent of kyo-system.
  */
class ScopeEnsureInterruptDiagJVMTest extends kyo.test.Test[Any]:

    private val rounds = 40
    private val spawns = 200

    private def spawn(registered: AtomicInt, executed: AtomicInt, remaining: Int)(using Frame): Unit < (Async & Sync) =
        if remaining <= 0 then ()
        else
            Fiber.initUnscoped {
                Scope.run {
                    Scope.ensure(executed.incrementAndGet.unit).andThen {
                        registered.incrementAndGet.unit
                    }
                }
            }
                .map(_.interrupt)
                .andThen(spawn(registered, executed, remaining - 1))

    private def settle(registered: AtomicInt, executed: AtomicInt, remaining: Int)(using Frame): Boolean < (Async & Sync) =
        registered.get.map { r =>
            executed.get.map { e =>
                if e >= r then true
                else if remaining <= 0 then false
                else Async.sleep(50.millis).andThen(settle(registered, executed, remaining - 1))
            }
        }

    private def round(index: Int)(using Frame): Boolean < (Async & Sync) =
        AtomicInt.init(0).map { registered =>
            AtomicInt.init(0).map { executed =>
                spawn(registered, executed, spawns).andThen(settle(registered, executed, 100)).map { settled =>
                    if settled then true
                    else
                        registered.get.map { r =>
                            executed.get.map { e =>
                                Sync.defer {
                                    println(s"DIAG-CORE-STRAND round=$index registered=$r executed=$e lost=${r - e}")
                                    false
                                }
                            }
                        }
                }
            }
        }

    private def loop(index: Int)(using Frame): Int < (Async & Sync) =
        if index >= rounds then -1
        else
            round(index).map {
                case true  => loop(index + 1)
                case false => index
            }

    "diag: an interrupted fiber runs every registered Scope finalizer" in {
        loop(0).map { stranded =>
            assert(stranded == -1, s"lost finalizer first observed at round $stranded")
        }
    }
end ScopeEnsureInterruptDiagJVMTest
