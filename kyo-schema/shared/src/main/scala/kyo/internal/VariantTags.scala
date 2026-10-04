package kyo.internal

import kyo.*
import kyo.Codec.Reader

/** A variant's tag on the wire: a name, or a number for a sum whose variants are numbered. A number is never carried as text. */
private[kyo] enum VariantTag derives CanEqual:
    case Name(value: String)
    case Number(value: Long)

    /** The tag as it reads in a failure message. */
    def show: String =
        this match
            case Name(value)   => value
            case Number(value) => value.toString
end VariantTag

/** A sum's tags, one table per schema: each variant's tag, the aliases that also read as it, and the kind the wire carries.
  *
  * Every representation that writes a tag value (`@discriminator`, `@adjacent`, `tupleTagged`, `tupleFlat`, `@tagOnly`) reads and
  * writes it here. The wrapper object writes no tag value: its key is the variant's Scala name, which an [[VariantTags.Entry]] keeps.
  * The catch-all variant has no tag of its own, since its encode writes the tag it holds, so no tag resolves to it.
  *
  * Two checks wait for the first codec use, through `checked`: a numbered sum with a variant left unnumbered, and two variants whose
  * convention-derived names collide. Builders compose in any order, so a schema partway through a valid chain may fail both; a later
  * `catchAll`, `variantNumbers` or `variantNames` completes it.
  */
final private[kyo] class VariantTags private (
    val entries: Chunk[VariantTags.Entry],
    val numbered: Boolean,
    catchAll: Maybe[CatchAll],
    structure: => Structure.Type,
    deferred: => Maybe[VariantTags.Problem]
):
    private val byTag: Map[VariantTag, String] =
        entries.flatMap(e => (e.tag.toChunk ++ e.aliases).map(_ -> e.scalaName)).toMap

    private val bySource: Map[String, VariantTag] =
        entries.flatMap(e => e.tag.toChunk.map(e.scalaName -> _)).toMap

    private lazy val problem: Maybe[VariantTags.Problem] = deferred

    /** Raises the first-use failure, if the sum has one. */
    def checked()(using Frame): Unit =
        problem.foreach {
            case VariantTags.Problem.Unnumbered(names) =>
                throw TransformFailedException(
                    "variantNumbers numbers some variants, so every variant except the catch-all needs a number; these have none: " +
                        s"${names.mkString(", ")}."
                )
            case VariantTags.Problem.Collision(wire, sources) =>
                throw VariantNameCollisionException(wire, sources)
        }

    /** The tag `scalaName` writes, or `Absent` for the catch-all. */
    def tagOf(scalaName: String): Maybe[VariantTag] = Maybe.fromOption(bySource.get(scalaName))

    /** The Scala name of the variant `tag` names, as its primary tag or an alias. */
    def variantOf(tag: VariantTag): Maybe[String] = Maybe.fromOption(byTag.get(tag))

    /** The Scala name of the variant `tag` names, or an [[kyo.UnknownVariantException]] naming the tag. */
    def apply(tag: VariantTag)(using Frame): String = byTag.getOrElse(tag, throw UnknownVariantException(Seq.empty, tag.show))

    /** The tags that name a known variant, aliases included. */
    def known: Set[VariantTag] = byTag.keySet

    /** The next tag on `reader`: an integer for a numbered sum, else a string. A tag of the other kind fails as the reader's mismatch. */
    def read(reader: Reader): VariantTag =
        if numbered then VariantTag.Number(reader.long()) else VariantTag.Name(reader.string())

    /** The value a tag is written as. */
    def write(tag: VariantTag): Structure.Value =
        tag match
            case VariantTag.Name(value)   => Structure.Value.Str(value)
            case VariantTag.Number(value) => Structure.Value.Integer(value)

    /** The value `scalaName` writes as its tag. The catch-all, which holds its own tag, is written by name. */
    def written(scalaName: String): Structure.Value =
        tagOf(scalaName).fold(Structure.Value.Str(scalaName))(write)

    /** The tag a captured value holds, or `Absent` when it is not of the sum's kind. Ion captures an integer as a `BigNum`. */
    def captured(value: Structure.Value): Maybe[VariantTag] =
        value match
            case Structure.Value.Str(name) if !numbered                           => Present(VariantTag.Name(name))
            case Structure.Value.Integer(number) if numbered                      => Present(VariantTag.Number(number))
            case Structure.Value.BigNum(number) if numbered && number.isValidLong => Present(VariantTag.Number(number.toLong))
            case _                                                                => Absent

    /** Whether the catch-all's tag field is an `Int` or a `Long`, read from the variant's structure. */
    lazy val catchAllTagIsNumber: Boolean =
        (catchAll, structure) match
            case (Present(c), Structure.Type.Sum(_, _, _, variants, _, _)) if c.tagIndex >= 0 =>
                variants.find(_.name == c.variant).exists(_.variantType match
                    case Structure.Type.Product(_, _, _, fields, _) if c.tagIndex < fields.size =>
                        fields(c.tagIndex).fieldType match
                            case p: Structure.Type.Primitive =>
                                p.kind == Structure.PrimitiveKind.Int || p.kind == Structure.PrimitiveKind.Long
                            case _ => false
                    case _ => false)
            case _ => false
