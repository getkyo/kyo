package kyo.internal

import kyo.*

/** The neutral, serializable op request, one case per Compiler op.
  *
  * Derives [[Compiler.AsMessage]] (the kyo-schema wire codec) so it is the aeron wire request for the
  * worker, carried by `Topic.publish`/`Topic.stream` inside the [[Envelope]] wrapper. It carries no
  * correlation id (the request/reply id correlation is the transport's, owned by `kyo.Exchange`),
  * so a request case is just the op payload. No lsp4j or scala.meta.pc type appears in any case
  * field; the wire is strictly the module's own neutral types.
  */
private[kyo] enum Request derives CanEqual, Compiler.AsMessage:
    case Compile(uri: Compiler.Uri, text: String)
    case Completions(uri: Compiler.Uri, text: String, offset: Int)
    case Hover(uri: Compiler.Uri, text: String, offset: Int)
    case SignatureHelp(uri: Compiler.Uri, text: String, offset: Int)
    case Symbol(uri: Compiler.Uri, text: String, offset: Int)
    case DidClose(uri: Compiler.Uri)
end Request
