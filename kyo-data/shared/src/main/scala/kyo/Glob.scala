package kyo

import scala.collection.mutable.ArrayBuffer

/** A compiled, platform-independent pattern for matching slash-separated paths.
  *
  * Globs are parsed once into an immutable automaton and can then match either a
  * path string or its already-separated components. Matching uses `/` as the
  * separator on every platform and treats dot-prefixed names like any other name.
  *
  * `*` and `?` stay within one segment. `**` is recursive only when it is the
  * complete segment. Character classes, ranges, negation, alternatives, and
  * backslash escaping are supported. Matching has polynomial bounds and does not
  * use regular expressions or filesystem APIs.
  *
  * @see [[Glob.parse]]
  * @see [[Glob.all]]
  * @see [[Glob.CaseSensitivity]]
  */
final case class Glob private[kyo] (
    private[kyo] val source: String,
    private[kyo] val segments: Chunk[Glob.PathSegment]
) derives CanEqual:

    /** Returns the original pattern used to compile this glob. */
    def show: String = source

    /** Tests a slash-separated string against this glob. */
    def matches(value: String, caseSensitivity: Glob.CaseSensitivity): Boolean =
        Glob.matchSegments(this, Glob.splitValue(value), caseSensitivity)

    /** Tests already-separated path components against this glob. */
    def matches(parts: Chunk[String], caseSensitivity: Glob.CaseSensitivity): Boolean =
        Glob.matchSegments(this, parts, caseSensitivity)
end Glob

