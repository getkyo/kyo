package kyo.scheduler

import kyo.Present
import scala.scalanative.libc.stdatomic.AtomicRef
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.runtime.fromRawPtr

object IOPromisePlatformSpecific:

    val stateHandle =
        Present(
            new IOPromise.StateHandle:
                def compareAndSet[E, A](promise: IOPromise[E, A], curr: IOPromise.State[E, A], next: IOPromise.State[E, A]): Boolean =
                    val ref = AtomicRef(fromRawPtr(Intrinsics.classFieldRawPtr(promise, "state")))
                    ref.compareExchangeStrong(curr.asInstanceOf[AnyRef], next.asInstanceOf[AnyRef])
        )

end IOPromisePlatformSpecific
