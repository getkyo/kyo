package kyo.parse

import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.Effect
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.matching.Regex

/** Parser combinator effect for compositional text parsing.
  *
  * This design enables building sophisticated parsers that can handle complex grammars while maintaining readability and composition of
  * parsing logic. The core parsing model is based on consuming text incrementally and producing typed values with precise control over
  * backtracking behavior.
  *
  * Parse operations follow a consistent pattern where parsers attempt to match input text and either succeed by returning a value and the
  * remaining unconsumed input, or fail, allowing the system to try alternative parsing strategies. This approach supports both
  * deterministic parsing (where exactly one interpretation is valid) and ambiguous grammars (where multiple interpretations might be
  * acceptable).
  *
  * The effect provides a rich set of combinators that build on the fundamental `read` operation, including sequence, alternative,
  * repetition, and look-ahead parsers. It also includes pre-built parsers for common needs like whitespace handling, numeric values,
  * identifiers, and character recognition patterns.
  *
  * Parse is well-suited for implementing domain-specific languages, configuration formats, or any structured text processing that benefits
  * from declarative grammar definitions with strong composition and error handling capabilities.
  *
  * @see
  *   [[kyo.Parse.read]] for the fundamental parsing operation that other combinators build upon
  * @see
  *   [[kyo.Parse.firstOf]], [[kyo.Parse.anyIn]], [[kyo.Parse.inOrder]] for combining parsers
  * @see
  *   [[kyo.Parse.attempt]], [[kyo.Parse.peek]] for parsers with look-ahead and backtracking
  * @see
  *   [[kyo.Parse.run]] for executing parsers against input text
  */
sealed trait Parse[In] extends ArrowEffect[[in] =>> Parse.Op[In, in], Id]