object Glob:

    private val MaxPatternLength       = 4096
    private val MaxAlternativeBranches = 256

    /** A glob that matches every path, including the empty path. */
    val all: Glob = Glob("**", Chunk(Recursive))

    /** Selects how literal characters and character classes are compared.
      *
      * Case behavior is a property of each match operation, not of the compiled
      * glob. The same glob can therefore be reused for sensitive and insensitive
      * comparisons. Insensitive matching compares Unicode lowercase characters
      * without consulting the host platform's locale.
      *
      * @see [[Glob.parse]]
      */
    enum CaseSensitivity derives CanEqual:
        case Sensitive
        case Insensitive

    /** Parses and compiles a glob pattern. */
    def parse(value: String)(using Frame): Result[GlobParseException, Glob] =
        if value.length > MaxPatternLength then
            Result.fail(GlobParseException(MaxPatternLength, s"pattern exceeds maximum length of $MaxPatternLength"))
        else
            Parser(value).parse()

    given Render[Glob] = Render.from(_.source)

    private inline val EncodedRecursiveSegment = 0
    private inline val EncodedAutomatonSegment = 1
    private inline val EncodedEpsilon          = 0
    private inline val EncodedAnyCharacter     = 1
    private inline val EncodedLiteral          = 2
    private inline val EncodedCharacterClass   = 3
    private inline val EncodingPartSize        = 4096

    // V1 stores counts and state indices in single UTF-16 code units. The 4096-character
    // pattern limit keeps every value representable. Splitting the result keeps each
    // generated string constant well below JVM constant-pool limits.
    private[kyo] def encodeV1(self: Glob): Chunk[String] =
        val encoded = new java.lang.StringBuilder()

        def append(value: Int): Unit = discard(encoded.append(value.toChar))

        append(self.segments.size)
        self.segments.foreach {
            case Recursive => append(EncodedRecursiveSegment)
            case Segment(Automaton(transitions, accept)) =>
                append(EncodedAutomatonSegment)
                append(accept)
                append(transitions.size)
                transitions.foreach { state =>
                    append(state.size)
                    state.foreach {
                        case Epsilon(to) =>
                            append(EncodedEpsilon)
                            append(to)
                        case Consume(to, matcher) =>
                            matcher match
                                case _: AnyCharacter.type =>
                                    append(EncodedAnyCharacter)
                                    append(to)
                                case Literal(expected) =>
                                    append(EncodedLiteral)
                                    append(to)
                                    discard(encoded.append(expected))
                                case CharacterClass(negated, ranges) =>
                                    append(EncodedCharacterClass)
                                    append(to)
                                    append(if negated then 1 else 0)
                                    append(ranges.size)
                                    ranges.foreach { range =>
                                        discard(encoded.append(range.start))
                                        discard(encoded.append(range.end))
                                    }
                            end match
                    }
                }
        }

        Chunk.from(encoded.toString.grouped(EncodingPartSize))
    end encodeV1

    private[kyo] def expression(self: Glob)(using scala.quoted.Quotes): scala.quoted.Expr[Glob] =
        import scala.quoted.*

        val parts = encodeV1(self).map(Expr(_))
        '{ kyo.internal.GlobLiteral.fromEncodedV1(${ Expr(self.source) }, Chunk(${ Varargs(parts) }*)) }
    end expression

    private[kyo] def fromEncodedV1(source: String, parts: Chunk[String]): Glob =
        var partIndex = 0
        var charIndex = 0
        var current   = parts(0)

        def read(): Int =
            while charIndex == current.length do
                partIndex += 1
                current = parts(partIndex)
                charIndex = 0
            end while
            val value = current.charAt(charIndex).toInt
            charIndex += 1
            value
        end read

        val segmentCount = read()
        val segments     = ArrayBuffer.empty[PathSegment]
        var segmentIndex = 0
        while segmentIndex < segmentCount do
            read() match
                case EncodedRecursiveSegment => segments += Recursive
                case EncodedAutomatonSegment =>
                    val accept      = read()
                    val stateCount  = read()
                    val transitions = ArrayBuffer.empty[Chunk[Transition]]
                    var stateIndex  = 0
                    while stateIndex < stateCount do
                        val transitionCount = read()
                        val state           = ArrayBuffer.empty[Transition]
                        var transitionIndex = 0
                        while transitionIndex < transitionCount do
                            val kind = read()
                            val to   = read()
                            val transition =
                                kind match
                                    case EncodedEpsilon      => Epsilon(to)
                                    case EncodedAnyCharacter => Consume(to, AnyCharacter)
                                    case EncodedLiteral      => Consume(to, Literal(read().toChar))
                                    case EncodedCharacterClass =>
                                        val negated    = read() == 1
                                        val rangeCount = read()
                                        val ranges     = ArrayBuffer.empty[CharacterRange]
                                        var rangeIndex = 0
                                        while rangeIndex < rangeCount do
                                            ranges += CharacterRange(read().toChar, read().toChar)
                                            rangeIndex += 1
                                        Consume(to, CharacterClass(negated, Chunk.from(ranges)))
                                    case value => throw new IllegalStateException(s"invalid encoded glob transition kind: $value")
                            state += transition
                            transitionIndex += 1
                        end while
                        transitions += Chunk.from(state)
                        stateIndex += 1
                    end while
                    segments += Segment(Automaton(Chunk.from(transitions), accept))
                case value => throw new IllegalStateException(s"invalid encoded glob segment kind: $value")
            end match
            segmentIndex += 1
        end while

        Glob(source, Chunk.from(segments))
    end fromEncodedV1

    sealed private[kyo] trait PathSegment derives CanEqual
    private case object Recursive                          extends PathSegment
    final private case class Segment(automaton: Automaton) extends PathSegment

    final private case class Automaton(transitions: Chunk[Chunk[Transition]], accept: Int):

        def matches(value: String, caseSensitivity: CaseSensitivity): Boolean =
            val size   = transitions.size
            var active = Array.fill(size)(false)
            active(0) = true
            close(active)

            var index = 0
            while index < value.length do
                val next = Array.fill(size)(false)
                var from = 0
                while from < size do
                    if active(from) then
                        val edges = transitions(from)
                        var edge  = 0
                        while edge < edges.size do
                            edges(edge) match
                                case Consume(to, matcher) if matcher.accepts(value.charAt(index), caseSensitivity) =>
                                    next(to) = true
                                case _ =>
                            end match
                            edge += 1
                        end while
                    end if
                    from += 1
                end while
                active = next
                close(active)
                index += 1
            end while
            active(accept)
        end matches

        private def close(states: Array[Boolean]): Unit =
            val queue = new Array[Int](states.length)
            var read  = 0
            var write = 0
            var state = 0
            while state < states.length do
                if states(state) then
                    queue(write) = state
                    write += 1
                state += 1
            end while
            while read < write do
                val from  = queue(read)
                val edges = transitions(from)
                var edge  = 0
                while edge < edges.size do
                    edges(edge) match
                        case Epsilon(to) if !states(to) =>
                            states(to) = true
                            queue(write) = to
                            write += 1
                        case _ =>
                    end match
                    edge += 1
                end while
                read += 1
            end while
        end close
    end Automaton

    sealed private trait Transition:
        def to: Int

    final private case class Epsilon(to: Int)                            extends Transition
    final private case class Consume(to: Int, matcher: CharacterMatcher) extends Transition

    sealed private trait CharacterMatcher:
        def accepts(value: Char, caseSensitivity: CaseSensitivity): Boolean

    private case object AnyCharacter extends CharacterMatcher:
        def accepts(value: Char, caseSensitivity: CaseSensitivity): Boolean = true

    final private case class Literal(expected: Char) extends CharacterMatcher:
        def accepts(value: Char, caseSensitivity: CaseSensitivity): Boolean =
            equalChars(value, expected, caseSensitivity)

    final private case class CharacterClass(negated: Boolean, ranges: Chunk[CharacterRange]) extends CharacterMatcher:
        def accepts(value: Char, caseSensitivity: CaseSensitivity): Boolean =
            var found = false
            var index = 0
            while index < ranges.size && !found do
                found = ranges(index).contains(value, caseSensitivity)
                index += 1
            if negated then !found else found
        end accepts
    end CharacterClass

    final private case class CharacterRange(start: Char, end: Char):
        def contains(value: Char, caseSensitivity: CaseSensitivity): Boolean =
            caseSensitivity match
                case CaseSensitivity.Sensitive => value >= start && value <= end
                case CaseSensitivity.Insensitive =>
                    val foldedValue = fold(value)
                    val foldedStart = fold(start)
                    val foldedEnd   = fold(end)
                    foldedValue >= foldedStart && foldedValue <= foldedEnd
    end CharacterRange

    sealed private trait Atom derives CanEqual
    final private case class AtomLiteral(value: Char)                                   extends Atom
    private case object AtomAnyCharacter                                                extends Atom
    private case object AtomAnyCharacters                                               extends Atom
    final private case class AtomClass(negated: Boolean, ranges: Chunk[CharacterRange]) extends Atom
    final private case class AtomAlternatives(branches: Chunk[Chunk[Atom]])             extends Atom

    final private class Parser(value: String)(using Frame):
        private val length = value.length

        def parse(): Result[GlobParseException, Glob] =
            splitSegments() match
                case Result.Failure(error) => Result.fail(error)
                case Result.Panic(error)   => Result.panic(error)
                case Result.Success(rawSegments) =>
                    val compiled                           = ArrayBuffer.empty[PathSegment]
                    var index                              = 0
                    var failure: Maybe[GlobParseException] = Absent
                    while index < rawSegments.size && failure.isEmpty do
                        val (raw, offset) = rawSegments(index)
                        if raw == "**" then compiled += Recursive
                        else
                            SegmentParser(raw, offset).parse() match
                                case Result.Success(atoms) => compiled += Segment(compile(atoms))
                                case Result.Failure(error) => failure = Maybe(error)
                                case Result.Panic(error)   => return Result.panic(error)
                        end if
                        index += 1
                    end while
                    failure.fold(Result.succeed(Glob(value, Chunk.from(compiled))))(Result.fail)
        end parse

        private def splitSegments(): Result[GlobParseException, Chunk[(String, Int)]] =
            val segments = ArrayBuffer.empty[(String, Int)]
            var start    = 0
            var index    = 0
            var escaped  = false
            while index < length do
                val char = value.charAt(index)
                if escaped then escaped = false
                else if char == '\\' then escaped = true
                else if char == '/' then
                    segments += ((value.substring(start, index), start))
                    start = index + 1
                end if
                index += 1
            end while
            segments += ((value.substring(start), start))
            Result.succeed(Chunk.from(segments))
        end splitSegments
    end Parser

    final private class SegmentParser(value: String, baseOffset: Int)(using Frame):
        private val length = value.length
        private var index  = 0

        def parse(): Result[GlobParseException, Chunk[Atom]] =
            parseSequence(inAlternative = false).flatMap { case (atoms, delimiter) =>
                if delimiter == 0.toChar then Result.succeed(atoms)
                else Result.fail(GlobParseException(baseOffset + index, s"unexpected delimiter '$delimiter'"))
            }

        private def parseSequence(inAlternative: Boolean): Result[GlobParseException, (Chunk[Atom], Char)] =
            val atoms = ArrayBuffer.empty[Atom]
            while index < length do
                val char = value.charAt(index)
                if inAlternative && (char == ',' || char == '}') then
                    return Result.succeed((Chunk.from(atoms), char))
                char match
                    case '\\' =>
                        if index + 1 >= length then return fail(index, "dangling escape")
                        atoms += AtomLiteral(value.charAt(index + 1))
                        index += 2
                    case '*' =>
                        if index + 1 < length && value.charAt(index + 1) == '*' then
                            return fail(index, "double star must be a complete segment")
                        atoms += AtomAnyCharacters
                        index += 1
                    case '?' =>
                        atoms += AtomAnyCharacter
                        index += 1
                    case '[' =>
                        parseClass() match
                            case Result.Success(atom)  => atoms += atom
                            case Result.Failure(error) => return Result.fail(error)
                            case Result.Panic(error)   => return Result.panic(error)
                    case '{' =>
                        if inAlternative then return fail(index, "nested alternatives are not supported")
                        parseAlternatives() match
                            case Result.Success(atom)  => atoms += atom
                            case Result.Failure(error) => return Result.fail(error)
                            case Result.Panic(error)   => return Result.panic(error)
                        end match
                    case ']' => return fail(index, "unmatched closing bracket")
                    case '}' => return fail(index, "unmatched closing brace")
                    case _ =>
                        atoms += AtomLiteral(char)
                        index += 1
                end match
            end while
            Result.succeed((Chunk.from(atoms), 0.toChar))
        end parseSequence

        private def parseAlternatives(): Result[GlobParseException, Atom] =
            val opening  = index
            val branches = ArrayBuffer.empty[Chunk[Atom]]
            index += 1
            while index < length do
                if value.charAt(index) == ',' || value.charAt(index) == '}' then
                    return fail(index, "empty alternative branch")
                parseSequence(inAlternative = true) match
                    case Result.Failure(error) => return Result.fail(error)
                    case Result.Panic(error)   => return Result.panic(error)
                    case Result.Success((branch, delimiter)) =>
                        branches += branch
                        if delimiter == '}' then
                            index += 1
                            if branches.size < 2 then return fail(opening, "alternative must contain at least two branches")
                            return Result.succeed(AtomAlternatives(Chunk.from(branches)))
                        else if delimiter == ',' then
                            if branches.size >= MaxAlternativeBranches then
                                return fail(index, s"alternative exceeds maximum of $MaxAlternativeBranches alternative branches")
                            index += 1
                        else return fail(opening, "unterminated alternative")
                        end if
                end match
            end while
            fail(opening, "unterminated alternative")
        end parseAlternatives

        private def parseClass(): Result[GlobParseException, Atom] =
            val opening = index
            index += 1
            var negated = false
            if index < length && (value.charAt(index) == '!' || value.charAt(index) == '^') then
                negated = true
                index += 1
            if index >= length then return fail(opening, "unterminated character class")
            if value.charAt(index) == ']' then return fail(opening, "empty character class")

            val elements = ArrayBuffer.empty[ClassElement]
            var closed   = false
            while index < length && !closed do
                value.charAt(index) match
                    case ']' =>
                        closed = true
                        index += 1
                    case '\\' =>
                        if index + 1 >= length then return fail(index, "dangling escape")
                        elements += ClassElement(value.charAt(index + 1), index + 1, escaped = true)
                        index += 2
                    case char =>
                        elements += ClassElement(char, index, escaped = false)
                        index += 1
            end while
            if !closed then return fail(opening, "unterminated character class")
            if elements.isEmpty then return fail(opening, "empty character class")

            val ranges  = ArrayBuffer.empty[CharacterRange]
            var element = 0
            while element < elements.size do
                if element + 2 < elements.size && elements(element + 1).value == '-' && !elements(element + 1).escaped then
                    val start = elements(element).value
                    val end   = elements(element + 2).value
                    if start > end then return fail(elements(element + 1).offset, "descending character range")
                    ranges += CharacterRange(start, end)
                    element += 3
                else
                    val value = elements(element).value
                    ranges += CharacterRange(value, value)
                    element += 1
            end while
            Result.succeed(AtomClass(negated, Chunk.from(ranges)))
        end parseClass

        private def fail[A](relativeOffset: Int, reason: String): Result[GlobParseException, A] =
            Result.fail(GlobParseException(baseOffset + relativeOffset, reason))
    end SegmentParser

    final private case class ClassElement(value: Char, offset: Int, escaped: Boolean)

    private def compile(atoms: Chunk[Atom]): Automaton =
        val transitions = ArrayBuffer(ArrayBuffer.empty[Transition])

        def state(): Int =
            transitions += ArrayBuffer.empty[Transition]
            transitions.size - 1

        def edge(from: Int, transition: Transition): Unit =
            transitions(from) += transition

        def sequence(values: Chunk[Atom], from: Int, to: Int): Unit =
            var current = from
            var index   = 0
            while index < values.size do
                val next = if index == values.size - 1 then to else state()
                values(index) match
                    case AtomLiteral(value) => edge(current, Consume(next, Literal(value)))
                    case AtomAnyCharacter   => edge(current, Consume(next, AnyCharacter))
                    case AtomAnyCharacters =>
                        edge(current, Epsilon(next))
                        edge(current, Consume(current, AnyCharacter))
                    case AtomClass(negated, ranges) => edge(current, Consume(next, CharacterClass(negated, ranges)))
                    case AtomAlternatives(branches) =>
                        var branch = 0
                        while branch < branches.size do
                            sequence(branches(branch), current, next)
                            branch += 1
                end match
                current = next
                index += 1
            end while
            if values.isEmpty then edge(from, Epsilon(to))
        end sequence

        val accept = state()
        sequence(atoms, 0, accept)
        Automaton(Chunk.from(transitions.map(edges => Chunk.from(edges))), accept)
    end compile

    private def equalChars(left: Char, right: Char, caseSensitivity: CaseSensitivity): Boolean =
        caseSensitivity match
            case CaseSensitivity.Sensitive   => left == right
            case CaseSensitivity.Insensitive => fold(left) == fold(right)

    private def fold(value: Char): Char = value.toLower

    private[kyo] def matchSegments(self: Glob, parts: Chunk[String], caseSensitivity: CaseSensitivity): Boolean =
        var validation = 0
        while validation < parts.size do
            if parts(validation).contains('/') then return false
            validation += 1
        val input    = if parts.isEmpty then Chunk("") else parts
        var previous = Array.fill(input.size + 1)(false)
        previous(0) = true
        var pattern = 0
        while pattern < self.segments.size do
            val current = Array.fill(input.size + 1)(false)
            self.segments(pattern) match
                case Recursive =>
                    current(0) = previous(0)
                    var part = 1
                    while part <= input.size do
                        current(part) = previous(part) || current(part - 1)
                        part += 1
                case Segment(automaton) =>
                    var part = 1
                    while part <= input.size do
                        current(part) = previous(part - 1) && automaton.matches(input(part - 1), caseSensitivity)
                        part += 1
            end match
            previous = current
            pattern += 1
        end while
        previous(input.size)
    end matchSegments

    private[kyo] def splitValue(value: String): Chunk[String] =
        val parts = ArrayBuffer.empty[String]
        var start = 0
        var index = 0
        while index < value.length do
            if value.charAt(index) == '/' then
                parts += value.substring(start, index)
                start = index + 1
            index += 1
        end while
        parts += value.substring(start)
        Chunk.from(parts)
    end splitValue

