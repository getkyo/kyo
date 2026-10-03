package kyo.internal

import kyo.*

/** A configuration failure found while a schema was built, held on the schema as data and raised by the first codec call that reaches
  * it, with that call's `Frame`. A builder takes no `Frame`, so it cannot raise the failure itself. A schema keeps the first problem of
  * its chain, so a chain whose builder found one keeps failing whatever follows it.
  */
final private[kyo] case class BuilderProblem(call: String, failure: BuilderProblem.Failure):

    def raise()(using Frame): Nothing =
        given SchemaException.BuilderCall = SchemaException.BuilderCall(call)
        failure match
            case BuilderProblem.Failure.UnknownVariant(name)             => throw UnknownVariantException(Seq.empty, name)
            case BuilderProblem.Failure.VariantCollision(wire, variants) => throw VariantNameCollisionException(wire, variants)
            case BuilderProblem.Failure.FieldCollision(wire, fields)     => throw FieldNameCollisionException(wire, fields)
            case BuilderProblem.Failure.DuplicateRepresentation(chain)   => throw DuplicateRepresentationException(chain)
            case BuilderProblem.Failure.Transform(detail)                => throw TransformFailedException(detail)
        end match
    end raise

end BuilderProblem

private[kyo] object BuilderProblem:

    enum Failure:
        case UnknownVariant(variantName: String)
        case VariantCollision(wireName: String, variants: Chunk[String])
        case FieldCollision(wireName: String, fields: Chunk[String])
        case DuplicateRepresentation(chain: Chunk[Schema.UnionRepresentation])
        case Transform(detail: String)
    end Failure

end BuilderProblem
