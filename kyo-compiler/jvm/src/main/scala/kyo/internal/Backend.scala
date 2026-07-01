package kyo.internal

import kyo.*

/** The internal backend interface for one config's compiler.
  *
  * The compiler runs behind this abstract class either in-process (LocalBackend) or in a forked worker
  * (SpawnBackend). A `run` drives one pc op and returns a neutral Response; a fiber interrupt during
  * `run` cancels the underlying op end to end. `close` shuts the backend down (in-process: pc
  * shutdown; forked: the worker process is force-killed).
  */
abstract private[kyo] class Backend:
    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerException])
    def close(using Frame): Unit < (Async & Abort[Throwable])
