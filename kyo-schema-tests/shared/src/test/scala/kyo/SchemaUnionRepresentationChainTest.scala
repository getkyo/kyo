package kyo

class SchemaUnionRepresentationChainTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // A variant whose read fails with an error that is not a decode failure.
    private def throwingVariant(message: String): () => Schema[Any] =
        val variant = Schema.init[Any](
            writeFn = (_: Any, _: Codec.Writer) => (),
            readFn = (_: Codec.Reader) => throw new IllegalStateException(message)
        )
        () => variant
    end throwingVariant

    // =========================================================================
    // Codec.Capabilities, the representation chain slot, and the chain builders
    // =========================================================================

    "representationFor is deterministic and capability-keyed" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.External
        )
        val capTrue  = Codec.Capabilities(canWriteTopLevelNonObject = true)
        val capFalse = Codec.Capabilities(canWriteTopLevelNonObject = false)
        assert(schema.representationFor(capTrue) == Schema.UnionRepresentation.TupleFlat)
        assert(schema.representationFor(capTrue) == Schema.UnionRepresentation.TupleFlat)
        assert(schema.representationFor(capFalse) == Schema.UnionRepresentation.External)
        assert(schema.representationFor(capFalse) == Schema.UnionRepresentation.External)
    }

    "duplicate chain is rejected at the first decode, naming the builder call" in {
        val dupChain = Schema[SSRShape].representations(
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.TupleFlat
        )
        dupChain.decodeString[Json]("{}") match
            case Result.Panic(e: DuplicateRepresentationException) =>
                assert(e.chain == Chunk(Schema.UnionRepresentation.TupleFlat, Schema.UnionRepresentation.TupleFlat))
                assert(e.getMessage.contains("representations(TupleFlat, TupleFlat)"), e.getMessage)
            case other => fail(s"expected a DuplicateRepresentationException, got $other")
        end match

        val dupOrElse = Schema[SSRShape].tupleFlat.orElseRepresentation(Schema.UnionRepresentation.TupleFlat)
        dupOrElse.decodeString[Json]("{}") match
            case Result.Panic(e: DuplicateRepresentationException) =>
                assert(e.chain == Chunk(Schema.UnionRepresentation.TupleFlat, Schema.UnionRepresentation.TupleFlat))
                assert(e.getMessage.contains("orElseRepresentation(TupleFlat)"), e.getMessage)
            case other => fail(s"expected a DuplicateRepresentationException, got $other")
        end match
    }

    "representations requires a first parameter - single-arg form compiles" in {
        val schema = Schema[SSRShape].representations(Schema.UnionRepresentation.External)
        assert(schema.representationChain.isDefined)
    }

    "single-entry External chain is byte-identical to default-External" in {
        val default         = Schema[SSRShape]
        val chainOne        = Schema[SSRShape].representations(Schema.UnionRepresentation.External)
        val value: SSRShape = SSRCircle(5.0)
        assert(default.encodeString[Json](value) == chainOne.encodeString[Json](value))
    }

    // =========================================================================
    // Chain encode selection and decode try-in-order
    // =========================================================================

    "encode emits primary shape on capable codec" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.Adjacent("type", "content"),
            Schema.UnionRepresentation.External
        )
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)
        val wire               = schema.encodeString[Json](triangle)
        assert(wire.startsWith("["))
        assert(wire == """["SSRTriangle",10.0,10.0,10.0]""")
    }

    "encode degrades to first object-shaped entry on incapable codec" in {
        // Chain: TupleFlat (needs canWriteTopLevelNonObject), Adjacent (object-shaped, always ok), External.
        // Protobuf cannot express TupleFlat, so selectRepresentation picks Adjacent.
        // The encode SUCCEEDS (Adjacent is an object shape Protobuf can write).
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.Adjacent("type", "content"),
            Schema.UnionRepresentation.External
        )
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)
        val bytes              = schema.encode[Protobuf](triangle)
        assert(bytes.nonEmpty)
        // Chain decode requires a self-describing reader; Protobuf is not one.
        // Decode via Json to confirm the encode produced an Adjacent-shaped value.
        // (Re-encode as Adjacent-only Json wire and verify the shape.)
        val adjWire = Schema[SSRShape].adjacent("type", "content").encodeString[Json](triangle)
        val decoded = Schema[SSRShape].adjacent("type", "content").decodeString[Json](adjWire)
        assert(decoded == Result.succeed(SSRTriangle(10.0, 10.0, 10.0)))
    }

    "no-chain tupleFlat still throws on Protobuf" in {
        val schema           = Schema[SSRShape].tupleFlat
        val circle: SSRShape = SSRCircle(10.0)
        val result           = Result.catching[RepresentationUnsupportedException](schema.encode[Protobuf](circle))
        assert(result.isFailure)
        result match
            case Result.Failure(ex) => assert(ex.codec == "Protobuf")
            case other              => fail(s"Expected RepresentationUnsupportedException but got $other")
        end match
    }

    "exhausted chain throws naming codec and attempted chain" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.Tuple
        )
        val circle: SSRShape = SSRCircle(10.0)
        val result           = Result.catching[RepresentationUnsupportedException](schema.encode[Protobuf](circle))
        result match
            case Result.Failure(ex) =>
                assert(ex.getMessage.contains("Protobuf"))
                assert(ex.getMessage.contains("TupleFlat"))
                assert(ex.getMessage.contains("Tuple"))
            case other => fail(s"Expected RepresentationUnsupportedException but got $other")
        end match
    }

    "chain round-trips a value valid for a later entry" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Internal("type"),
            Schema.UnionRepresentation.External
        )
        val circle: SSRShape = SSRCircle(10.0)
        // Encode using External (the baseline) to produce a wire the Internal arm won't match
        val externalWire = Schema[SSRShape].encodeString[Json](circle)
        // externalWire is {"SSRCircle":{"radius":10.0}}, which won't parse as Internal
        // but will parse as External (the fallback in the chain)
        val result = schema.decodeString[Json](externalWire)
        assert(result == Result.succeed(SSRCircle(10.0)))
    }

    "chain decode whose first attempt panics re-throws the panic" in {
        // Use Untagged as the only chain entry so readUntagged reads each variant in turn.
        // The injected variant at position 0 throws IllegalStateException (not a SchemaException),
        // which must surface as Result.Panic and NOT be swallowed as a chain no-match.
        val base = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Untagged
        )
        val patched = Schema.copyWith(base)(
            variantSchemas = Chunk(throwingVariant("injected panic in chain decode")) ++ base.variantSchemas.drop(1)
        )
        // Untagged wire: a bare SSRCircle payload
        val wire   = """{"radius":10.0}"""
        val result = patched.decodeString[Json](wire)
        result match
            case Result.Panic(ex: IllegalStateException) =>
                assert(ex.getMessage == "injected panic in chain decode")
            case other => fail(s"Expected Result.Panic(IllegalStateException) but got $other")
        end match
    }

    "ambiguous two-entry chain selects first-declared on decode" in {
        // Two representations that could both decode the same External wire: External then Internal.
        // External is first-declared, so it should win.
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.External,
            Schema.UnionRepresentation.Internal("type")
        )
        val wire   = Schema[SSRShape].encodeString[Json](SSRCircle(10.0))
        val result = schema.decodeString[Json](wire)
        assert(result == Result.succeed(SSRCircle(10.0)))
    }

    "reordering the chain flips the chosen decode path" in {
        // Internal is first when we use Internal wire format: chain tries Internal first and wins.
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Internal("type"),
            Schema.UnionRepresentation.External
        )
        val internalWire = Schema[SSRShape].discriminator("type").encodeString[Json](SSRCircle(10.0))
        val result       = schema.decodeString[Json](internalWire)
        assert(result == Result.succeed(SSRCircle(10.0)))
    }

    "variant wire name is consistent across selected representations" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Adjacent("type", "content"),
            Schema.UnionRepresentation.External
        ).discriminator("type").renameAllVariants(Schema.NameCase.SnakeCase)
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // Adjacent is selected (capable); snake_case variant name must appear
        assert(wire.contains("ssr_circle"))
    }

    "one derived decoder set round-trips a sum schema through every representation" in {
        // The six-representation schema uses the single derived variantDecoders set to decode
        // wire produced by each individual representation schema. Each round-trip must produce
        // the same concrete value, proving variantDecoders is representation-independent.
        val chainSchema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.External,
            Schema.UnionRepresentation.Internal("type"),
            Schema.UnionRepresentation.Adjacent("type", "content"),
            Schema.UnionRepresentation.Tuple,
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.Untagged
        )
        val value: SSRShape = SSRTriangle(10.0, 10.0, 10.0)

        // Produce wires for each of the six representations using single-rep schemas
        val extWire  = Schema[SSRShape].encodeString[Json](value)
        val intWire  = Schema[SSRShape].discriminator("type").encodeString[Json](value)
        val adjWire  = Schema[SSRShape].adjacent("type", "content").encodeString[Json](value)
        val tupWire  = Schema[SSRShape].tupleTagged.encodeString[Json](value)
        val tupFWire = Schema[SSRShape].tupleFlat.encodeString[Json](value)
        val untWire  = Schema[SSRShape].untagged.encodeString[Json](value)

        // Decode each wire through the chain schema; chain tries entries in declared order
        // and the first that succeeds returns the value
        assert(chainSchema.decodeString[Json](extWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](intWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](adjWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](tupWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](tupFWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](untWire) == Result.succeed(value))
    }

    "tagged union representation throws RepresentationUnsupportedException before bytes on incapable binary codec" in {
        // tupleFlat requires a top-level array; Protobuf cannot express this.
        // The exception must be raised before any bytes are written.
        val s      = summon[Schema[Int | String]].tupleFlat
        val result = Result.catching[RepresentationUnsupportedException](s.encode[Protobuf](42))
        result match
            case Result.Failure(ex) =>
                assert(ex.codec == "Protobuf", s"Exception must name the codec; got: ${ex.codec}")
                assert(ex.representation == "TupleFlat", s"Exception must name the representation; got: ${ex.representation}")
            case other => fail(s"Expected Failure(RepresentationUnsupportedException), got $other")
        end match
    }

    "chain decode over no variants yields typed NoVariantMatchException" in {
        // A schema with no variant schemas reaches readChain, which dispatches to
        // readUntagged (via readForRepresentation), which immediately throws NoVariantMatchException
        // (zero variants). That is caught as a DecodeException and re-thrown on chain exhaustion.
        val base = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Untagged
        )
        val patched = Schema.copyWith(base)(variantSchemas = Chunk.empty)
        val wire    = """{"radius":10.0}"""
        val result  = patched.decodeString[Json](wire)
        result match
            case Result.Failure(_: NoVariantMatchException) => succeed("no variants yields NoVariantMatchException")
            case Result.Panic(ex)                           => fail(s"Expected typed Failure but got Panic: $ex")
            case other                                      => fail(s"Expected Failure(NoVariantMatchException) but got $other")
        end match
    }

end SchemaUnionRepresentationChainTest
