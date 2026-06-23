package kyo

// --- SSR* fixtures (scoped to this file) ---
// --- SSRA* fixtures for alias-set and naming-composition tests ---
sealed trait SSRAShape derives CanEqual, Schema
case class SSRACircle(radius: Double) extends SSRAShape derives CanEqual
case class SSRASquare(side: Double)   extends SSRAShape derives CanEqual

// --- SSRU* fixtures for untagged round-trip and declaration-order tests ---
// SSRUCircle is declared BEFORE SSRUSquare so the attempt loop order matches source order.
sealed trait SSRUShape derives CanEqual, Schema
case class SSRUCircle(radius: Double)             extends SSRUShape derives CanEqual
case class SSRUSquare(side: Double)               extends SSRUShape derives CanEqual
case class SSRUBox(width: Double, height: Double) extends SSRUShape derives CanEqual

// Fixture for ambiguous-first-wins test: both variants share the same wire field.
sealed trait SSRUAmbig derives CanEqual, Schema
case class SSRUAmbigFirst(x: Double)  extends SSRUAmbig derives CanEqual
case class SSRUAmbigSecond(x: Double) extends SSRUAmbig derives CanEqual

// Reverse-order variant of the ambiguous fixture to confirm order matters.
sealed trait SSRUAmbigRev derives CanEqual, Schema
case class SSRUAmbigRevSecond(x: Double) extends SSRUAmbigRev derives CanEqual
case class SSRUAmbigRevFirst(x: Double)  extends SSRUAmbigRev derives CanEqual

// Fixture: a variant with a multi-word camelCase field, so field-name casing is observable in the wire.
// itemCount would become item_count under SnakeCase, making assertions discriminating.
sealed trait SSRUNamed derives CanEqual, Schema
case class SSRUItem(itemCount: Int) extends SSRUNamed derives CanEqual

// Fixtures for untagged round-trip with Short, Byte, and Int fields on Ion.
// Ion emits integer literals as BigNum in the Structure.Value tree; the reader must accept BigNum for these types.
sealed trait SSRUIntegral derives CanEqual, Schema
case class SSRUShortVal(s: Short) extends SSRUIntegral derives CanEqual
case class SSRUByteVal(b: Byte)   extends SSRUIntegral derives CanEqual
case class SSRUIntVal(n: Int)     extends SSRUIntegral derives CanEqual

sealed trait SSRShape derives CanEqual, Schema
case class SSRCircle(radius: Double)                    extends SSRShape derives CanEqual
case class SSRSquare(side: Double)                      extends SSRShape derives CanEqual
case class SSRTriangle(a: Double, b: Double, c: Double) extends SSRShape derives CanEqual
case object SSRUnit                                     extends SSRShape derives CanEqual
case class SSRPi(value: Double)                         extends SSRShape derives CanEqual
case class SSRLine(p1: SSRPoint, p2: SSRPoint)          extends SSRShape derives CanEqual
case class SSRPoint(x: Double, y: Double) derives CanEqual, Schema

class SchemaSumRepresentationTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // =========================================================================
    // Group: enum shape
    // =========================================================================

    "enum shape constructs and matches exhaustively" in {
        val ext  = Schema.SumRepresentation.External
        val int  = Schema.SumRepresentation.Internal("t")
        val adj  = Schema.SumRepresentation.Adjacent("t", "c")
        val tup  = Schema.SumRepresentation.Tuple
        val tupF = Schema.SumRepresentation.TupleFlat
        val unt  = Schema.SumRepresentation.Untagged

        // Total match over all six arms compiles with no missing-case warning.
        def describeAll(r: Schema.SumRepresentation): String = r match
            case Schema.SumRepresentation.External       => "external"
            case Schema.SumRepresentation.Internal(_)    => "internal"
            case Schema.SumRepresentation.Adjacent(_, _) => "adjacent"
            case Schema.SumRepresentation.Tuple          => "tuple"
            case Schema.SumRepresentation.TupleFlat      => "tupleFlat"
            case Schema.SumRepresentation.Untagged       => "untagged"

        assert(describeAll(ext) == "external")
        assert(describeAll(int) == "internal")
        assert(describeAll(adj) == "adjacent")
        assert(describeAll(tup) == "tuple")
        assert(describeAll(tupF) == "tupleFlat")
        assert(describeAll(unt) == "untagged")
        // Case-specific field access via pattern match
        val intTagKey = int match
            case Schema.SumRepresentation.Internal(k) => k
            case _                                    => ""
        assert(intTagKey == "t")
        val (adjTagKey, adjContentKey) = adj match
            case Schema.SumRepresentation.Adjacent(tk, ck) => (tk, ck)
            case _                                         => ("", "")
        assert(adjTagKey == "t")
        assert(adjContentKey == "c")
    }

    "enum CanEqual equality" in {
        val a = Schema.SumRepresentation.External
        val b = Schema.SumRepresentation.Adjacent("t", "c")
        val c = Schema.SumRepresentation.Adjacent("t", "d")
        assert(a == Schema.SumRepresentation.External)
        assert(b != c)
    }

    "nonDefault predicate" in {
        assert(Schema.SumRepresentation.External.nonDefault == false)
        assert(Schema.SumRepresentation.Internal("t").nonDefault == true)
        assert(Schema.SumRepresentation.Adjacent("t", "c").nonDefault == true)
        assert(Schema.SumRepresentation.Tuple.nonDefault == true)
        assert(Schema.SumRepresentation.TupleFlat.nonDefault == true)
        assert(Schema.SumRepresentation.Untagged.nonDefault == true)
    }

    // =========================================================================
    // Group: slot threading / inert default
    // =========================================================================

    "configured schema takes transform path; unconfigured is inert" in {
        val base      = Schema[SSRShape]
        val adjSchema = base.adjacent("type", "content")

        // Unconfigured schema: External (default) - hasTransforms driven only by other flags
        assert(base.representation == Schema.SumRepresentation.External)
        assert(base.representation.nonDefault == false)

        // Configured schema: Adjacent - nonDefault is true, so hasTransforms and hasReadTransforms flip
        assert(adjSchema.representation == Schema.SumRepresentation.Adjacent("type", "content"))
        assert(adjSchema.hasTransforms == true)
        assert(adjSchema.hasReadTransforms == true)

        // The External path encodes the default single-field wrapper
        val circle: SSRShape = SSRCircle(10.0)
        val baseWire         = base.encodeString[Json](circle)
        assert(baseWire == """{"SSRCircle":{"radius":10.0}}""")
    }

    "representation survives copyWith / focus composition" in {
        val base = Schema[SSRShape].adjacent("type", "content")
        // .doc routes through copyWith, which must preserve representation
        val withDoc = Schema.copyWith(base)(doc = Maybe("test doc"))

        // Representation slot must survive the copyWith round-trip
        assert(withDoc.representation == Schema.SumRepresentation.Adjacent("type", "content"))
        assert(withDoc.hasTransforms == true)
    }

    // =========================================================================
    // Group: builder Focused-preserving
    // =========================================================================

    "adjacent builder is Focused-preserving and sets Adjacent" in {
        val base                                                  = Schema[SSRShape]
        val adj: Schema[SSRShape] { type Focused = base.Focused } = base.adjacent("type", "content")
        // Focused refinement preserved (compile-time check above) and slot set correctly
        assert(adj.representation == Schema.SumRepresentation.Adjacent("type", "content"))
        succeed("adjacent builder preserves the Focused refinement")
    }

    "tupleTagged / tupleFlat / untagged builders are Focused-preserving and set their case" in {
        val base                                                   = Schema[SSRShape]
        val tup: Schema[SSRShape] { type Focused = base.Focused }  = base.tupleTagged
        val tupF: Schema[SSRShape] { type Focused = base.Focused } = base.tupleFlat
        val unt: Schema[SSRShape] { type Focused = base.Focused }  = base.untagged

        assert(tup.representation == Schema.SumRepresentation.Tuple)
        assert(tupF.representation == Schema.SumRepresentation.TupleFlat)
        assert(unt.representation == Schema.SumRepresentation.Untagged)

        // All three set nonDefault, so both transform flags are true
        assert(tup.hasTransforms == true)
        assert(tupF.hasReadTransforms == true)
        assert(unt.hasTransforms == true)

        succeed("tupleTagged, tupleFlat, and untagged builders preserve the Focused refinement")
    }

    // =========================================================================
    // Group: discriminator byte-identity
    // =========================================================================

    "discriminator is byte-identical sugar over Internal" in {
        val schema           = Schema[SSRShape].discriminator("type")
        val circle: SSRShape = SSRCircle(10.0)

        // discriminator sets both discriminatorField AND representation = Internal(fieldName)
        assert(schema.representation == Schema.SumRepresentation.Internal("type"))

        val wire = schema.encodeString[Json](circle)
        // Internal flat discriminator: {"type":"SSRCircle","radius":10.0}
        assert(wire == """{"type":"SSRCircle","radius":10.0}""")

        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRCircle(10.0)))
    }

    "discriminator chaining is last-wins" in {
        val schema           = Schema[SSRShape].discriminator("a").discriminator("b")
        val circle: SSRShape = SSRCircle(10.0)

        // Last discriminator call wins for both fields
        assert(schema.representation == Schema.SumRepresentation.Internal("b"))

        val wire = schema.encodeString[Json](circle)
        assert(wire == """{"b":"SSRCircle","radius":10.0}""")
        assert(!wire.contains("\"a\""))
    }

    // =========================================================================
    // Group: adjacent round-trip
    // =========================================================================

    "adjacent encode emits the two-field object" in {
        val schema           = Schema[SSRShape].adjacent("type", "content")
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        assert(wire == """{"type":"SSRCircle","content":{"radius":10.0}}""")
    }

    "adjacent empty-payload encode emits empty content object" in {
        val schema         = Schema[SSRShape].adjacent("type", "content")
        val unit: SSRShape = SSRUnit
        val wire           = schema.encodeString[Json](unit)
        assert(wire == """{"type":"SSRUnit","content":{}}""")
    }

    // =========================================================================
    // Group: tuple round-trip
    // =========================================================================

    "tuple encode emits the two-element array" in {
        val schema           = Schema[SSRShape].tupleTagged
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        assert(wire == """["SSRCircle",{"radius":10.0}]""")
    }

    // =========================================================================
    // Group: untagged encode
    // =========================================================================

    "untagged encode emits the bare payload" in {
        val schema           = Schema[SSRShape].untagged
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        assert(wire == """{"radius":10.0}""")
    }

    // =========================================================================
    // Group: non-object payload
    // =========================================================================

    "adjacent carries a single-field payload without dropping it" in {
        val schema       = Schema[SSRShape].adjacent("type", "content")
        val pi: SSRShape = SSRPi(3.14159)
        val wire         = schema.encodeString[Json](pi)
        // SSRPi(value: Double) produces a single-field Record payload; adjacentEncode passes it
        // through unchanged as the content value (no silent drop, unlike flattenWithDiscriminator).
        assert(wire == """{"type":"SSRPi","content":{"value":3.14159}}""")
    }

    "tuple carries a single-field payload as the second element" in {
        val schema       = Schema[SSRShape].tupleTagged
        val pi: SSRShape = SSRPi(3.14159)
        val wire         = schema.encodeString[Json](pi)
        // SSRPi(value: Double) produces a single-field Record payload; tupleEncode passes it
        // through unchanged as element 1 of the array.
        assert(wire == """["SSRPi",{"value":3.14159}]""")
    }

    // =========================================================================
    // Group: tupleFlat encode
    // =========================================================================

    "tupleFlat encode emits the flattened array in declaration order" in {
        val schema             = Schema[SSRShape].tupleFlat
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)
        val wire               = schema.encodeString[Json](triangle)
        assert(wire == """["SSRTriangle",10.0,10.0,10.0]""")
    }

    "tupleFlat nests a record field as one element; nested Tuple unchanged" in {
        val schemaFlat         = Schema[SSRShape].tupleFlat
        val schemaTuple        = Schema[SSRShape].tupleTagged
        val line: SSRShape     = SSRLine(SSRPoint(1.0, 2.0), SSRPoint(3.0, 4.0))
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)

        val flatWire  = schemaFlat.encodeString[Json](line)
        val tupleWire = schemaTuple.encodeString[Json](triangle)

        assert(flatWire == """["SSRLine",{"x":1.0,"y":2.0},{"x":3.0,"y":4.0}]""")
        assert(tupleWire == """["SSRTriangle",{"a":10.0,"b":10.0,"c":10.0}]""")
    }

    // =========================================================================
    // Group: binary codec rejection
    // =========================================================================

    "incapable codec raises RepresentationUnsupportedException pre-write; capable does not" in {
        val circle: SSRShape = SSRCircle(10.0)

        val tupleSchema     = Schema[SSRShape].tupleTagged
        val untaggedSchema  = Schema[SSRShape].untagged
        val tupleFlatSchema = Schema[SSRShape].tupleFlat

        val exTuple = intercept[RepresentationUnsupportedException] {
            tupleSchema.encode[Protobuf](circle)
        }
        assert(exTuple.codec == "Protobuf")
        assert(exTuple.representation == "Tuple")
        assert(exTuple.getMessage.contains("Protobuf"))

        val exUntagged = intercept[RepresentationUnsupportedException] {
            untaggedSchema.encode[Protobuf](circle)
        }
        assert(exUntagged.codec == "Protobuf")
        assert(exUntagged.representation == "Untagged")

        val exTupleFlat = intercept[RepresentationUnsupportedException] {
            tupleFlatSchema.encode[Protobuf](circle)
        }
        assert(exTupleFlat.codec == "Protobuf")
        assert(exTupleFlat.representation == "TupleFlat")

        // Json can express the tuple shape
        val jsonWire = tupleSchema.encodeString[Json](circle)
        assert(jsonWire == """["SSRCircle",{"radius":10.0}]""")
    }

    // =========================================================================
    // Group: adjacent round-trip decode
    // =========================================================================

    "adjacent round-trips and decodes the worked example" in {
        val schema           = Schema[SSRShape].adjacent("type", "content")
        val square: SSRShape = SSRSquare(10.0)
        val circle: SSRShape = SSRCircle(10.0)

        val decoded = schema.decodeString[Json]("""{"type":"SSRSquare","content":{"side":10.0}}""")
        assert(decoded == Result.succeed(SSRSquare(10.0)))

        val roundTripped = schema.decodeString[Json](schema.encodeString[Json](circle))
        assert(roundTripped == Result.succeed(SSRCircle(10.0)))
    }

    "adjacent empty-payload round-trips" in {
        val schema         = Schema[SSRShape].adjacent("type", "content")
        val unit: SSRShape = SSRUnit

        val wire    = schema.encodeString[Json](unit)
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRUnit))
    }

    "adjacent non-object scalar payload round-trips; discriminator keeps record fields" in {
        val adjSchema    = Schema[SSRShape].adjacent("type", "content")
        val discSchema   = Schema[SSRShape].discriminator("type")
        val pi: SSRShape = SSRPi(3.14159)

        val adjWire   = adjSchema.encodeString[Json](pi)
        val adjResult = adjSchema.decodeString[Json](adjWire)
        assert(adjResult == Result.succeed(SSRPi(3.14159)))

        // SSRPi(value: Double) is a record variant: discriminator flattens the field alongside the tag.
        val discWire = discSchema.encodeString[Json](pi)
        assert(discWire == """{"type":"SSRPi","value":3.14159}""")
    }

    // =========================================================================
    // Group: tuple round-trip decode
    // =========================================================================

    "tuple round-trips the worked example; non-object element round-trips" in {
        val schema           = Schema[SSRShape].tupleTagged
        val circle: SSRShape = SSRCircle(10.0)
        val pi: SSRShape     = SSRPi(3.14159)

        val circleWire   = schema.encodeString[Json](circle)
        val circleResult = schema.decodeString[Json](circleWire)
        assert(circleWire == """["SSRCircle",{"radius":10.0}]""")
        assert(circleResult == Result.succeed(SSRCircle(10.0)))

        val piWire   = schema.encodeString[Json](pi)
        val piResult = schema.decodeString[Json](piWire)
        assert(piWire == """["SSRPi",{"value":3.14159}]""")
        assert(piResult == Result.succeed(SSRPi(3.14159)))
    }

    // =========================================================================
    // Group: adjacent missing tag
    // =========================================================================

    "adjacent missing tag key -> MissingTagKeyException in Result" in {
        val schema  = Schema[SSRShape].adjacent("type", "content")
        val decoded = schema.decodeString[Json]("""{"content":{"radius":10.0}}""")
        decoded match
            case Result.Failure(ex: MissingTagKeyException) =>
                assert(ex.tagKey == "type")
            case other =>
                fail(s"Expected Result.Failure(MissingTagKeyException) but got $other")
        end match
    }

    // =========================================================================
    // Group: tupleFlat decode
    // =========================================================================

    "tupleFlat positional decode round-trips" in {
        val schema             = Schema[SSRShape].tupleFlat
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)

        val decoded = schema.decodeString[Json]("""["SSRTriangle",10.0,10.0,10.0]""")
        assert(decoded == Result.succeed(SSRTriangle(10.0, 10.0, 10.0)))

        val roundTripped = schema.decodeString[Json](schema.encodeString[Json](triangle))
        assert(roundTripped == Result.succeed(SSRTriangle(10.0, 10.0, 10.0)))
    }

    "tupleFlat count mismatch -> typed DecodeException in Result" in {
        val schema = Schema[SSRShape].tupleFlat

        val tooFew = schema.decodeString[Json]("""["SSRTriangle",10.0,10.0]""")
        tooFew match
            case Result.Failure(_: MissingFieldException) => succeed("too-few yields MissingFieldException as expected")
            case other                                    => fail(s"Expected MissingFieldException for too-few but got $other")

        val tooMany = schema.decodeString[Json]("""["SSRTriangle",10.0,10.0,10.0,10.0]""")
        tooMany match
            case Result.Failure(_: DecodeException) => succeed("too-many yields DecodeException as expected")
            case other                              => fail(s"Expected DecodeException for too-many but got $other")

        val correct = schema.decodeString[Json]("""["SSRTriangle",10.0,10.0,10.0]""")
        assert(correct == Result.succeed(SSRTriangle(10.0, 10.0, 10.0)))
    }

    "tupleFlat zero-field and single-field edges round-trip" in {
        val schema = Schema[SSRShape].tupleFlat

        val unitWire   = schema.encodeString[Json](SSRUnit)
        val unitResult = schema.decodeString[Json](unitWire)
        assert(unitWire == """["SSRUnit"]""")
        assert(unitResult == Result.succeed(SSRUnit))

        val piWire   = schema.encodeString[Json](SSRPi(10.0))
        val piResult = schema.decodeString[Json](piWire)
        assert(piWire == """["SSRPi",10.0]""")
        assert(piResult == Result.succeed(SSRPi(10.0)))
    }

    // =========================================================================
    // Group: untagged declaration order
    // =========================================================================

    "untagged declaration order: first clean parse wins on Json" in {
        val schema = Schema[SSRUShape].untagged
        // SSRUCircle is declared first but needs 'radius'; the input has 'side', so Circle fails.
        // SSRUSquare is declared second and succeeds on {'side':10.0}.
        val result = schema.decodeString[Json]("""{"side":10.0}""")
        assert(result == Result.succeed(SSRUSquare(10.0)))
    }

    "untagged first-declared variant wins when both variants can decode the same input" in {
        val schemaFirst = Schema[SSRUAmbig].untagged
        // Both SSRUAmbigFirst and SSRUAmbigSecond share field 'x'. First-declared wins.
        val resultFirst = schemaFirst.decodeString[Json]("""{"x":5.0}""")
        assert(resultFirst == Result.succeed(SSRUAmbigFirst(5.0)))

        val schemaRev = Schema[SSRUAmbigRev].untagged
        // Reversed declaration order: SSRUAmbigRevSecond declared before SSRUAmbigRevFirst.
        val resultRev = schemaRev.decodeString[Json]("""{"x":5.0}""")
        assert(resultRev == Result.succeed(SSRUAmbigRevSecond(5.0)))
    }

    "untagged round-trips on Json" in {
        val schema           = Schema[SSRUShape].untagged
        val value: SSRUShape = SSRUCircle(10.0)
        val wire             = schema.encodeString[Json](value)
        val result           = schema.decodeString[Json](wire)
        assert(result == Result.succeed(SSRUCircle(10.0)))
    }

    "untagged round-trips on Yaml" in {
        val schema           = Schema[SSRUShape].untagged
        val value: SSRUShape = SSRUSquare(10.0)
        val wire             = schema.encodeString[Yaml](value)
        val result           = schema.decodeString[Yaml](wire)
        assert(result == Result.succeed(SSRUSquare(10.0)))
    }

    "untagged round-trips on Ion" in {
        val schema           = Schema[SSRUShape].untagged
        val value: SSRUShape = SSRUCircle(10.0)
        val wire             = schema.encodeString[Ion](value)
        val result           = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRUCircle(10.0)))
    }

    // =========================================================================
    // Group: untagged non-destructive retry
    // =========================================================================

    "untagged non-destructive retry: later variant succeeds after earlier variants fail" in {
        val schema = Schema[SSRUShape].untagged
        // SSRUBox requires both 'width' and 'height'. SSRUCircle (needs 'radius') and
        // SSRUSquare (needs 'side') both fail. A destructive reader would leave SSRUBox
        // short of input; non-destructive retry gives each attempt the full original tree.
        val input = """{"width":3.0,"height":4.0}"""
        assert(schema.decodeString[Json](input) == Result.succeed(SSRUBox(3.0, 4.0)))

        val yamlWire = schema.encodeString[Yaml](SSRUBox(3.0, 4.0))
        assert(schema.decodeString[Yaml](yamlWire) == Result.succeed(SSRUBox(3.0, 4.0)))

        val ionWire = schema.encodeString[Ion](SSRUBox(3.0, 4.0))
        assert(schema.decodeString[Ion](ionWire) == Result.succeed(SSRUBox(3.0, 4.0)))

        val msgPackBytes = schema.encode[MsgPack](SSRUBox(3.0, 4.0))
        assert(schema.decode[MsgPack](msgPackBytes) == Result.succeed(SSRUBox(3.0, 4.0)))
    }

    // =========================================================================
    // Group: untagged no-match
    // =========================================================================

    "untagged no-match yields NoVariantMatchException in Result on Json" in {
        val schema = Schema[SSRUShape].untagged
        val result = schema.decodeString[Json]("""{"weight":1.0}""")
        result match
            case Result.Failure(ex: NoVariantMatchException) =>
                assert(ex.variants.nonEmpty)
            case other =>
                fail(s"Expected Failure(NoVariantMatchException) but got $other")
        end match
    }

    "untagged no-match yields NoVariantMatchException on Yaml, Ion, and MsgPack" in {
        val schema = Schema[SSRUShape].untagged

        val yamlResult = schema.decodeString[Yaml]("weight: 1.0\n")
        yamlResult match
            case Result.Failure(_: NoVariantMatchException) => succeed("Yaml no-match ok")
            case other                                      => fail(s"Expected Failure(NoVariantMatchException) on Yaml but got $other")
        end match

        val ionResult = schema.decodeString[Ion]("{weight:1.0}")
        ionResult match
            case Result.Failure(_: NoVariantMatchException) => succeed("Ion no-match ok")
            case other                                      => fail(s"Expected Failure(NoVariantMatchException) on Ion but got $other")
        end match

        // Encode a value with a 'weight' field (not present in any SSRUShape variant).
        val msgPackBytes = Schema[SSRUAmbigFirst].encode[MsgPack](SSRUAmbigFirst(9.9))
        val msgResult    = schema.decode[MsgPack](msgPackBytes)
        msgResult match
            case Result.Failure(_: NoVariantMatchException) => succeed("MsgPack no-match ok")
            case Result.Success(v)                          => fail(s"Expected no-match failure but decoded $v")
            case other                                      => fail(s"Expected Failure(NoVariantMatchException) on MsgPack but got $other")
        end match
    }

    // =========================================================================
    // Group: introspecting Yaml/Ion
    // =========================================================================

    "YamlReader and IonReader readStructure materialize a mixed-shape value" in {
        // Encode a record with a nested sequence and scalar fields on Yaml and Ion,
        // then decode back as Structure.Value to confirm both readers are introspecting.
        val sv = Structure.Value.Record(Chunk(
            ("name", Structure.Value.Str("Alice")),
            (
                "tags",
                Structure.Value.Sequence(Chunk(
                    Structure.Value.Str("a"),
                    Structure.Value.Str("b")
                ))
            ),
            ("score", Structure.Value.Decimal(9.5)),
            ("active", Structure.Value.Bool(true))
        ))

        val svSchema = summon[Schema[Structure.Value]]

        val yamlWire   = svSchema.encodeString[Yaml](sv)
        val yamlResult = svSchema.decodeString[Yaml](yamlWire)
        assert(yamlResult.isSuccess)

        val ionWire   = svSchema.encodeString[Ion](sv)
        val ionResult = svSchema.decodeString[Ion](ionWire)
        assert(ionResult.isSuccess)
    }

    "untagged decode on Protobuf surfaces a typed self-describing-reader failure" in {
        val schema     = Schema[SSRUShape].untagged
        val protoBytes = Schema[SSRShape].encode[Protobuf](SSRCircle(10.0))
        val result     = schema.decode[Protobuf](protoBytes)
        result match
            case Result.Panic(ex: SchemaNotSerializableException) =>
                assert(ex.getMessage.contains("self-describing"))
            case other =>
                fail(s"Expected Result.Panic(SchemaNotSerializableException) with 'self-describing' message but got $other")
        end match
    }

    // =========================================================================
    // Group: untagged panic surfacing
    // =========================================================================

    "untagged decode surfaces unexpected error from variant decoder, not NoVariantMatchException" in {
        // An unexpected error thrown by a variant decoder (IllegalStateException) must surface as
        // Result.Panic, never be retried and masked as a no-match: a Panic is not a clean decode miss.
        // Replace the first variant decoder with one that throws to verify the Panic surfaces.
        val base     = Schema[SSRUShape].untagged
        val decoders = base.variantDecoders
        val injectedDecoder: Codec.Reader => Any = (_: Codec.Reader) =>
            throw new IllegalStateException("injected unexpected decoder failure")
        val patched = Schema.copyWith(base)(
            variantDecoders = Chunk(injectedDecoder) ++ decoders.drop(1)
        )
        // SSRUSquare matches only the second decoder (index 1). The first throws
        // IllegalStateException, which must surface as Result.Panic.
        val result = patched.decodeString[Json]("""{"side":10.0}""")
        result match
            case Result.Panic(ex: IllegalStateException) =>
                assert(ex.getMessage == "injected unexpected decoder failure")
            case Result.Failure(_: NoVariantMatchException) =>
                fail("unexpected error was masked as NoVariantMatchException instead of surfacing as a Panic")
            case other =>
                fail(s"Expected Result.Panic(IllegalStateException) but got $other")
        end match
    }

    "untagged clean no-match yields NoVariantMatchException" in {
        // When all variant decoders legitimately reject the input with DecodeExceptions,
        // the result is NoVariantMatchException, not a Panic.
        val schema = Schema[SSRUShape].untagged
        val result = schema.decodeString[Json]("""{"weight":99.0}""")
        result match
            case Result.Failure(ex: NoVariantMatchException) =>
                assert(ex.variants.nonEmpty)
            case other =>
                fail(s"Expected Failure(NoVariantMatchException) for a genuine no-match but got $other")
        end match
    }

    // =========================================================================
    // Group: naming composition
    // =========================================================================

    "adjacent with snake-case naming emits snake-cased tag" in {
        val schema           = Schema[SSRShape].adjacent("kind", "data").renameAllVariants(Schema.NameCase.SnakeCase)
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // SSRCircle -> tokens [SSR, Circle] -> ssr_circle
        assert(wire == """{"kind":"ssr_circle","data":{"radius":10.0}}""")
    }

    "tuple tag resolves through naming layer on encode and decode" in {
        val schema           = Schema[SSRShape].tupleTagged.renameAllVariants(Schema.NameCase.SnakeCase)
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // encode emits the snake-cased tag as element 0
        assert(wire == """["ssr_circle",{"radius":10.0}]""")
        // decode accepts the snake-cased tag as element 0
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRCircle(10.0)))
    }

    "untagged skips tag naming but keeps field naming" in {
        // renameAllVariants on the sum suppresses the tag under Untagged but does NOT rename payload
        // fields: field naming on a sum schema does not propagate into each variant's own product schema.
        // SSRUItem.itemCount is a multi-word camelCase field; under SnakeCase it would become item_count.
        // Asserting the wire still carries itemCount (not item_count) confirms the payload field names are
        // passed through exactly as the variant schema produces them.
        val schema          = Schema[SSRUNamed].untagged.renameAllVariants(Schema.NameCase.SnakeCase)
        val item: SSRUNamed = SSRUItem(42)
        val wire            = schema.encodeString[Json](item)
        // Bare payload: the variant's own schema emits itemCount unchanged; no sum-level field rename fires.
        assert(wire == """{"itemCount":42}""")
        // No variant-name token appears: Untagged suppresses the tag even with renameAllVariants configured.
        assert(!wire.contains("SSRUItem"))
        assert(!wire.contains("ssru_item"))
        // Round-trip: the untagged decoder reconstructs the original value from the bare payload.
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRUItem(42)))
    }

    "sum-level renameAllFields does not rename variant payload field names (payload-bearing reps)" in {
        // A field convention configured on the SUM schema governs only the sum wrapper, never the
        // variant's own product fields. SSRUItem.itemCount is multi-word camelCase: under SnakeCase a leaked
        // sum-level rename would surface as item_count in the payload. Each payload-bearing representation
        // (External, Internal, Adjacent, Tuple) carries the payload as a named object, so a leak would be
        // wire-visible here. TupleFlat (positional) and Untagged (bare payload) are pinned separately.
        val item: SSRUNamed = SSRUItem(42)
        def wire(schema: Schema[SSRUNamed]): String =
            schema.renameAllFields(Schema.NameCase.SnakeCase).encodeString[Json](item)

        // External: wrapper key is the variant name; the payload object keeps itemCount.
        assert(wire(Schema[SSRUNamed]) == """{"SSRUItem":{"itemCount":42}}""")
        // Internal: discriminator key plus inlined payload; itemCount unchanged.
        assert(wire(Schema[SSRUNamed].discriminator("type")) == """{"type":"SSRUItem","itemCount":42}""")
        // Adjacent: tag key plus nested content object; itemCount unchanged.
        assert(wire(Schema[SSRUNamed].adjacent("t", "c")) == """{"t":"SSRUItem","c":{"itemCount":42}}""")
        // Tuple: [tag, payload-object]; itemCount unchanged.
        assert(wire(Schema[SSRUNamed].tupleTagged) == """["SSRUItem",{"itemCount":42}]""")
    }

    // =========================================================================
    // Group: alias acceptance set
    // =========================================================================

    "alias accepted on decode under Internal, Adjacent, and Tuple" in {
        val internalSchema = Schema[SSRAShape].discriminator("type").variantAlias("SSRACircle", "circ_v1")
        val adjacentSchema = Schema[SSRAShape].adjacent("t", "c").variantAlias("SSRACircle", "circ_v1")
        val tupleSchema    = Schema[SSRAShape].tupleTagged.variantAlias("SSRACircle", "circ_v1")

        val internalResult = internalSchema.decodeString[Json]("""{"type":"circ_v1","radius":10.0}""")
        assert(internalResult == Result.succeed(SSRACircle(10.0)))

        val adjacentResult = adjacentSchema.decodeString[Json]("""{"t":"circ_v1","c":{"radius":10.0}}""")
        assert(adjacentResult == Result.succeed(SSRACircle(10.0)))

        val tupleResult = tupleSchema.decodeString[Json]("""["circ_v1",{"radius":10.0}]""")
        assert(tupleResult == Result.succeed(SSRACircle(10.0)))
    }

    "alias accepted on decode under TupleFlat" in {
        val schema = Schema[SSRAShape].tupleFlat.variantAlias("SSRACircle", "circ_v1")
        val wire   = """["circ_v1",10.0]"""
        val result = schema.decodeString[Json](wire)
        assert(result == Result.succeed(SSRACircle(10.0)))
    }

    "External does not accept alias as wrapper key; untagged decode is unaffected by alias" in {
        // External: alias is NOT accepted as the wrapper object key
        val externalSchema = Schema[SSRAShape].variantAlias("SSRACircle", "circ_v1")
        val externalResult = externalSchema.decodeString[Json]("""{"circ_v1":{"radius":10.0}}""")
        assert(!externalResult.isSuccess)

        // The canonical name still works under External
        val canonicalResult = externalSchema.decodeString[Json]("""{"SSRACircle":{"radius":10.0}}""")
        assert(canonicalResult == Result.succeed(SSRACircle(10.0)))

        // Untagged: alias has no effect on which variant a bare payload decodes to
        val untaggedSchema = Schema[SSRAShape].untagged.variantAlias("SSRACircle", "circ_v1")
        val untaggedResult = untaggedSchema.decodeString[Json]("""{"radius":10.0}""")
        assert(untaggedResult == Result.succeed(SSRACircle(10.0)))
    }

    // =========================================================================
    // Group: tupleFlat naming/alias
    // =========================================================================

    "tupleFlat tag resolves through naming layer" in {
        val schema             = Schema[SSRShape].tupleFlat.renameAllVariants(Schema.NameCase.SnakeCase)
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)
        val wire               = schema.encodeString[Json](triangle)
        // SSRTriangle -> tokens [SSR, Triangle] -> ssr_triangle
        assert(wire == """["ssr_triangle",10.0,10.0,10.0]""")
    }

    "tupleFlat field rename leaves the positional wire unchanged" in {
        val schemaPlain        = Schema[SSRShape].tupleFlat
        val schemaWithRename   = Schema[SSRShape].tupleFlat.renameAllFields(Schema.NameCase.SnakeCase)
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)

        val plainWire   = schemaPlain.encodeString[Json](triangle)
        val renamedWire = schemaWithRename.encodeString[Json](triangle)
        // TupleFlat drops field names: a field-level rename is wire-invisible
        assert(plainWire == renamedWire)
        assert(renamedWire == """["SSRTriangle",10.0,10.0,10.0]""")
    }

    // =========================================================================
    // Group: default byte-identity
    // =========================================================================

    "External default encodes and round-trips byte-identically" in {
        val schema           = Schema[SSRShape]
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // External wrapper shape: {"SSRCircle":{"radius":10.0}}
        assert(wire == """{"SSRCircle":{"radius":10.0}}""")
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRCircle(10.0)))
    }

    "Internal flat encodes and round-trips byte-identically" in {
        val schema           = Schema[SSRShape].discriminator("type")
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // Internal flat-discriminator shape: {"type":"SSRCircle","radius":10.0}
        assert(wire == """{"type":"SSRCircle","radius":10.0}""")
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRCircle(10.0)))
    }

    "External and discriminator emit the canonical wrapper and flat-discriminator wire shapes" in {
        // The canonical External and Internal wire shapes for a single-field variant.
        val extSchema          = Schema[SSRShape]
        val discSchema         = Schema[SSRShape].discriminator("type")
        val square: SSRShape   = SSRSquare(5.0)
        val triangle: SSRShape = SSRTriangle(1.0, 2.0, 3.0)

        // External wrapper form: {"VariantName":{"field":value}}
        assert(extSchema.encodeString[Json](square) == """{"SSRSquare":{"side":5.0}}""")
        assert(extSchema.encodeString[Json](triangle) == """{"SSRTriangle":{"a":1.0,"b":2.0,"c":3.0}}""")

        // Internal flat form: {"tagKey":"VariantName","field":value}
        assert(discSchema.encodeString[Json](square) == """{"type":"SSRSquare","side":5.0}""")
        assert(discSchema.encodeString[Json](triangle) == """{"type":"SSRTriangle","a":1.0,"b":2.0,"c":3.0}""")
    }

    // =========================================================================
    // Group: untagged Ion round-trip with Short/Byte/Int fields
    // =========================================================================

    "untagged Ion round-trip with Short field decodes to the correct variant and value" in {
        val schema              = Schema[SSRUIntegral].untagged
        val value: SSRUIntegral = SSRUShortVal(42.toShort)
        val wire                = schema.encodeString[Ion](value)
        val result              = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRUShortVal(42.toShort)))
    }

    "untagged Ion round-trip with Byte field decodes to the correct variant and value" in {
        val schema              = Schema[SSRUIntegral].untagged
        val value: SSRUIntegral = SSRUByteVal(7.toByte)
        val wire                = schema.encodeString[Ion](value)
        val result              = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRUByteVal(7.toByte)))
    }

    "untagged Ion round-trip with Int field decodes to the correct variant and value" in {
        val schema              = Schema[SSRUIntegral].untagged
        val value: SSRUIntegral = SSRUIntVal(1000)
        val wire                = schema.encodeString[Ion](value)
        val result              = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRUIntVal(1000)))
    }

    // =========================================================================
    // Group: adjacent and tupleFlat decode on Yaml and Ion
    // =========================================================================

    "adjacent round-trips on Yaml with concrete value assertion" in {
        val schema          = Schema[SSRShape].adjacent("type", "content")
        val value: SSRShape = SSRCircle(5.0)
        val wire            = schema.encodeString[Yaml](value)
        val result          = schema.decodeString[Yaml](wire)
        assert(result == Result.succeed(SSRCircle(5.0)))
    }

    "adjacent round-trips on Ion with concrete value assertion" in {
        val schema          = Schema[SSRShape].adjacent("type", "content")
        val value: SSRShape = SSRSquare(3.0)
        val wire            = schema.encodeString[Ion](value)
        val result          = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRSquare(3.0)))
    }

    "tupleFlat round-trips on Yaml with concrete value assertion" in {
        val schema          = Schema[SSRShape].tupleFlat
        val value: SSRShape = SSRTriangle(1.0, 2.0, 3.0)
        val wire            = schema.encodeString[Yaml](value)
        val result          = schema.decodeString[Yaml](wire)
        assert(result == Result.succeed(SSRTriangle(1.0, 2.0, 3.0)))
    }

    "tupleFlat round-trips on Ion with concrete value assertion" in {
        val schema          = Schema[SSRShape].tupleFlat
        val value: SSRShape = SSRTriangle(4.0, 5.0, 6.0)
        val wire            = schema.encodeString[Ion](value)
        val result          = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRTriangle(4.0, 5.0, 6.0)))
    }

end SchemaSumRepresentationTest
