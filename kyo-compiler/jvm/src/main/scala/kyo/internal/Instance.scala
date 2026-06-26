package kyo.internal

import kyo.*

/** A live presentation-compiler instance bound to one Config, plus its per-config serialization
  * meter. The `Cache[Config, Instance]` value type, closed on every eviction path by the pool's
  * cache finalizer.
  *
  * Reliability: close releases the mutex `Meter` even if the backend close fails or the fiber is
  * interrupted (the meter close runs in a `Sync.ensure` finalizer), so neither the worker process
  * nor the meter leaks.
  */
final private[kyo] case class Instance(backend: Backend, mutex: Meter):
    def close(using Frame): Unit < (Async & Abort[Throwable]) =
        Sync.ensure(mutex.close.map(_ => ()))(backend.close)
end Instance
