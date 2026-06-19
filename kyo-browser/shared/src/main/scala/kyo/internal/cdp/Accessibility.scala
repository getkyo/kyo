package kyo.internal.cdp

import kyo.*
import kyo.internal.CdpBackend
import kyo.internal.CdpNoParams
import kyo.internal.CdpReply

/** Typed wrapper around CDP `Accessibility.getFullAXTree`.
  *
  * Returns a flat [[Chunk]] of [[AxNode]] values, one per node in the accessibility tree. Each node surfaces its role, computed name,
  * ignored flag, and a `properties` map keyed by common AX state attributes (`disabled`, `checked`, `expanded`, `focused`, `hidden`, …).
  *
  * The CDP `AXValue` shape carries a polymorphic `value` field whose JSON type depends on `type` (boolean / string / number / object). The
  * polymorphism is handled by a custom [[Schema]] for [[AxValue]] (via [[Schema.init]]) that reads the `type` discriminator and dispatches
  * to the correct typed reader for the `value` field; the result is a `Maybe[String]` that stringifies every supported variant. The
  * surrounding wire types ([[AxPropertyWire]], [[AxNodeWire]], [[AxTreeResponse]]) are plain `derives Schema` case classes.
  */
private[kyo] object Accessibility:

    final case class AxNode(
        nodeId: String,
        role: String,
        name: String,
        ignored: Boolean,
        properties: Dict[String, String]
    ) derives Schema, CanEqual

    /** Returns the full accessibility tree for the current page.
      *
      * The caller must invoke this on a session-scoped [[CdpBackend]] (obtained via `backend.withSession(sid)`). The `Accessibility` domain
      * is auto-enabled by this call; no explicit `Accessibility.enable` is required.
      */
    def getFullAXTree(client: CdpBackend)(using Frame): Chunk[AxNode] < (Async & Abort[BrowserReadException]) =
        client.send[CdpNoParams, AxTreeResponse]("Accessibility.getFullAXTree", CdpNoParams()).map(extractAxNodes)

    /** Frame-scoped variant: returns the AX tree for the specified frame id rather than the top-level document. */
    def getFullAXTreeForFrame(client: CdpBackend, frameId: String)(using Frame): Chunk[AxNode] < (Async & Abort[BrowserReadException]) =
        client.send[GetFullAXTreeParams, AxTreeResponse](
            "Accessibility.getFullAXTree",
            GetFullAXTreeParams(frameId = Present(frameId))
        ).map(extractAxNodes)

    /** Wire shape for `Accessibility.getFullAXTree`. Only the `frameId` field is consumed; the zero-arg overload sends no params. */
    final private[kyo] case class GetFullAXTreeParams(frameId: Maybe[String] = Absent) derives Schema

    // --- Internal wire types ---

    /** A CDP `AXValue` modelled as a discriminated sum type on the `type` field.
      *
      * Wire shape: `{"type": "string", "value": "Submit"}`, `{"type": "number", "value": 3}`, `{"type": "boolean", "value": true}`,
      * `{"type": "idref", "value": [...]}` (object/array payloads, kept opaque to the AX projection).
      *
      * Variants follow CDP's `AXValueType` enum. Lowercase case-class names with backticks match the wire discriminator values verbatim.
      * Object / array variants (`idref` / `idrefList`) carry no typed fields; permissive decoding skips the wire `value` payload.
      *
      * Declared in scope of [[AxPropertyWire]] / [[AxNodeWire]] so kyo-schema's macro-time `Expr.summon[Schema[AxValue]]` resolves to
      * the discriminator-flat given.
      */
    sealed private[kyo] trait AxValue derives CanEqual

    private[kyo] object AxValue:

        final case class `string`(value: String)              extends AxValue derives Schema, CanEqual
        final case class `computedString`(value: String)      extends AxValue derives Schema, CanEqual
        final case class `token`(value: String)               extends AxValue derives Schema, CanEqual
        final case class `role`(value: String)                extends AxValue derives Schema, CanEqual
        final case class `internalRole`(value: String)        extends AxValue derives Schema, CanEqual
        final case class `tokenList`(value: String)           extends AxValue derives Schema, CanEqual
        final case class `boolean`(value: Boolean)            extends AxValue derives Schema, CanEqual
        final case class `booleanOrUndefined`(value: Boolean) extends AxValue derives Schema, CanEqual
        final case class `number`(value: Double)              extends AxValue derives Schema, CanEqual
        final case class `integer`(value: Long)               extends AxValue derives Schema, CanEqual
        final case class `idref`()                            extends AxValue derives Schema, CanEqual
        final case class `idrefList`()                        extends AxValue derives Schema, CanEqual
        // Chrome emits `{"type":"nodeList","relatedNodes":[...]}` for `aria-labelledby` and similar idref-list ARIA
        // attributes. The related-nodes payload is kept opaque (mirrors the `idref` / `idrefList` shape): the public
        // projection (`asString`) returns Absent, and `Accessibility.toAxNode` drops the property from the map.
        final case class `nodeList`() extends AxValue derives Schema, CanEqual
        // Additional opaque CDP AXValueType variants documented in the CDP spec. Treated as opaque (asString returns
        // Absent) so the discriminator-flat decoder never fails with UnknownVariantException on real Chrome wire data.
        final case class `node`()           extends AxValue derives Schema, CanEqual
        final case class `tristate`()       extends AxValue derives Schema, CanEqual
        final case class `domRelation`()    extends AxValue derives Schema, CanEqual
        final case class `valueUndefined`() extends AxValue derives Schema, CanEqual

        given Schema[AxValue] = Schema.derived[AxValue].discriminator("type")

        /** Stringified projection of the variant's value, used by the public [[AxNode]] projection.
          *
          *   - String-typed variants: the inner value verbatim.
          *   - Boolean-typed variants: `"true"` or `"false"`.
          *   - `number`: round-tripped through `Long` when integer-valued so callers see `"20"` rather than `"20.0"`.
          *   - `integer`: `Long.toString`.
          *   - `idref` / `idrefList`: `Absent` (opaque object / array payloads not surfaced).
          */
        extension (av: AxValue)
            def asString: Maybe[String] = av match
                case s: `string`             => Present(s.value)
                case s: `computedString`     => Present(s.value)
                case s: `token`              => Present(s.value)
                case s: `role`               => Present(s.value)
                case s: `internalRole`       => Present(s.value)
                case s: `tokenList`          => Present(s.value)
                case b: `boolean`            => Present(b.value.toString)
                case b: `booleanOrUndefined` => Present(b.value.toString)
                case n: `number` =>
                    val d      = n.value
                    val asLong = d.toLong
                    if !d.isInfinite && !d.isNaN && d == asLong.toDouble then Present(asLong.toString)
                    else Present(d.toString)
                case i: `integer`        => Present(i.value.toString)
                case _: `idref`          => Absent
                case _: `idrefList`      => Absent
                case _: `nodeList`       => Absent
                case _: `node`           => Absent
                case _: `tristate`       => Absent
                case _: `domRelation`    => Absent
                case _: `valueUndefined` => Absent
        end extension
    end AxValue

    final private[kyo] case class AxPropertyWire(name: String, value: Maybe[AxValue] = Absent) derives Schema

    final private[kyo] case class AxNodeWire(
        nodeId: String = "",
        role: Maybe[AxValue] = Absent,
        name: Maybe[AxValue] = Absent,
        ignored: Boolean = false,
        properties: Seq[AxPropertyWire] = Seq.empty,
        backendDOMNodeId: Maybe[Long] = Absent
    ) derives Schema

    final private[kyo] case class AxTreeResponse(nodes: Seq[AxNodeWire] = Seq.empty) derives Schema

    // --- Parsing ---

    /** Extracts [[AxNode]] values from a typed [[AxTreeResponse]]. The error and decode failure paths are handled
      * upstream by [[CdpBackend.send]], which recovers [[JsonRpcError]] to [[BrowserProtocolErrorException]].
      */
    private[kyo] def extractAxNodes(tree: AxTreeResponse): Chunk[AxNode] =
        Chunk.from(tree.nodes.map(toAxNode))

    /** Legacy wire-string parser kept for test compatibility. */
    private[kyo] def parseAxTree(wire: String)(using Frame): Chunk[AxNode] < (Sync & Abort[BrowserReadException]) =
        Json.decode[CdpReply[AxTreeResponse]](wire) match
            case Result.Success(reply) =>
                reply.error match
                    case Present(err) =>
                        Abort.fail(BrowserProtocolErrorException("Accessibility.getFullAXTree", err.message))
                    case Absent =>
                        reply.result match
                            case Present(tree) => Chunk.from(tree.nodes.map(toAxNode))
                            case Absent =>
                                Abort.fail(BrowserProtocolErrorException(
                                    "Accessibility.getFullAXTree",
                                    s"reply has neither result nor error: $wire"
                                ))
            case Result.Failure(err) =>
                Abort.fail(BrowserProtocolErrorException(
                    "Accessibility.getFullAXTree",
                    s"wire decode failed: ${err.getMessage}; raw=$wire"
                ))
            case Result.Panic(t) =>
                Abort.fail(BrowserProtocolErrorException(
                    "Accessibility.getFullAXTree",
                    s"wire decode panicked: ${t.getMessage}; raw=$wire"
                ))
    end parseAxTree

    /** Project a typed [[AxNodeWire]] into the public [[AxNode]] shape. The polymorphic `role` / `name` AXValues are reduced to plain
      * strings via [[AxValue.asString]] (default `""` when the field is absent or non-stringifiable); properties whose value is `Absent`
      * (idref / unknown variants) are dropped from the public map. Every wire entry yields exactly one [[AxNode]] (defaults rather than
      * drops, mirroring the pre-Schema parser's permissive behaviour so a single missing field doesn't poison the tree).
      */
    private def toAxNode(wire: AxNodeWire): AxNode =
        import AxValue.asString
        val role = wire.role.flatMap(_.asString).getOrElse("")
        val name = wire.name.flatMap(_.asString).getOrElse("")
        val baseProps = wire.properties.foldLeft(Dict.empty[String, String]) { (acc, prop) =>
            (prop.value.flatMap(_.asString): @unchecked) match
                case Present(v) => acc.update(prop.name, v)
                case Absent     => acc
        }
        val finalProps = wire.backendDOMNodeId match
            case Present(id) => baseProps.update("backendDOMNodeId", id.toString)
            case Absent      => baseProps
        AxNode(wire.nodeId, role, name, wire.ignored, finalProps)
    end toAxNode

end Accessibility
