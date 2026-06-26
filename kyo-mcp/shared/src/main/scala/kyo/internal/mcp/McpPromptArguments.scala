package kyo.internal.mcp

import kyo.*

/** Helpers for the typed `prompt[In]` lift: derive the argument advertisement from `In`'s
  * schema, and re-encode the inbound `Map[String, String]` into a `Structure.Value` the
  * `Structure.decode[In]` step consumes.
  *
  * Each top-level field of `In` becomes a `PromptArgument`; a `Maybe`-typed field is
  * `required = false`. MCP prompt arguments are string-valued on the wire, so only field
  * names and required-ness are advertised.
  */
private[kyo] object McpPromptArguments:

    def fromSchema[In](using schema: Schema[In]): Chunk[McpHandler.PromptArgument] =
        schema.structure match
            case p: Structure.Type.Product =>
                p.fields.map { field =>
                    McpHandler.PromptArgument(
                        name = field.name,
                        description = Absent,
                        required = !field.optional
                    )
                }
            case _ =>
                Chunk.empty

    /** Builds the `Structure.Value` record from the inbound string-valued argument map so the
      * typed `prompt[In]` can `Structure.decode[In]` it. Each value is a `Structure.Value.Str`.
      */
    def encodeArgs(args: Map[String, String]): Structure.Value =
        Structure.Value.Record(Chunk.from(args.iterator.map((k, v) => (k, Structure.Value.Str(v)))))
end McpPromptArguments
