package kyo.internal

import kyo.*

/** The internal backend interface for one config's compiler.
  *
  * The compiler runs behind this trait either in-process (LocalBackend) or in a forked worker
  * (SpawnBackend). A `run` drives one pc op and returns a neutral Response; a fiber interrupt during
  * `run` cancels the underlying op end to end. `close` shuts the backend down (in-process: pc
  * shutdown; forked: the worker process is force-killed).
  */
private[kyo] trait Backend:
    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError])
    def close(using Frame): Unit < (Async & Abort[Throwable])
