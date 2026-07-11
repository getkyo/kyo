package kyo.internal

import kyo.*

/** The neutral, serializable op reply, the aeron wire reply (the upickle [[Compiler.AsMessage]]
  * codec, carried inside the [[Envelope]] wrapper).
  *
  * Like [[Request]] it carries no id field (the id correlation is the transport's); a reply case is
  * just the op result. A worker-side typed failure is carried in-band as `Failed`, distinct from a
  * transport break.
  */
private[kyo] enum Response derives CanEqual, Compiler.AsMessage:
    case Diagnostics(value: Chunk[Compiler.Diagnostic])
    case Completions(value: Chunk[Compiler.Completion])
    case Hover(value: Maybe[Compiler.Hover])
    case Signature(value: Maybe[Compiler.Signature])
    case Symbol(value: Maybe[Compiler.SymbolInfo])
    case Closed
    case Failed(error: CompilerException)
end Response