object Parse:

    enum Op[In, +Out]:
        case ModifyState(modify: ParseState[In] => (ParseState[In], Maybe[Out]))
        case Attempt[In, A, S](parser: A < (Parse[In] & S))                                              extends Op[In, Maybe[A] < S]
        case Require[In, A, S](parser: A < (Parse[In] & S))                                              extends Op[In, A < S]
        case RecoverWith[In, A, S](parser: A < (Parse[In] & S), recoverStrategy: RecoverStrategy[In, A]) extends Op[In, A < S]
        case Discard[In, A, S](parser: A < (Parse[In] & S), isDiscarded: In => Boolean)                  extends Op[In, A < S]
    end Op

    inline def modifyState[Out](using
        inline frame: Frame
    )[In](modify: ParseState[In] => (ParseState[In], Maybe[Out]))(using inline tag: Tag[Parse[In]]): Out < Parse[In] =
        ArrowEffect.suspend(tag, Op.ModifyState(modify))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyIn, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[In, Out, S](parsers: Seq[() => Out < (Parse[In] & S)])(using Tag[Parse[In]], Frame): Out < (Parse[In] & S) =
        Effect.defer:
            Loop(parsers):
                case Seq() => fail("No branch succeeded")
                case head +: tail =>
                    attempt(head()).map:
                        case Present(value) => Loop.done(value)
                        case Absent         => Loop.continue(tail)

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyIn, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[A, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => A < (Parse[In] & S)
    )(using Tag[Parse[In]], Frame): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyIn, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[A, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => A < (Parse[In] & S),
        parser3: => A < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyIn, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[A, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => A < (Parse[In] & S),
        parser3: => A < (Parse[In] & S),
        parser4: => A < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyIn, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[A, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => A < (Parse[In] & S),
        parser3: => A < (Parse[In] & S),
        parser4: => A < (Parse[In] & S),
        parser5: => A < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4, () => parser5))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyIn, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[A, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => A < (Parse[In] & S),
        parser3: => A < (Parse[In] & S),
        parser4: => A < (Parse[In] & S),
        parser5: => A < (Parse[In] & S),
        parser6: => A < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4, () => parser5, () => parser6))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyIn, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[A, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => A < (Parse[In] & S),
        parser3: => A < (Parse[In] & S),
        parser4: => A < (Parse[In] & S),
        parser5: => A < (Parse[In] & S),
        parser6: => A < (Parse[In] & S),
        parser7: => A < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4, () => parser5, () => parser6, () => parser7))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyIn, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[A, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => A < (Parse[In] & S),
        parser3: => A < (Parse[In] & S),
        parser4: => A < (Parse[In] & S),
        parser5: => A < (Parse[In] & S),
        parser6: => A < (Parse[In] & S),
        parser7: => A < (Parse[In] & S),
        parser8: => A < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4, () => parser5, () => parser6, () => parser7, () => parser8))

    inline def recoverWith[In, Out, S](
        parser: Out < (Parse[In] & S),
        recoverStrategy: RecoverStrategy[In, Out]
    )(using inline tag: Tag[Parse[In]], inline frame: Frame): Out < (Parse[In] & S) =
        ArrowEffect.suspendWith(tag, Op.RecoverWith(parser, recoverStrategy))(x => x)

    /** Modifies a computation to automatically handle whitespace in all its parsing operations. Any parser used within the computation will
      * consume and discard leading and trailing whitespace around its expected input.
      *
      * Since this operates through the Aspect effect, it affects all parsing operations within the computation, not just the immediate
      * parser. This makes it particularly useful for complex parsers like those for mathematical expressions or programming languages where
      * whitespace should be uniformly handled.
      *
      * @param v
      *   Computation containing parsing operations
      * @return
      *   A computation where all parsing operations handle surrounding whitespace
      */
    inline def spaced[In, Out, S](parser: Out < (Parse[In] & S), isWhitespace: In => Boolean = (_: Char).isWhitespace)(using
        inline tag: Tag[Parse[In]],
        frame: Frame
    ): Out < (Parse[In] & S) =
        ArrowEffect.suspendWith(tag, Op.Discard(parser, isWhitespace))(x => x)

    def readOne[In, Out](f: In => Result[Chunk[String], Out])(using Tag[Parse[In]], Frame): Out < Parse[In] =
        read(input =>
            if input.done then Result.fail(Chunk(ParseFailure("EOF", input.position)))
            else
                f(input.remaining.head) match
                    case Result.Failure(messages) => Result.fail(messages.map(ParseFailure(_, input.position)))
                    case Result.Success(out)      => Result.succeed((input.advance(1), out))
        )

    def readWhile[A](f: A => Boolean)(using Tag[Parse[A]], Frame): Chunk[A] < Parse[A] =
        read(input =>
            val matched = input.remaining.takeWhile(f)
            Result.succeed((input.advance(matched.length), matched))
        )

    /** Attempts to parse input using the provided parsing function
      *
      * @param f
      *   Function that takes remaining text and returns:
      *   - Present((remaining, value)) when parsing succeeds, containing the unconsumed text and parsed value
      *   - Absent when the parser doesn't match at the current position, allowing for backtracking
      * @return
      *   Parsed value if successful, drops the current parse branch if unsuccessful
      */
    def read[In, Out](f: ParseInput[In] => Result[Chunk[ParseFailure], (ParseInput[In], Out)])(using
        Tag[Parse[In]],
        Frame
    ): Out < Parse[In] =
        modifyState(state =>
            f(state.input) match
                case Result.Panic(error) => throw error
                case Result.Failure(failures) =>
                    (state.copy(failures = state.failures ++ failures), Absent)
                case Result.Success((newInput, out)) =>
                    (state.copy(input = newInput), Present(out))
        )

    /** Fail the current parse branch with the given error.
      *
      * @return
      *   Nothing, as this always fails the current branch
      */
    def fail[In](message: String)(using Tag[Parse[In]], Frame): Nothing < Parse[In] =
        modifyState(state =>
            (state.copy(failures = state.failures :+ ParseFailure(message, state.input.position)), Absent)
        )

    /** Get the position of the next token to parse.
      *
      * @return
      *   the index of the next token
      */
    def position[In](using Tag[Parse[In]], Frame): Int < Parse[In] =
        modifyState(state => (state, Present(state.input.position)))

    /** Rewind the parser to a specific parsing position.
      */
    def rewind[In](position: Int)(using Tag[Parse[In]], Frame): Unit < Parse[In] =
        modifyState(state => (state.copy(input = state.input.copy(position = position)), Present(())))

    /** Consumes any single input element
      *
      * @return
      *   The consumed input element
      */
    def any[A](using Tag[Parse[A]], Frame): A < Parse[A] =
        readOne(Result.succeed)

    /** Consumes a character matching predicate
      *
      * @param f
      *   Predicate function for character
      * @param errorMessage
      *   The error message if the predicate fails
      * @return
      *   Matching character
      */
    def anyIf[A](f: A => Boolean)(errorMessage: A => String)(using Tag[Parse[A]], Frame): A < Parse[A] =
        readOne(in =>
            if f(in) then Result.succeed(in)
            else Result.fail(Chunk(errorMessage(in)))
        )

    def anyMatch[A](using Frame)[In](pf: PartialFunction[In, A])(using Tag[Parse[In]]): A < Parse[In] =
        Parse.read(in =>
            if in.done then Result.fail(Chunk(ParseFailure("Unexpected token, got EOF", in.position)))
            else if pf.isDefinedAt(in.remaining.head) then Result.succeed((in.advance(1), pf(in.remaining.head)))
            else Result.fail(Chunk(ParseFailure("Unexpected token", in.position)))
        )

    @targetName("anyInSeq")
    def anyIn[A](values: Seq[A])(using Tag[Parse[A]], Frame): A < Parse[A] =
        anyIf(values.contains)(in => s"Expected: ${values.mkString(", ")}, Got: $in")

    @targetName("anyInString")
    def anyIn(values: String)(using Frame): Char < Parse[Char] =
        anyIf[Char](values.contains(_))(in => s"Expected: ${values.mkString(", ")}, Got: $in")

    @targetName("anyInVarargs")
    def anyIn[A](values: A*)(using Tag[Parse[A]], Frame): A < Parse[A] =
        anyIn(values)

    @targetName("anyNotInSeq")
    def anyNotIn[A](values: Seq[A])(using Tag[Parse[A]], Frame): A < Parse[A] =
        anyIf[A](!values.contains(_))(in => s"Expected something else than: ${values.mkString(", ")}, Got: $in")

    @targetName("anyNotInString")
    def anyNotIn(values: String)(using Frame): Char < Parse[Char] =
        anyIf[Char](!values.contains(_))(in => s"Expected something else than: ${values.mkString(", ")}, Got: $in")

    @targetName("anyNotInVarargs")
    def anyNotIn[A](values: A*)(using Tag[Parse[A]], Frame): A < Parse[A] =
        anyNotIn(values)

    /** Matches exact input
      *
      * @param value
      *   Input to match
      * @return
      *   Unit if text matches
      */
    def literal[A](value: A)(using CanEqual[A, A], Tag[Parse[A]], Frame): A < Parse[A] =
        anyIf[A](_ == value)(in => s"Expected: $value, Got: $in")

    /** Matches exact text
      *
      * @param str
      *   Text to match
      * @return
      *   Unit if text matches
      */
    def literal(text: Text)(using Frame): Text < Parse[Char] =
        read(in =>
            if in.remaining.startsWith(text.toString) then
                Result.succeed((in.advance(text.length), text))
            else
                Result.fail(Chunk(ParseFailure(s"Expected: $text", in.position)))
        )

    private def inOrder[A, In, S](parsers: Seq[() => A < (Parse[In] & S)])(using Tag[Parse[In]], Frame): Chunk[A] < (Parse[In] & S) =
        Kyo.foldLeft(parsers)(Chunk.empty[A])((acc, parser) => parser().map(acc :+ _))

    /** Parses two parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @return
      *   Tuple containing both parsed results in order. The parse branch is dropped if either parser fails
      */
    def inOrder[A, B, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => B < (Parse[In] & S)
    )(using Tag[Parse[In]], Frame): (A, B) < (Parse[In] & S) =
        inOrder(Chunk(
            () => parser1,
            () => parser2
        )).map { s =>
            (s(0).asInstanceOf[A], s(1).asInstanceOf[B])
        }

    /** Parses three parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @return
      *   Tuple containing all three parsed results in order. The parse branch is dropped if any parser fails
      */
    def inOrder[A, B, C, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => B < (Parse[In] & S),
        parser3: => C < (Parse[In] & S)
    )(using
        Tag[Parse[In]],
        Frame
    ): (A, B, C) < (Parse[In] & S) =
        inOrder(Chunk(
            () => parser1,
            () => parser2,
            () => parser3
        )).map { s =>
            (s(0).asInstanceOf[A], s(1).asInstanceOf[B], s(2).asInstanceOf[C])
        }

    /** Parses four parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @return
      *   Tuple containing all four parsed results in order. The parse branch is dropped if any parser fails
      */
    def inOrder[A, B, C, D, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => B < (Parse[In] & S),
        parser3: => C < (Parse[In] & S),
        parser4: => D < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): (A, B, C, D) < (Parse[In] & S) =
        inOrder(Chunk(
            () => parser1,
            () => parser2,
            () => parser3,
            () => parser4
        )).map { s =>
            (s(0).asInstanceOf[A], s(1).asInstanceOf[B], s(2).asInstanceOf[C], s(3).asInstanceOf[D])
        }

    /** Parses five parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @return
      *   Tuple containing all five parsed results in order. The parse branch is dropped if any parser fails
      */
    def inOrder[A, B, C, D, E, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => B < (Parse[In] & S),
        parser3: => C < (Parse[In] & S),
        parser4: => D < (Parse[In] & S),
        parser5: => E < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): (A, B, C, D, E) < (Parse[In] & S) =
        inOrder(Chunk(
            () => parser1,
            () => parser2,
            () => parser3,
            () => parser4,
            () => parser5
        )).map { s =>
            (s(0).asInstanceOf[A], s(1).asInstanceOf[B], s(2).asInstanceOf[C], s(3).asInstanceOf[D], s(4).asInstanceOf[E])
        }

    /** Parses six parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @return
      *   Tuple containing all six parsed results in order. The parse branch is dropped if any parser fails
      */
    def inOrder[A, B, C, D, E, F, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => B < (Parse[In] & S),
        parser3: => C < (Parse[In] & S),
        parser4: => D < (Parse[In] & S),
        parser5: => E < (Parse[In] & S),
        parser6: => F < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): (A, B, C, D, E, F) < (Parse[In] & S) =
        inOrder(Chunk(
            () => parser1,
            () => parser2,
            () => parser3,
            () => parser4,
            () => parser5,
            () => parser6
        )).map {
            s =>
                (
                    s(0).asInstanceOf[A],
                    s(1).asInstanceOf[B],
                    s(2).asInstanceOf[C],
                    s(3).asInstanceOf[D],
                    s(4).asInstanceOf[E],
                    s(5).asInstanceOf[F]
                )
        }

    /** Parses seven parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @return
      *   Tuple containing all seven parsed results in order. The parse branch is dropped if any parser fails
      */
    def inOrder[A, B, C, D, E, F, G, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => B < (Parse[In] & S),
        parser3: => C < (Parse[In] & S),
        parser4: => D < (Parse[In] & S),
        parser5: => E < (Parse[In] & S),
        parser6: => F < (Parse[In] & S),
        parser7: => G < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): (A, B, C, D, E, F, G) < (Parse[In] & S) =
        inOrder(Chunk(
            () => parser1,
            () => parser2,
            () => parser3,
            () => parser4,
            () => parser5,
            () => parser6,
            () => parser7
        )).map {
            s =>
                (
                    s(0).asInstanceOf[A],
                    s(1).asInstanceOf[B],
                    s(2).asInstanceOf[C],
                    s(3).asInstanceOf[D],
                    s(4).asInstanceOf[E],
                    s(5).asInstanceOf[F],
                    s(6).asInstanceOf[G]
                )
        }

    /** Parses eight parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @return
      *   Tuple containing all eight parsed results in order. The parse branch is dropped if any parser fails
      */
    def inOrder[A, B, C, D, E, F, G, H, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => B < (Parse[In] & S),
        parser3: => C < (Parse[In] & S),
        parser4: => D < (Parse[In] & S),
        parser5: => E < (Parse[In] & S),
        parser6: => F < (Parse[In] & S),
        parser7: => G < (Parse[In] & S),
        parser8: => H < (Parse[In] & S)
    )(
        using
        Tag[Parse[In]],
        Frame
    ): (A, B, C, D, E, F, G, H) < (Parse[In] & S) =
        inOrder(Chunk(
            () => parser1,
            () => parser2,
            () => parser3,
            () => parser4,
            () => parser5,
            () => parser6,
            () => parser7,
            () => parser8
        )).map {
            s =>
                (
                    s(0).asInstanceOf[A],
                    s(1).asInstanceOf[B],
                    s(2).asInstanceOf[C],
                    s(3).asInstanceOf[D],
                    s(4).asInstanceOf[E],
                    s(5).asInstanceOf[F],
                    s(6).asInstanceOf[G],
                    s(7).asInstanceOf[H]
                )
        }

    /** Repeats a parser until it fails
      *
      * @param element
      *   Parser to repeat
      * @return
      *   Chunk of all successful results
      */
    def repeat[Out](using Frame)[In, S](element: => Out < (Parse[In] & S))(using Tag[Parse[In]]): Chunk[Out] < (Parse[In] & S) =
        Loop(Chunk.empty[Out]): acc =>
            attempt(element).map:
                case Present(a) => Loop.continue(acc.append(a))
                case Absent     => Loop.done(acc)

    /** Repeats a parser exactly n times
      *
      * @param n
      *   Number of repetitions required
      * @param p
      *   Parser to repeat
      * @return
      *   Chunk of n results, fails if can't get n results
      */
    def repeat[Out](using Frame)[In, S](n: Int)(element: Out < (Parse[In] & S))(using Tag[Parse[In]]): Chunk[Out] < (Parse[In] & S) =
        Loop.indexed(Chunk.empty[Out]): (idx, acc) =>
            if idx == n then Loop.done(acc)
            else
                attempt(element).map:
                    case Present(a) => Loop.continue(acc.append(a))
                    case Absent     => fail("Unexpected token")

    def repeatUntil[Out](using
        Frame
    )[In, S](
        element: => Out < (Parse[In] & S),
        until: => Any < (Parse[In] & S)
    )(using Tag[Parse[In]]): Chunk[Out] < (Parse[In] & S) =
        firstOf(
            until.andThen(Chunk.empty[Out]),
            for
                head <- element
                tail <- repeatUntil(element, until)
            yield head +: tail
        )

    def skipUntil[In, Out](
        until: => Out < Parse[In]
    )(using Tag[Parse[In]], Frame): Out < Parse[In] =
        attempt(until).map:
            case Absent =>
                attempt(end).map:
                    case Absent     => any.andThen(skipUntil(until))
                    case Present(_) => fail("End of File")
            case Present(result) => result

    /** Parses a sequence of elements separated by a delimiter. For example, parsing comma-separated values or space-separated words.
      *
      * The parser succeeds with an empty chunk if no elements can be parsed, otherwise parses elements until no more element-separator
      * pairs are found.
      *
      * @param element
      *   Parser for individual elements
      * @param separator
      *   Parser for the delimiter between elements
      * @param allowTrailing
      *   If true, allow trailing separator
      * @return
      *   Chunk of successfully parsed elements
      */
    def separatedBy[Out](using
        Frame
    )[In, S](
        element: => Out < (Parse[In] & S),
        separator: => Any < (Parse[In] & S),
        allowTrailing: Boolean = false
    )(using Tag[Parse[In]]): Chunk[Out] < (Parse[In] & S) =
        attempt(element).map:
            case Absent => Chunk.empty
            case Present(first) =>
                Loop(Chunk(first)): acc =>
                    attempt(separator).map:
                        case Absent => Loop.done(acc)
                        case Present(_) =>
                            attempt(element).map:
                                case Present(next) =>
                                    Loop.continue(acc.append(next))
                                case Absent =>
                                    if allowTrailing then Loop.done(acc)
                                    else fail("Trailing separator not allowed")

    /** Parses content between a left and right delimiter.
      *
      * @param left
      *   Parser for left delimiter
      * @param content
      *   Parser for content between delimiters
      * @param right
      *   Parser for right delimiter
      * @return
      *   The parsed content
      */
    def between[Out](using
        Frame
    )[In, S](
        left: => Any < (Parse[In] & S),
        content: => Out < (Parse[In] & S),
        right: => Any < (Parse[In] & S)
    ): Out < (Parse[In] & S) =
        for
            _      <- left
            result <- content
            _      <- right
        yield result

    /** Succeeds only at end of input
      *
      * @return
      *   Unit if at end of input
      */
    def end[In](using Tag[Parse[In]], Frame): Unit < Parse[In] =
        read(input =>
            if input.done then Result.succeed(input, ())
            else Result.fail(Chunk(ParseFailure(s"Expected: EOF, Got: ${input.remaining.head}", input.position)))
        )

    def not[In, S](parser: Any < (Parse[In] & S))(using Tag[Parse[In]], Frame): Unit < (Parse[In] & S) =
        attempt(parser).map(result =>
            if result.isDefined then fail("Not supposed to parse")
            else ()
        )

    /** Tries a parser but backtracks on failure. If the parser succeeds, the input is consumed normally. If it fails, the input position is
      * restored to where it was before the attempt. This is essential for implementing look-ahead and alternative parsing strategies where
      * failed attempts shouldn't consume input.
      *
      * @param parser
      *   Parser to attempt
      * @return
      *   Maybe containing the result if successful, Absent if parser failed
      */
    inline def attempt[Out](using
        inline frame: Frame
    )[In, S](parser: Out < (Parse[In] & S))(using inline tag: Tag[Parse[In]]): Maybe[Out] < (Parse[In] & S) =
        ArrowEffect.suspendWith(tag, Op.Attempt(parser))(x => x)

    /** Like attempt but requires the parse to succeed, failing instead of returning Maybe.empty. Use this when a parser must succeed at
      * this point - if it fails, the entire parse fails with no possibility of backtracking. However, the AST is still recoverable via
      * `recoverWith`.
      *
      * This operation is sometimes known as a "cut" in other parsing libraries, as it cuts off the possibility of backtracking.
      *
      * @param v
      *   Parser to run
      * @return
      *   Parser result, fails if parser fails with no possibility of backtracking
      */
    inline def require[Out](using
        inline frame: Frame
    )[In, S](parser: Out < (Parse[In] & S))(using inline tag: Tag[Parse[In]]): Out < (Parse[In] & S) =
        ArrowEffect.suspendWith(tag, Op.Require(parser))(x => x)

    /** Tries a parser without consuming input
      *
      * @param parser
      *   Parser to peek with
      * @return
      *   Maybe containing the result if successful
      */
    def peek[Out](using Frame)[In, S](parser: Out < (Parse[In] & S))(using Tag[Parse[In]]): Maybe[Out] < (Parse[In] & S) =
        attempt(
            for
                pos    <- position
                result <- parser
                _      <- rewind(pos)
            yield result
        )

    def andIs[Out](using
        Frame
    )[In, S](
        parser: Out < (Parse[In] & S),
        and: Any < (Parse[In] & S)
    )(using Tag[Parse[In]]): Out < (Parse[In] & S) =
        for
            pos    <- position
            result <- parser
            resPos <- position
            _      <- rewind(pos)
            _      <- and
            _      <- rewind(resPos)
        yield result

    /** Consumes whitespace characters
      *
      * @return
      *   Unit after consuming whitespace
      */
    def whitespaces(using Frame): Text < Parse[Char] =
        Parse.readWhile[Char](_.isWhitespace).map(c => Text(c.mkString))

    /** Parses an integer
      *
      * @return
      *   Parsed integer value
      */
    def int(using Frame): Int < Parse[Char] =
        read: in =>
            val num = in.remaining.takeWhile(c => c.isDigit || c == '-')
            Maybe
                .fromOption(num.mkString.toIntOption)
                .toResult(Result.fail(Chunk(ParseFailure("Invalid int", in.position))))
                .map(res => (in.advance(num.length), res))

    /** Parses a decimal number
      *
      * @return
      *   Parsed double value
      */
    def decimal(using Frame): Double < Parse[Char] =
        read: in =>
            val num = in.remaining.takeWhile(c => c.isDigit || c == '.' || c == '-')
            Maybe
                .fromOption(num.mkString.toDoubleOption)
                .toResult(Result.fail(Chunk(ParseFailure("Invalid decimal", in.position))))
                .map(res => (in.advance(num.length), res))

    /** Parses a boolean ("true" or "false")
      *
      * @return
      *   Parsed boolean value
      */
    def boolean(using Frame): Boolean < Parse[Char] =
        read(in =>
            if in.remaining.startsWith("true") then Result.succeed((in.advance(4), true))
            else if in.remaining.startsWith("false") then Result.succeed((in.advance(5), false))
            else Result.fail(Chunk(ParseFailure("Invalid boolean", in.position)))
        )

    /** Parses an identifier (letter/underscore followed by letters/digits/underscores)
      *
      * @return
      *   Parsed identifier text
      */
    def identifier(using Frame): Text < Parse[Char] =
        Parse.read: in =>
            val remaining = in.remaining
            remaining.headMaybe.filter(c => c.isLetter || c == '_').map(_ =>
                val text = remaining.takeWhile(c => c.isLetterOrDigit || c == '_')
                (in.advance(text.length), Text(text.mkString))
            ).toResult(Result.fail(Chunk(ParseFailure("Invalid identifier", in.position))))

    /** Matches text using regex pattern
      *
      * @param pattern
      *   Regex pattern
      * @return
      *   Matched text
      */
    def regex(pattern: Regex)(using Frame): Text < Parse[Char] =
        Parse.read(in =>
            Maybe.fromOption(pattern.findPrefixOf(in.remaining.mkString).map(m => (in.advance(m.length), Text(m))))
                .toResult(Result.fail(Chunk(ParseFailure("Regex didn't match", in.position))))
        )

    /** Matches text using regex pattern
      *
      * @param pattern
      *   Regex pattern string
      * @return
      *   Matched text
      */
    def regex(pattern: String)(using Frame): Text < Parse[Char] =
        regex(pattern.r)

    def entireInput[Out](using Frame)[In, S](parser: Out < (Parse[In] & S))(using Tag[Parse[In]]): Out < (Parse[In] & S) =
        for
            result   <- parser
            maybeEnd <- attempt(end)
            _ <-
                if maybeEnd.isDefined then Kyo.lift(())
                else fail("Incomplete parse - remaining input not consumed")
        yield result

    private[kyo] def runWith[In, Out, S, Out2, S2](state: ParseState[In])(parser: Out < (Parse[In] & S))(f: Out => Out2 < S2)(using
        inTag: Tag[In],
        tag: Tag[Parse[In]],
        frame: Frame
    ): (ParseState[In], ParseResult[Out2]) < (S & S2) =
        extension [X, S1](v: X < (S1 & Parse[In]))
            def castS: X < Parse[In] = v.asInstanceOf
        ArrowEffect.handleLoop[
            [in] =>> Parse.Op[In, in],
            Id,
            Parse[In],
            Out,
            (ParseState[In], ParseResult[Out2]),
            S,
            S2,
            ParseState[In]
        ](tag, state, parser)(
            [C] =>
                (input, state, cont) =>
                    input match
                        case Op.ModifyState(modify) =>
                            val (newState, optOut) = modify(state.copy(input = state.input.advanceWhile(state.isDiscarded)))
                            optOut match
                                case Absent => Loop.done((newState, ParseResult.failure(newState.failures)))
                                case Present(out) =>
                                    Loop.continue(newState.copy(input = newState.input.advanceWhile(newState.isDiscarded)), cont(out))
                            end match

                        case Op.Attempt(parser: (Out < Parse[In]) @unchecked) =>
                            runState(state)(parser)
                                .map((parseState, result) =>
                                    result.out match
                                        case None =>
                                            if result.fatal then
                                                Loop.done((parseState, ParseResult.failure(result.errors, true)))
                                            else
                                                Loop.continue(state, cont(Kyo.lift(Absent)))
                                            end if
                                        case Some(out) =>
                                            Loop.continue(parseState, cont(Kyo.lift(Present(out))))
                                    end match
                                )

                        case Op.Require(parser: (Out < Parse[In]) @unchecked) =>
                            runState(state)(parser).map((parseState, result) =>
                                result.out match
                                    case None      => Loop.done((parseState, ParseResult.failure(parseState.failures, fatal = true)))
                                    case Some(out) => Loop.continue(parseState, cont(Kyo.lift(out)))
                            )

                        case Op.RecoverWith(parser: (Out < Parse[In]) @unchecked, recoverStrategy) =>
                            runState(state)(parser)
                                .map((parseState, result) =>
                                    result.out match
                                        case None =>
                                            runState(state.copy(failures = Chunk.empty))(recoverStrategy(parser))
                                                .map((recoverState, recoverResult) =>
                                                    recoverResult.out match
                                                        case None => Loop.done((parseState, ParseResult.failure(parseState.failures)))
                                                        case Some(recouverOut) => Loop.continue(
                                                                recoverState.copy(failures = recoverState.failures ++ parseState.failures),
                                                                cont(Kyo.lift(recouverOut))
                                                            )
                                                )
                                        case Some(out) => Loop.continue(parseState, cont(Kyo.lift(out)))
                                )

                        case Op.Discard(parser: (Out < Parse[In]) @unchecked, isDiscarded) =>
                            val oldIsDiscarded = state.isDiscarded
                            runState(
                                state.copy(isDiscarded = token => oldIsDiscarded(token) || isDiscarded(token))
                            )(parser)
                                .map((parseState, result) =>
                                    val finalState = parseState.copy(isDiscarded = oldIsDiscarded)
                                    result.out match
                                        case None      => Loop.done((finalState, ParseResult.failure(finalState.failures)))
                                        case Some(out) => Loop.continue(finalState, cont(Kyo.lift(out)))
                                ),
            done = (s, r) => f(r).map(out => (s, ParseResult.success(s.failures, out)))
        )
    end runWith

    def runState[In, Out, S](state: ParseState[In])(parser: Out < (Parse[In] & S))(using
        Tag[In],
        Tag[Parse[In]],
        Frame
    ): (ParseState[In], ParseResult[Out]) < S =
        runWith(state)(parser)(identity)

    def runResult[In, Out, S](state: ParseState[In])(parser: Out < (Parse[In] & S))(using
        Tag[In],
        Tag[Parse[In]],
        Frame
    ): ParseResult[Out] < S =
        runState(state)(parser).map(_._2)

    def runResult[In, Out, S](input: ParseInput[In])(parser: Out < (Parse[In] & S))(using
        Tag[In],
        Tag[Parse[In]],
        Frame
    ): ParseResult[Out] < S =
        runResult(ParseState(input, Chunk.empty))(parser)

    def runResult[In, Out, S](input: Chunk[In])(parser: Out < (Parse[In] & S))(using Tag[In], Tag[Parse[In]], Frame): ParseResult[Out] < S =
        runResult(ParseInput(input, 0))(parser)

    def runResult[Out, S](input: String)(parser: Out < (Parse[Char] & S))(using Frame): ParseResult[Out] < S =
        runResult(Chunk.from(input))(parser)

    def runResult[Out, S](input: Text)(parser: Out < (Parse[Char] & S))(using Frame): ParseResult[Out] < S =
        runResult(input.toChunk)(parser)

    def runOrAbort[In, Out, S](input: Chunk[In])(parser: Out < (Parse[In] & S))(using
        Tag[In],
        Tag[Parse[In]],
        Frame
    ): Out < (Abort[ParseError] & S) =
        runResult(input)(parser).map(_.orAbort)

    def runOrAbort[Out, S](input: String)(parser: Out < (Parse[Char] & S))(using Frame): Out < (Abort[ParseError] & S) =
        runResult(input)(parser).map(_.orAbort)

    def runOrAbort[Out, S](input: Text)(parser: Out < (Parse[Char] & S))(using Frame): Out < (Abort[ParseError] & S) =
        runResult(input)(parser).map(_.orAbort)

    /** Runs a parser on a stream of text input, emitting parsed results as they become available. This streaming parser accumulates text
      * chunks and continuously attempts to parse complete results, handling partial inputs and backtracking as needed.
      *
      * @param input
      *   Stream of text chunks to parse
      * @param v
      *   Parser to run on the accumulated text
      * @tparam A
      *   Type of parsed result
      * @tparam S
      *   Effects required by input stream
      * @tparam S2
      *   Effects required by parser
      * @return
      *   Stream of successfully parsed results, which can abort with ParseFailed
      */
    def runStream[A, S, S2](input: Stream[Text, S])(v: A < (Parse[Char] & S2))(
        using
        Frame,
        Tag[Emit[Chunk[Text]]],
        Tag[Emit[Chunk[A]]]
    ): Stream[A, S & S2 & Abort[ParseError]] =
        Stream {
            input.emit.handle {
                // Maintains a running buffer of text and repeatedly attempts parsing
                Emit.runFold[Chunk[Text]](Text.empty) {
                    (acc: Text, curr: Chunk[Text]) =>
                        // Concatenate new chunks with existing accumulated text
                        val text = acc + curr.foldLeft(Text.empty)(_ + _)
                        if text.isEmpty then
                            // If no text to parse, request more input
                            text
                        else
                            runState(ParseState(ParseInput(text.toChunk, 0), Chunk.empty))(v).map((state, result) =>
                                if result.isFailure || state.input.done then
                                    // Parser failed or consumed all input - might need more text to complete
                                    // the next parse, so continue
                                    text
                                else
                                    // Successfully parsed a value with remaining text.
                                    // Emit the parsed value and continue with unconsumed text
                                    Emit.valueWith(Chunk(result.out.get))(Text(state.input.remaining.mkString))
                            )
                        end if
                }
            }.map { (text, _) => runOrAbort(text)(Parse.entireInput(repeat(v))).map(Emit.value(_)) }
        }

    // TODO Rework
    def debug[In, Out](name: String, parser: => Out < Parse[In])(using Tag[In], Tag[Parse[In]], Frame): Out < Parse[In] =
        for
            start <- position
            _ = println(s"$name:$start")
            res    <- parser
            endPos <- position
            _ = println(s"$name:$start-$endPos => $res")
        yield res

end Parse