end VariantTags

private[kyo] object VariantTags:

    /** A variant's Scala name, the tag it writes (`Absent` for the catch-all), and the aliases that also read as it. */
    final case class Entry(scalaName: String, tag: Maybe[VariantTag], aliases: Chunk[VariantTag]) derives CanEqual

    /** A first-use failure: a numbered sum's unnumbered variants, or a convention-derived name two variants share. */
    enum Problem derives CanEqual:
        case Unnumbered(names: Chunk[String])
        case Collision(wire: String, sources: Chunk[String])
    end Problem

    /** The table for the variants named `variantNames`, in declaration order, under `naming`. */
    def apply(
        variantNames: Chunk[String],
        naming: Schema.VariantNaming,
        catchAll: Maybe[CatchAll],
        structure: => Structure.Type
    ): VariantTags =
        val catchAllName = catchAll.map(_.variant)
        if naming.numbered then
            val numbers = naming.variantNumbers.toMap
            val entries = variantNames.map(name =>
                Entry(name, Maybe.fromOption(numbers.get(name)).map(n => VariantTag.Number(n.toLong)), Chunk.empty)
            )
            val unnumbered = variantNames.filterNot(name => numbers.contains(name) || catchAllName.contains(name))
            new VariantTags(
                entries,
                numbered = true,
                catchAll,
                structure,
                if unnumbered.isEmpty then Absent else Present(Problem.Unnumbered(unnumbered))
            )
        else
            val explicit                      = naming.variantPairs.toMap
            val convention                    = naming.variantCase.map(NameCaseConversion.convert)
            def primary(name: String): String =
                explicit.getOrElse(name, convention.fold(name)(convert => convert(name)))
            val aliasesOf = naming.variantAliases.groupMap(_._2)(alias => VariantTag.Name(alias._1): VariantTag)
            val entries   = variantNames.map { name =>
                if catchAllName.contains(name) then Entry(name, Absent, Chunk.empty)
                else
                    val wire = primary(name)
                    Entry(name, Present(VariantTag.Name(wire)), Chunk.from(aliasesOf.getOrElse(wire, Chunk.empty)))
            }
            val collision =
                if convention.isEmpty then Absent
                else
                    Maybe.fromOption(variantNames.groupBy(primary).toSeq.sortBy(_._1).collectFirst {
                        case (wire, sources) if sources.distinct.size > 1 => (wire, Chunk.from(sources.distinct.toSeq.sorted))
                    })
            new VariantTags(
                entries,
                numbered = false,
                catchAll,
                structure,
                collision.map((wire, sources) => Problem.Collision(wire, sources))
            )
        end if
    end apply

end VariantTags
