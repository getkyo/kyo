package kyo

sealed trait SVNShape derives CanEqual, Schema
case class SVNCircle(radius: Double)                   extends SVNShape derives CanEqual
case class SVNRectangle(width: Double, height: Double) extends SVNShape derives CanEqual
case class SVNSquare(side: Double)                     extends SVNShape derives CanEqual

sealed trait SVNEvent derives CanEqual, Schema
case class SVNUserCreated(userId: String) extends SVNEvent derives CanEqual
case class SVNUserDeleted(userId: String) extends SVNEvent derives CanEqual

// Collision fixture: Ab -> "ab" and AB -> "ab" under SnakeCase (single-word all-caps is lowercased).
// Nested under distinct objects so JVM class names differ by more than case:
// kyo.SVNDupA$Ab vs kyo.SVNDupB$AB, avoiding the case-insensitive-filesystem warning under -Werror.
sealed trait SVNDup derives CanEqual, Schema
object SVNDupA:
    case class Ab(x: Int) extends SVNDup derives CanEqual
object SVNDupB:
    case class AB(x: Int) extends SVNDup derives CanEqual

case class SVNAccount(firstName: String, lastName: String) derives CanEqual, Schema

case class OmitRenameCase(items: Chunk[Int], name: String) derives CanEqual, Schema

// Field collision fixture: aB -> "a_b" and a_B -> "a_b" under SnakeCase.
// Both fields produce identical snake_case tokens so applyFieldConvention raises FieldNameCollisionException.
case class SVNClash(aB: String, a_B: String) derives CanEqual, Schema

class SchemaNamingTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "variantNames explicit encode" in {
        val schema           = Schema[SVNShape].discriminator("type").variantNames("SVNCircle" -> "circle")
        val circle: SVNShape = SVNCircle(5.0)
        val json             = schema.encodeString[Json](circle)
        assert(json.contains("\"type\""))
        assert(json.contains("\"circle\""))
        assert(json.contains("\"radius\""))
        assert(!json.contains("SVNCircle"))
    }

    "variantNames explicit decode round-trip" in {
        val schema = Schema[SVNShape].discriminator("type").variantNames("SVNCircle" -> "circle")
        val result = schema.decodeString[Json]("""{"type":"circle","radius":5.0}""")
        assert(result == Result.succeed(SVNCircle(5.0)))
    }

    "variant round-trip both directions" in {
        val schema           = Schema[SVNShape].discriminator("type").variantNames("SVNCircle" -> "circle")
        val circle: SVNShape = SVNCircle(5.0)
        val encoded          = schema.encodeString[Json](circle)
        val decoded          = schema.decodeString[Json](encoded)
        assert(decoded == Result.succeed(circle))
        val reEncoded = schema.encodeString[Json](decoded.getOrThrow)
        assert(reEncoded == encoded)
    }

    "renameAllVariants convention encode acronym-aware" in {
        val schema          = Schema[SVNEvent].discriminator("kind").renameAllVariants(Schema.NameCase.SnakeCase)
        val event: SVNEvent = SVNUserCreated("u1")
        val json            = schema.encodeString[Json](event)
        assert(json.contains("\"kind\""))
        assert(json.contains("\"svn_user_created\""))
        assert(!json.contains("SVNUserCreated"))
    }

    "precedence explicit beats convention" in {
        val schema =
            Schema[SVNEvent].discriminator("kind")
                .renameAllVariants(Schema.NameCase.SnakeCase)
                .variantNames("SVNUserDeleted" -> "removed")
        val createdJson = schema.encodeString[Json](SVNUserCreated("u1"): SVNEvent)
        val deletedJson = schema.encodeString[Json](SVNUserDeleted("u2"): SVNEvent)
        assert(createdJson.contains("\"svn_user_created\""))
        assert(!createdJson.contains("SVNUserCreated"))
        assert(deletedJson.contains("\"removed\""))
        assert(!deletedJson.contains("SVNUserDeleted"))
        assert(!deletedJson.contains("svn_user_deleted"))
    }

    "variantAlias decode resolves" in {
        val schema =
            Schema[SVNShape].discriminator("type")
                .variantNames("SVNCircle" -> "circle")
                .variantAlias("circle", "round", "disc")
        val r1 = schema.decodeString[Json]("""{"type":"round","radius":5.0}""")
        val r2 = schema.decodeString[Json]("""{"type":"disc","radius":5.0}""")
        assert(r1 == Result.succeed(SVNCircle(5.0)))
        assert(r2 == Result.succeed(SVNCircle(5.0)))
    }

    "variantAlias encode omits alias" in {
        val schema =
            Schema[SVNShape].discriminator("type")
                .variantNames("SVNCircle" -> "circle")
                .variantAlias("circle", "round", "disc")
        val json = schema.encodeString[Json](SVNCircle(5.0): SVNShape)
        assert(json.contains("\"circle\""))
        assert(!json.contains("round"))
        assert(!json.contains("disc"))
    }

    "variantNames collision config-time" in {
        Result.catching[VariantNameCollisionException] {
            Schema[SVNShape].discriminator("type").variantNames("SVNCircle" -> "shape", "SVNSquare" -> "shape")
        } match
            case Result.Failure(e) =>
                assert(e.wireName == "shape")
                assert(e.variants.contains("SVNCircle"))
                assert(e.variants.contains("SVNSquare"))
            case _ =>
                fail("expected VariantNameCollisionException")
    }

    "variantAlias collision with primary config-time" in {
        val result = Result.catching[VariantNameCollisionException] {
            Schema[SVNShape].discriminator("type")
                .variantNames("SVNCircle" -> "circle", "SVNSquare" -> "square")
                .variantAlias("circle", "square")
        }
        assert(result.isFailure)
        result match
            case Result.Failure(e) =>
                assert(e.variants.size >= 2)
            case _ =>
                fail("expected VariantNameCollisionException")
        end match
    }

    "renameAllVariants collision first-serialize" in {
        val schema = Schema[SVNDup].discriminator("t").renameAllVariants(Schema.NameCase.SnakeCase)
        val result = Result.catching[VariantNameCollisionException] {
            schema.encodeString[Json](SVNDupA.Ab(1): SVNDup)
        }
        result match
            case Result.Failure(e) =>
                assert(e.wireName == "ab")
                assert(e.variants.contains("AB"))
                assert(e.variants.contains("Ab"))
            case _ =>
                fail("expected VariantNameCollisionException")
        end match
    }

    "unknown wire variant raises" in {
        val schema = Schema[SVNShape].discriminator("type").variantNames("SVNCircle" -> "circle")
        val result = schema.decodeString[Json]("""{"type":"triangle","radius":1.0}""")
        assert(result.isFailure)
        result match
            case Result.Failure(e: UnknownVariantException) =>
                assert(e.variantName == "triangle")
            case _ =>
                fail("expected UnknownVariantException")
        end match
    }

    "discriminator-less variant naming no-op" in {
        val schema = Schema[SVNShape].variantNames("SVNCircle" -> "circle")
        val json   = schema.encodeString[Json](SVNCircle(5.0): SVNShape)
        assert(json.contains("SVNCircle"))
        assert(!json.contains("\"circle\""))
    }

    "variant builders preserve Focused" in {
        val base = Schema[SVNShape].discriminator("type")
        val named: Schema[SVNShape] { type Focused = base.Focused } =
            base.variantNames("SVNCircle" -> "circle")
        val renamed: Schema[SVNShape] { type Focused = base.Focused } =
            base.renameAllVariants(Schema.NameCase.SnakeCase)
        val aliased: Schema[SVNShape] { type Focused = base.Focused } =
            base.variantNames("SVNCircle" -> "circle").variantAlias("circle", "round")
        succeed("type ascription above is the compile-time check; Focused is preserved through all variant builders")
    }

    "builders return distinct instance no mutation" in {
        val base = Schema[SVNShape].discriminator("type")
        val cfg  = base.variantNames("SVNCircle" -> "circle")
        assert(base.variantNaming.isEmpty)
        assert(cfg.variantNaming.nonEmpty)
    }

    "hot-path flags include slot observable" in {
        val schema           = Schema[SVNShape].discriminator("type").variantNames("SVNCircle" -> "circle")
        val circle: SVNShape = SVNCircle(5.0)
        val json             = schema.encodeString[Json](circle)
        val decoded          = schema.decodeString[Json](json)
        assert(decoded == Result.succeed(circle))
    }

    "un-configured byte-identical presence" in {
        val schema           = Schema[SVNShape].discriminator("type")
        val circle: SVNShape = SVNCircle(5.0)
        val json             = schema.encodeString[Json](circle)
        assert(json.contains("\"type\""))
        assert(json.contains("\"SVNCircle\""))
        assert(json.contains("\"radius\""))
    }

    "variantNaming preserved through a rename transform" in {
        // rename on a sealed trait is blocked at compile time (assertNotSealedTrait).
        // Test createFrom preservation via a product schema carrying variantNaming
        // through the rename-triggered createFrom path.
        // Schema.copyWith is private[kyo], accessible in package kyo.
        val base = Schema.copyWith(Schema[SVNUserCreated])(
            variantNaming = Schema.VariantNaming(
                variantPairs = Chunk("SVNUserCreated" -> "created")
            )
        )
        val schema = base.rename("userId", "user_id")
        assert(schema.variantNaming.nonEmpty)
        assert(schema.variantNaming.variantPairs == Chunk("SVNUserCreated" -> "created"))
        val json = schema.encodeString[Json](SVNUserCreated("u1"))
        assert(json.contains("\"user_id\""))
        assert(json.contains("\"u1\""))
    }

    "config-time variant-name typo raises UnknownVariantException" in {
        val typoResult = Result.catching[UnknownVariantException] {
            Schema[SVNShape].discriminator("type").variantNames("SVNTypo" -> "x")
        }
        assert(typoResult.isFailure)
        typoResult match
            case Result.Failure(e) =>
                assert(e.variantName == "SVNTypo")
            case _ =>
                fail("expected UnknownVariantException for SVNTypo")
        end match

        val aliasResult = Result.catching[UnknownVariantException] {
            Schema[SVNShape].discriminator("type")
                .variantNames("SVNCircle" -> "circle")
                .variantAlias("nope", "alt")
        }
        assert(aliasResult.isFailure)
        aliasResult match
            case Result.Failure(e) =>
                assert(e.variantName == "nope")
            case _ =>
                fail("expected UnknownVariantException for nope")
        end match

        val validResult = Result.catching[UnknownVariantException] {
            Schema[SVNShape].discriminator("type").variantNames("SVNCircle" -> "circle")
        }
        assert(!validResult.isFailure)
    }

    // --- field naming: renameAllFields and aliases ---

    "renameAllFields snake encode" in {
        val schema = Schema[SVNAccount].renameAllFields(Schema.NameCase.SnakeCase)
        val json   = schema.encodeString[Json](SVNAccount("ada", "lovelace"))
        assert(json.contains("\"first_name\""))
        assert(json.contains("\"last_name\""))
        assert(json.contains("\"ada\""))
        assert(json.contains("\"lovelace\""))
        assert(!json.contains("\"firstName\""))
        assert(!json.contains("\"lastName\""))
    }

    "renameAllFields decode round-trip" in {
        val schema = Schema[SVNAccount].renameAllFields(Schema.NameCase.SnakeCase)
        val result = schema.decodeString[Json]("""{"first_name":"ada","last_name":"lovelace"}""")
        assert(result == Result.succeed(SVNAccount("ada", "lovelace")))
    }

    "renameAllFields without discriminator" in {
        val schema  = Schema[SVNAccount].renameAllFields(Schema.NameCase.SnakeCase)
        val encoded = schema.encodeString[Json](SVNAccount("a", "b"))
        val decoded = schema.decodeString[Json](encoded)
        assert(decoded == Result.succeed(SVNAccount("a", "b")))
        assert(encoded.contains("\"first_name\""))
        assert(encoded.contains("\"last_name\""))
    }

    "field alias string decode" in {
        val schema = Schema[SVNAccount].renameAllFields(Schema.NameCase.SnakeCase).alias("first_name", "fname")
        val r1     = schema.decodeString[Json]("""{"first_name":"ada","last_name":"lovelace"}""")
        val r2     = schema.decodeString[Json]("""{"fname":"ada","last_name":"lovelace"}""")
        assert(r1 == Result.succeed(SVNAccount("ada", "lovelace")))
        assert(r2 == Result.succeed(SVNAccount("ada", "lovelace")))
    }

    "field alias encode omits alias" in {
        val schema = Schema[SVNAccount].renameAllFields(Schema.NameCase.SnakeCase).alias("first_name", "fname")
        val json   = schema.encodeString[Json](SVNAccount("ada", "lovelace"))
        assert(json.contains("\"first_name\""))
        assert(!json.contains("\"fname\""))
    }

    "field alias lambda decode" in {
        val schema = Schema[SVNAccount].alias(_.firstName)("first_name", "fname")
        val result = schema.decodeString[Json]("""{"fname":"ada","lastName":"lovelace"}""")
        assert(result == Result.succeed(SVNAccount("ada", "lovelace")))
    }

    "field alias lambda non-field compile error" in {
        typeCheckFailure("Schema[kyo.SVNAccount].alias(_.middleName)(\"mname\")")("not found")
    }

    "renameAllFields collision first-serialize" in {
        val schema = Schema[SVNClash].renameAllFields(Schema.NameCase.SnakeCase)
        val result = Result.catching[FieldNameCollisionException] {
            schema.encodeString[Json](SVNClash("x", "y"))
        }
        result match
            case Result.Failure(e) =>
                assert(e.wireName == "a_b")
                assert(e.fields.contains("aB"))
                assert(e.fields.contains("a_B"))
            case _ =>
                fail("expected FieldNameCollisionException")
        end match
    }

    "field alias collision with primary config-time" in {
        val result = Result.catching[FieldNameCollisionException] {
            Schema[SVNAccount].renameAllFields(Schema.NameCase.SnakeCase).alias("last_name", "first_name")
        }
        result match
            case Result.Failure(e) =>
                assert(e.fields.size >= 2)
                assert(e.wireName == "first_name")
            case _ =>
                fail("expected FieldNameCollisionException")
        end match
    }

    "precedence field rename beats convention" in {
        val schema = Schema[SVNAccount].rename("firstName", "given").renameAllFields(Schema.NameCase.SnakeCase)
        val json   = schema.encodeString[Json](SVNAccount("ada", "lovelace"))
        assert(json.contains("\"given\""))
        assert(json.contains("\"ada\""))
        assert(json.contains("\"last_name\""))
        assert(!json.contains("\"firstName\""))
        assert(!json.contains("\"first_name\""))
    }

    "composition rename+convention+alias round-trips" in {
        val schema =
            Schema[SVNAccount]
                .rename("firstName", "given")
                .renameAllFields(Schema.NameCase.SnakeCase)
                .alias("last_name", "surname")
        val r1 = schema.decodeString[Json]("""{"given":"ada","surname":"lovelace"}""")
        val r2 = schema.decodeString[Json]("""{"given":"ada","last_name":"lovelace"}""")
        assert(r1 == Result.succeed(SVNAccount("ada", "lovelace")))
        assert(r2 == Result.succeed(SVNAccount("ada", "lovelace")))
        val encoded = schema.encodeString[Json](SVNAccount("ada", "lovelace"))
        assert(encoded.contains("\"given\""))
        assert(encoded.contains("\"last_name\""))
    }

    "chain order renameAllFields then rename (createFrom preserves)" in {
        val schema = Schema[SVNAccount].renameAllFields(Schema.NameCase.SnakeCase).rename("lastName", "surname")
        val json   = schema.encodeString[Json](SVNAccount("ada", "lovelace"))
        assert(json.contains("\"first_name\""))
        assert(json.contains("\"ada\""))
        assert(json.contains("\"surname\""))
        assert(json.contains("\"lovelace\""))
    }

    "field builders preserve Focused" in {
        val base = Schema[SVNAccount]
        val r: Schema[SVNAccount] { type Focused = base.Focused } =
            base.renameAllFields(Schema.NameCase.SnakeCase).alias("first_name", "fname")
        succeed("type ascription above is the compile-time check; Focused is preserved through all field builders")
    }

    "typed error reaches caller via Result boundary" in {
        val aliasSchema  = Schema[SVNAccount].renameAllFields(Schema.NameCase.SnakeCase).alias("first_name", "fname")
        val decodeResult = aliasSchema.decodeString[Json]("""{"not_a_real_field_but_decode_proceeds":true}""")
        assert(decodeResult.isFailure)
        decodeResult match
            case Result.Failure(_: MissingFieldException) => succeed("missing required field reported as typed error")
            case _                                        => fail(s"expected MissingFieldException, got: $decodeResult")
        val collisionSchema = Schema[SVNClash].renameAllFields(Schema.NameCase.SnakeCase)
        val encodeResult    = Result.catching[FieldNameCollisionException](collisionSchema.encodeString[Json](SVNClash("x", "y")))
        assert(encodeResult.isFailure)
    }

    "alias before renameAllFields collision is order-independent config-time" in {
        val result = Result.catching[FieldNameCollisionException] {
            Schema[SVNAccount].alias("firstName", "last_name").renameAllFields(Schema.NameCase.SnakeCase)
        }
        result match
            case Result.Failure(e) =>
                assert(e.wireName == "last_name")
            case _ =>
                fail("expected FieldNameCollisionException naming last_name")
        end match
    }

    "renameAllFields before alias collision is order-independent config-time" in {
        val result = Result.catching[FieldNameCollisionException] {
            Schema[SVNAccount].renameAllFields(Schema.NameCase.SnakeCase).alias("firstName", "last_name")
        }
        result match
            case Result.Failure(e) =>
                assert(e.wireName == "last_name")
            case _ =>
                fail("expected FieldNameCollisionException naming last_name")
        end match
    }

    "variantAlias then renameAllVariants collision is order-independent config-time" in {
        // alias("svn_user_deleted", ...) is registered first, then renameAllVariants derives
        // svn_user_deleted as SVNUserDeleted's primary. The convention primary collides with
        // the alias, so renameAllVariants must raise VariantNameCollisionException.
        val result = Result.catching[VariantNameCollisionException] {
            Schema[SVNEvent].discriminator("kind")
                .variantAlias("SVNUserCreated", "svn_user_deleted")
                .renameAllVariants(Schema.NameCase.SnakeCase)
        }
        result match
            case Result.Failure(e) =>
                assert(e.wireName == "svn_user_deleted")
            case _ =>
                fail("expected VariantNameCollisionException for svn_user_deleted")
        end match
    }

    "renameAllVariants then variantAlias collision is order-independent config-time" in {
        // renameAllVariants derives svn_user_deleted as SVNUserDeleted's primary.
        // variantAlias("svn_user_created", "svn_user_deleted") registers svn_user_deleted as
        // an alias for another variant's primary, colliding with SVNUserDeleted's convention primary.
        val result = Result.catching[VariantNameCollisionException] {
            Schema[SVNEvent].discriminator("kind")
                .renameAllVariants(Schema.NameCase.SnakeCase)
                .variantAlias("svn_user_created", "svn_user_deleted")
        }
        result match
            case Result.Failure(e) =>
                assert(e.wireName == "svn_user_deleted")
            case _ =>
                fail("expected VariantNameCollisionException for svn_user_deleted")
        end match
    }

    "field alias on renamed field wire target decodes correctly" in {
        // rename("firstName","given") makes "given" the effective wire for firstName.
        // alias("given","g") registers "g" as an alias for "given". Decoding with "g" must
        // resolve to firstName.
        val schema = Schema[SVNAccount].rename("firstName", "given").alias("given", "g")
        val r1     = schema.decodeString[Json]("""{"given":"ada","lastName":"lovelace"}""")
        val r2     = schema.decodeString[Json]("""{"g":"ada","lastName":"lovelace"}""")
        assert(r1 == Result.succeed(SVNAccount("ada", "lovelace")))
        assert(r2 == Result.succeed(SVNAccount("ada", "lovelace")))
    }

    "omit composes with renameAllFields: empty field absent, retained field snake_cased" in {
        val schema     = Schema[OmitRenameCase].omitEmptyCollections.renameAllFields(Schema.NameCase.SnakeCase)
        val emptyValue = OmitRenameCase(Chunk.empty, "x")
        val out        = schema.encodeString[Json](emptyValue)
        assert(!out.contains("\"items\""), s"source name items must be absent: $out")
        assert(!out.contains("\"Items\""), s"capitalized items must be absent: $out")
        assert(!out.contains("[]"), s"empty array must not appear: $out")
        assert(out.contains("\"name\""), s"name key must be present (already snake_case): $out")
        assert(out.contains("\"x\""), s"name value must be present: $out")

        val nonEmptyValue = OmitRenameCase(Chunk(1, 2, 3), "y")
        val out2          = schema.encodeString[Json](nonEmptyValue)
        assert(out2.contains("\"items\""), s"non-empty items must appear under its snake_case wire name: $out2")
        assert(out2.contains("\"y\""), s"name value y must be present: $out2")
    }

end SchemaNamingTest
