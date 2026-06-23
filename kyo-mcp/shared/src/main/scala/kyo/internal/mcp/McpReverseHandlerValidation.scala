package kyo.internal.mcp

import kyo.*

/** Validate-at-init coupling: a reverse handler whose required client capability was not
  * advertised is rejected at construction, never left as a silently-latent dead handler.
  *
  * A reverse default route gates on the advertised client capability, so a handler whose
  * capability is absent never fires. Surfacing the mismatch where the user can fix it (at
  * `McpClient.init`) is strictly better than a documented coupling the user can still violate.
  */
private[kyo] object McpReverseHandlerValidation:

    def require(handlers: Seq[McpClientHandler[?, ?, ?]], capabilities: McpCapabilities.Client)(using
        Frame
    ): Unit < Abort[McpInitFailure] =
        Kyo.foreachDiscard(Chunk.from(handlers)) { h =>
            h.requiredCapability match
                case Present(cap) if !advertised(cap, capabilities) =>
                    Abort.fail(McpCapabilityNotAdvertisedException(
                        h.method,
                        cap,
                        McpCapabilityNotAdvertisedException.Peer.Client
                    ))
                case _ => ()
        }

    private def advertised(cap: McpCapabilities.Name, c: McpCapabilities.Client): Boolean =
        cap match
            case McpCapabilities.Name.Sampling    => c.sampling.isDefined
            case McpCapabilities.Name.Roots       => c.roots.isDefined
            case McpCapabilities.Name.Elicitation => c.elicitation.isDefined
            case _                                => true

end McpReverseHandlerValidation
