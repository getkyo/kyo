package kyo

import upickle.default.*

/** The typed failure leaf on every [[Compiler]] op's `Abort` row.
  *
  * `InitializationFailed` means the backend, the presentation compiler, or a forked worker could
  * not start; `Fatal` means a running op or its transport failed. Top-level by design (the kyo
  * convention for failure types), serializable via [[Compiler.AsMessage]] so it rides the worker
  * IPC and LSP wires.
  */
enum CompilerError derives CanEqual, Compiler.AsMessage:
    case InitializationFailed(message: String)
    case Fatal(message: String)