end Glob

/** Describes why a glob pattern could not be parsed.
  *
  * The offset is a zero-based character offset into the original pattern.
  * The reason is stable, human-readable text suitable for diagnostics.
  *
  * @param offset
  *   location of the invalid syntax
  * @param reason
  *   explanation of the invalid syntax
  */
final case class GlobParseException(offset: Int, reason: String)(using Frame)
    extends KyoException(reason)
    derives CanEqual

extension (inline sc: StringContext)

    /** Compiles and embeds a constant glob literal, reporting invalid syntax at compile time. */
    inline def glob(): Glob = ${ GlobMacro.literal('sc) }
end extension

private[kyo] object GlobMacro:

    import scala.quoted.*

    def literal(sc: Expr[StringContext])(using Quotes): Expr[Glob] =
        val parts = sc.valueOrAbort.parts
        if parts.size != 1 then
            quotes.reflect.report.errorAndAbort("glob literals do not accept interpolated values")
        val value = parts.head
        // The macro implementation has no runtime caller frame. Parse failures are
        // re-emitted by the compiler at the glob literal's source position.
        Glob.parse(value)(using Frame.internal) match
            case Result.Failure(error) =>
                quotes.reflect.report.errorAndAbort(s"invalid glob at offset ${error.offset}: ${error.reason}")
            case Result.Panic(error)  => throw error
            case Result.Success(glob) => Glob.expression(glob)
        end match
    end literal
end GlobMacro
