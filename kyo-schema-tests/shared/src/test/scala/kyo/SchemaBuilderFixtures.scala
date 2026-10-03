package kyo

final case class SBFAccount(firstName: String, lastName: String) derives CanEqual

final case class SBFOrder(count: Int, code: String, tags: Chunk[String]) derives CanEqual

sealed trait SBFShape derives CanEqual
final case class SBFCircle(radius: Int) extends SBFShape
final case class SBFSquare(side: Int)   extends SBFShape

sealed trait SBFPoint derives CanEqual
final case class SBFFlat(x: Int)          extends SBFPoint
final case class SBFSpace(x: Int, z: Int) extends SBFPoint

sealed trait SBFCoin derives CanEqual
final case class SBFHeads() extends SBFCoin
final case class SBFTails() extends SBFCoin

sealed trait SBFClash
final case class SBFLeft(n: Int)  extends SBFClash
final case class SBFRight(n: Int) extends SBFClash

final case class SBFHolder(clash: SBFClash)

final class SBFInvalidSku(val text: String)(using val frame: Frame) extends Exception(s"not a sku: $text")

final case class SBFSku(value: String) derives CanEqual

object SBFSku:
    def init(text: String)(using Frame): Result[SBFInvalidSku, SBFSku] =
        if text.nonEmpty && text.forall(_.isLetterOrDigit) then Result.succeed(SBFSku(text)) else Result.fail(SBFInvalidSku(text))

/** A field codec that can refuse, as a library writes one: a `transformVia` schema whose constructor takes the decode site's Frame. */
object SBFSkuText extends kyo.schema.Transformer.Of[SBFSku](Schema.stringSchema.transformVia((text: String) => SBFSku.init(text))(_.value))

final case class SBFLine(@kyo.schema.transform(SBFSkuText) sku: SBFSku, quantity: Int) derives CanEqual

// Not a *Test.scala file, so Frame.derive refuses it as it refuses a library in package kyo: every given here is built with no Frame
// in scope.
object SBFGivens:

    given Schema[SBFAccount] = Schema[SBFAccount].renameAllFields(Schema.NameCase.SnakeCase).alias("first_name", "given_name")

    given Schema[SBFOrder] =
        Schema[SBFOrder]
            .check(_.count <= 100, "count is at most 100")
            .check(_.code)(_ != "zz", "code is not zz")
            .checkMin(_.count)(0)
            .checkMax(_.count)(1000)
            .checkExclusiveMin(_.count)(-1)
            .checkExclusiveMax(_.count)(1001)
            .checkMinLength(_.code)(1)
            .checkMaxLength(_.code)(8)
            .checkPattern(_.code)("[a-z0-9]*")
            .checkMinItems(_.tags)(0)
            .checkMaxItems(_.tags)(4)
            .checkUniqueItems(_.tags)
            .alias(_.code)("sku")
            .fieldId(_.count)(7)

    given Schema[SBFShape] =
        Schema[SBFShape].discriminator("type")
            .variantNames("SBFCircle" -> "circle")
            .renameAllVariants(Schema.NameCase.SnakeCase)
            .variantAlias("circle", "round")

    given Schema[SBFPoint] =
        Schema[SBFPoint]
            .representations(Schema.UnionRepresentation.Internal("kind"))
            .orElseRepresentation(Schema.UnionRepresentation.External)

    given Schema[SBFCoin] = Schema[SBFCoin].discriminator("side").variantNumbers("SBFHeads" -> 1, "SBFTails" -> 2)

    /** Its variant names collide: the given builds, and the first codec call that reaches it raises the collision. */
    given Schema[SBFClash] = Schema[SBFClash].discriminator("type").variantNames("SBFLeft" -> "side", "SBFRight" -> "side")

    given Schema[SBFHolder] = Schema.derived[SBFHolder]

    // Accepts any text: the field's transformer is what refuses, so a refusal proves the transformer ran.
    given Schema[SBFSku] = Schema.stringSchema.transform(SBFSku(_))(_.value)

    given Schema[SBFLine] = Schema.derived[SBFLine]

end SBFGivens
