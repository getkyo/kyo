package kyo

import kyo.Ansi.*
import kyo.kernel.*

/** Parser combinator effect for compositional text parsing.
  *
  * The Parse effect combines three fundamental capabilities needed for powerful parsing: state management through `Var[Parse.State]` to
  * track input position, alternative exploration via `Choice` for backtracking and ambiguity handling, and structured error reporting with
  * `Abort[ParseFailed]` for failures.
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
  *   [[kyo.Parse.firstOf]], [[kyo.Parse.anyOf]], [[kyo.Parse.inOrder]] for combining parsers
  * @see
  *   [[kyo.Parse.attempt]], [[kyo.Parse.peek]] for parsers with look-ahead and backtracking
  * @see
  *   [[kyo.Parse.run]] for executing parsers against input text
  * @see
  *   [[kyo.Var]], [[kyo.Choice]], [[kyo.Abort]] for the underlying effects that Parse composes
  */
opaque type Parse[A] <: (Var[Parse.State[A]] & Choice & Abort[ParseFailed]) =
    Var[Parse.State[A]] & Choice & Abort[ParseFailed]

object Parse:

    type StateTag[A] = Tag[Var[State[A]]]

    private val localReadAspect: Aspect[Const[Chunk[Any]], [C] =>> Maybe[(Chunk[?], C)], Parse[Any]] =
        Aspect.init(using Frame.internal)

    /** Aspect that modifies how text is read and parsed. This is the core parsing aspect that other parsing operations build upon. It takes
      * the current text input and a parsing function, allowing for preprocessing of input or postprocessing of results.
      *
      * @return
      *   An Aspect that transforms from Const[Text] to Maybe[(Text, C)]
      */
    def readAspect[A]: Aspect[Const[Chunk[A]], [C] =>> Maybe[(Chunk[A], C)], Parse[A]] =
        localReadAspect.asInstanceOf[Aspect[Const[Chunk[A]], [C] =>> Maybe[(Chunk[A], C)], Parse[A]]]

    /** Attempts to parse input using the provided parsing function
      *
      * @param f
      *   Function that takes remaining text and returns:
      *   - Present((remaining, value)) when parsing succeeds, containing the unconsumed text and parsed value
      *   - Absent when the parser doesn't match at the current position, allowing for backtracking
      * @return
      *   Parsed value if successful, drops the current parse branch if unsuccessful
      */
    def read[A, In](f: Chunk[In] => Maybe[(Chunk[In], A)])(using Frame, StateTag[In]): A < Parse[In] =
        Var.use[State[In]] { state =>
            readAspect(state.remaining)(f).map {
                case Present((remaining, result)) =>
                    val consumed = state.remaining.length - remaining.length
                    Var.set(state.advance(consumed)).andThen(result)
                case Absent =>
                    Choice.drop
            }
        }

    /** Drops the current parse branch
      *
      * @return
      *   Nothing, as this always fails the current branch
      */
    def drop[In](using Frame): Nothing < Parse[In] =
        Choice.drop

    /** Drops the current parse branch if condition is true
      *
      * @param condition
      *   When true, drops the current parse branch
      */
    def dropIf[In](condition: Boolean)(using Frame): Unit < Parse[In] =
        Choice.dropIf(condition)

    /** Terminally fail parsing with a message
      *
      * @param message
      *   Error message for the failure
      */
    def fail[In](message: String)(using frame: Frame, tag: StateTag[In]): Nothing < Parse[In] =
        Var.use[State[In]](s => fail(Seq(s), message))

    private def fail[In](states: Seq[State[In]], message: String)(using frame: Frame): Nothing < Abort[ParseFailed] =
        Abort.fail(ParseFailed(states, message))

    /** Reads and consumes characters from the input as long as they satisfy the given predicate.
      *
      * @param f
      *   Predicate function that tests each character
      * @return
      *   The matched text containing all consecutive characters that satisfied the predicate
      */
    def readWhile[A](f: A => Boolean)(using Frame, StateTag[A]): Chunk[A] < Parse[A] =
        Parse.read { s =>
            val (matched, rest) = s.span(f(_))
            Maybe((rest, matched))
        }

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
    def spaced[A, S](v: A < (Parse[Char] & S), isWhitespace: Char => Boolean = _.isWhitespace)(using Frame): A < (Parse[Char] & S) =
        readAspect[Char].let([C] =>
            (input, cont) =>
                cont(input.dropWhile(isWhitespace(_))).map {
                    case Present((remaining, value)) =>
                        Present((remaining.dropWhile(isWhitespace(_)), value))
                    case Absent => Absent
            })(v)

    /** Tries each parser in sequence and ensures exactly one succeeds. Unlike firstOf, this will evaluate all parsers, but will fail if
      * zero or multiple parsers succeed. This is useful for grammars where ambiguous parses should be considered an error.
      *
      * @param seq
      *   Sequence of parsers to try
      * @return
      *   Result from the single successful parser, fails if zero or multiple parsers succeed
      */
    def anyOf[A, In, S](seq: (A < (Parse[In] & S))*)(using Frame): A < (Parse[In] & S) =
        Choice.evalWith(seq)(identity)

    private def firstOf[A, In, S](seq: Seq[() => A < (Parse[In] & S)])(using Frame, StateTag[In]): A < (Parse[In] & S) =
        Loop(seq) {
            case Seq() => Choice.drop
            case head +: tail =>
                attempt(head()).map {
                    case Present(value) => Loop.done(value)
                    case Absent         => Loop.continue(tail)
                }
        }

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyOf, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[A, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => A < (Parse[In] & S)
    )(using Frame, StateTag[In]): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyOf, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
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
        Frame,
        StateTag[In]
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyOf, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
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
        Frame,
        StateTag[In]
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyOf, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
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
        Frame,
        StateTag[In]
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4, () => parser5))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyOf, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
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
        Frame,
        StateTag[In]
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4, () => parser5, () => parser6))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyOf, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
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
        Frame,
        StateTag[In]
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4, () => parser5, () => parser6, () => parser7))

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyOf, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
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
        Frame,
        StateTag[In]
    ): A < (Parse[In] & S) =
        firstOf(Seq(() => parser1, () => parser2, () => parser3, () => parser4, () => parser5, () => parser6, () => parser7, () => parser8))

    private def inOrder[A, In, S](parsers: Seq[() => A < (Parse[In] & S)])(using Frame, StateTag[In]): Chunk[A] < (Parse[In] & S) =
        parsers.size match
            case 0 => Chunk.empty
            case 1 => parsers(0)().map(Chunk(_))
            case _ =>
                Kyo.foldLeft(parsers)(Chunk.empty[A]) {
                    case (acc, parser) =>
                        attempt(parser()).map {
                            case Present(result) => acc.append(result)
                            case Absent          => Parse.drop
                        }
                }

    /** Parses two parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @return
      *   Tuple containing both parsed results in order. The parse branch is dropped if either parser fails
      */
    def inOrder[A, B, In, S](
        parser1: => A < (Parse[In] & S),
        parser2: => B < (Parse[In] & S)
    )(using Frame, StateTag[In]): (A, B) < (Parse[In] & S) =
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
        Frame,
        StateTag[In]
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
        Frame,
        StateTag[In]
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
        Frame,
        StateTag[In]
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
        Frame,
        StateTag[In]
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
        Frame,
        StateTag[In]
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
        Frame,
        StateTag[In]
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

    /** Skips input until parser succeeds
      *
      * @param v
      *   Parser to try at each position
      * @return
      *   Result when parser succeeds
      */
    def skipUntil[A, In, S](v: A < (Parse[In] & S))(using Frame, StateTag[In]): A < (Parse[In] & S) =
        attempt(v).map {
            case Absent =>
                Var.use[State[In]] { state =>
                    if state.done then Parse.drop
                    else Var.set(state.advance(1)).andThen(skipUntil(v))
                }
            case Present(v) => v
        }

    /** Tries a parser but backtracks on failure. If the parser succeeds, the input is consumed normally. If it fails, the input position is
      * restored to where it was before the attempt. This is essential for implementing look-ahead and alternative parsing strategies where
      * failed attempts shouldn't consume input.
      *
      * @param v
      *   Parser to attempt
      * @return
      *   Maybe containing the result if successful, Absent if parser failed
      */
    def attempt[A, In, S](v: A < (Parse[In] & S))(using Frame, StateTag[In]): Maybe[A] < (Parse[In] & S) =
        Var.use[State[In]] { start =>
            Choice.run(v).map { r =>
                result(r).map {
                    case Absent =>
                        Var.set(start).andThen(Maybe.empty)
                    case result =>
                        result
                }
            }
        }

    /** Like attempt but requires the parse to succeed, failing instead of returning Maybe.empty. Use this when a parser must succeed at
      * this point - if it fails, the entire parse fails with no possibility of backtracking.
      *
      * This operation is sometimes known as a "cut" in other parsing libraries, as it cuts off the possibility of backtracking.
      *
      * @param v
      *   Parser to run
      * @return
      *   Parser result, fails if parser fails with no possibility of backtracking
      */
    def require[A, In, S](v: A < (Parse[In] & S))(using Frame, StateTag[In]): A < (Parse[In] & S) =
        attempt(v).map {
            case Present(a) => a
            case Absent => Parse.fail(
                    "Parse.require failed - parser did not match at this position. A required parser " +
                        "fails the entire parse instead of allowing backtracking."
                )
        }

    /** Tries a parser without consuming input
      *
      * @param v
      *   Parser to peek with
      * @return
      *   Maybe containing the result if successful
      */
    def peek[A, In, S](v: A < (Parse[In] & S))(using Frame, StateTag[In]): Maybe[A] < (Parse[In] & S) =
        Var.use[State[In]] { start =>
            Choice.run(v).map { r =>
                Var.set(start).andThen(result(r))
            }
        }

    /** Repeats a parser until it fails
      *
      * @param p
      *   Parser to repeat
      * @return
      *   Chunk of all successful results
      */
    def repeat[A, In, S](p: A < (Parse[In] & S))(using Frame, StateTag[In]): Chunk[A] < (Parse[In] & S) =
        Loop(Chunk.empty[A]) { acc =>
            attempt(p).map {
                case Present(a) => Loop.continue(acc.append(a))
                case Absent     => Loop.done(acc)
            }
        }

    /** Repeats a parser exactly n times
      *
      * @param n
      *   Number of repetitions required
      * @param p
      *   Parser to repeat
      * @return
      *   Chunk of n results, fails if can't get n results
      */
    def repeat[A, In, S](n: Int)(p: A < (Parse[In] & S))(using Frame, StateTag[In]): Chunk[A] < (Parse[In] & S) =
        Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
            if idx == n then Loop.done(acc)
            else
                attempt(p).map {
                    case Present(a) => Loop.continue(acc.append(a))
                    case Absent     => Parse.drop
                }
        }

    /** Parses a sequence of elements separated by a delimiter. For example, parsing comma-separated values or space-separated words.
      *
      * The parser succeeds with an empty chunk if no elements can be parsed, otherwise parses elements until no more element-separator
      * pairs are found.
      *
      * @param element
      *   Parser for individual elements
      * @param separator
      *   Parser for the delimiter between elements
      * @return
      *   Chunk of successfully parsed elements
      */
    def separatedBy[A, B, In, S](
        element: => A < (Parse[In] & S),
        separator: => B < (Parse[In] & S),
        allowTrailing: Boolean = false
    )(using Frame, StateTag[In]): Chunk[A] < (Parse[In] & S) =
        attempt(element).map {
            case Absent => Chunk.empty
            case Present(first) =>
                Loop(Chunk(first)) { acc =>
                    attempt(separator).map {
                        case Absent => Loop.done(acc)
                        case Present(_) =>
                            attempt(element).map {
                                case Present(next) =>
                                    Loop.continue(acc.append(next))
                                case Absent =>
                                    if allowTrailing then Loop.done(acc)
                                    else Parse.drop
                            }
                    }
                }
        }

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
    def between[A, In, S](
        left: => Any < (Parse[In] & S),
        content: => A < (Parse[In] & S),
        right: => Any < (Parse[In] & S)
    )(using Frame): A < (Parse[In] & S) =
        for
            _      <- left
            result <- content
            _      <- right
        yield result

    // **************
    // ** Handlers **
    // **************

    /** Runs a parser on input text
      *
      * @param input
      *   Text to parse
      * @param v
      *   Parser to run
      * @return
      *   Parsed result if successful
      */
    def run[A, In, S](input: Chunk[In])(v: A < (Parse[In] & S))(using Frame, StateTag[In]): A < (S & Abort[ParseFailed]) =
        Choice.run(Var.runTuple(State(input, 0))(v)).map {
            case Seq() =>
                Parse.fail(Seq(State(input, 0)), "No valid parse results found")
            case Seq((state, value)) =>
                if state.done then value
                else Parse.fail(Seq(state), "Incomplete parse - remaining input not consumed")
            case seq =>
                Parse.fail(seq.map(_._1), "Ambiguous parse - multiple results found")
        }

    /** Runs a parser on input text
      *
      * @param input
      *   Text to parse
      * @param v
      *   Parser to run
      * @return
      *   Parsed result if successful
      */
    def run[A, S](input: Text)(v: A < (Parse[Char] & S))(using Frame): A < (S & Abort[ParseFailed]) =
        run(Chunk.from(input.toString))(v)

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
    def run[A, S, S2](input: Stream[Text, S])(v: A < (Parse[Char] & S2))(
        using
        Frame,
        Tag[Emit[Chunk[Text]]],
        Tag[Emit[Chunk[A]]]
    ): Stream[A, S & S2 & Abort[ParseFailed]] =
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
                            Choice.run(Var.runTuple(State(Chunk.from(text.toString), 0))(v)).map {
                                case Seq() =>
                                    // No valid parse found yet - keep current text and continue
                                    // collecting more input as the parse might succeed with additional text
                                    text
                                case Seq((state, value)) =>
                                    if state.done then
                                        // Parser consumed all input - might need more text to complete
                                        // the next parse, so continue
                                        text
                                    else
                                        // Successfully parsed a value with remaining text.
                                        // Emit the parsed value and continue with unconsumed text
                                        Emit.valueWith(Chunk(value))(Text(state.remaining.mkString))
                                case seq =>
                                    Parse.fail(seq.map(_._1), "Ambiguous parse - multiple results found")
                            }
                        end if
                }
            }.map { (text, _) => run(text)(repeat(v)).map(Emit.value(_)) }
        }

    private def result[A, In](seq: Seq[A])(using Frame, StateTag[In]): Maybe[A] < Parse[In] =
        seq match
            case Seq()      => Maybe.empty
            case Seq(value) => Maybe(value)
            case _          => Parse.fail("Ambiguous parse result - multiple values found")

    /** Represents the current state of parsing
      *
      * @param input
      *   The complete input text being parsed
      * @param position
      *   Current position in the input text
      */
    case class State[In](input: Chunk[In], position: Int):

        /** Returns the remaining unparsed input text */
        def remaining: Chunk[In] =
            input.drop(position)

        /** Advances the position by n characters, not exceeding input length
          *
          * @param n
          *   Number of characters to advance
          * @return
          *   New state with updated position
          */
        def advance(n: Int): State[In] =
            copy(position = Math.min(input.length, position + n))

        /** Checks if all input has been consumed
          *
          * @return
          *   true if position has reached the end of input
          */
        def done: Boolean =
            position == input.length
    end State

    // **********************
    // ** Standard parsers **
    // **********************

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
        Parse.read { s =>
            val (num, rest) = s.span(c => c.isDigit || c == '-')
            Maybe.fromOption(num.mkString.toIntOption).map((rest, _))
        }

    /** Parses a decimal number
      *
      * @return
      *   Parsed double value
      */
    def decimal(using Frame): Double < Parse[Char] =
        Parse.read { s =>
            val (num, rest) = s.span(c => c.isDigit || c == '.' || c == '-')
            Maybe.fromOption(num.mkString.toDoubleOption).map((rest, _))
        }

    /** Parses a boolean ("true" or "false")
      *
      * @return
      *   Parsed boolean value
      */
    def boolean(using Frame): Boolean < Parse[Char] =
        Parse.read { s =>
            if s.startsWith("true") then Maybe((s.drop(4), true))
            else if s.startsWith("false") then Maybe((s.drop(5), false))
            else Maybe.empty
        }

    /** Matches a specific character
      *
      * @param c
      *   Character to match
      * @return
      *   Unit if character matches
      */
    def char(c: Char)(using Frame): Char < Parse[Char] =
        literal(c) // Could be changed once we specialize Text state out of Chunk state

    /** Matches exact input
      *
      * @param str
      *   Input to match
      * @return
      *   Unit if text matches
      */
    def literal[In](expected: In)(using Frame, CanEqual[In, In], StateTag[In]): In < Parse[In] =
        Parse.read { s =>
            Maybe.fromOption(s.headOption.filter(_ == expected).map(_ => (s.drop(1), expected)))
        }

    /** Matches exact text
      *
      * @param str
      *   Text to match
      * @return
      *   Unit if text matches
      */
    def literal(str: Text)(using Frame): Text < Parse[Char] =
        Parse.read { s =>
            if s.startsWith(str.toString) then
                Maybe((s.drop(str.length), str))
            else
                Maybe.empty
        }

    /** Consumes any single character
      *
      * @return
      *   The character consumed
      */
    def char(using Frame): Char < Parse[Char] =
        Parse.read(s => s.headMaybe.map(c => (s.drop(1), c)))

    /** Consumes any single input element
      *
      * @return
      *   The consumed input element
      */
    def any[In](using Frame, StateTag[In]): In < Parse[In] =
        Parse.read(s => s.headMaybe.map(c => (s.drop(1), c)))

    /** Consumes a character matching predicate
      *
      * @param p
      *   Predicate function for character
      * @return
      *   Matching character
      */
    def charIf(p: Char => Boolean)(using Frame): Char < Parse[Char] =
        Parse.read(s => s.headMaybe.filter(p).map(c => (s.drop(1), c)))

    /** Consumes a character matching predicate
      *
      * @param p
      *   Predicate function for character
      * @return
      *   Matching character
      */
    def anyIf[In](p: In => Boolean)(using Frame, StateTag[In]): In < Parse[In] =
        Parse.read(s => s.headMaybe.filter(p).map(c => (s.drop(1), c)))

    def anyMatch[A, In](pf: PartialFunction[In, A])(using Frame, StateTag[In]): A < Parse[In] =
        Parse.read(s => Maybe.fromOption(s.headOption.flatMap(pf.lift)).map(c => (s.drop(1), c)))

    /** Matches text using regex pattern
      *
      * @param pattern
      *   Regex pattern string
      * @return
      *   Matched text
      */
    def regex(pattern: String)(using Frame): Text < Parse[Char] =
        Parse.read { s =>
            val regex = pattern.r
            Maybe.fromOption(regex.findPrefixOf(s.mkString).map(m => (s.drop(m.length), Text(m))))
        }

    /** Parses an identifier (letter/underscore followed by letters/digits/underscores)
      *
      * @return
      *   Parsed identifier text
      */
    def identifier(using Frame): Text < Parse[Char] =
        Parse.read { s =>
            s.headMaybe.filter(c => c.isLetter || c == '_').map { _ =>
                val (id, rest) = s.span(c => c.isLetterOrDigit || c == '_')
                (rest, Text(id.mkString))
            }
        }

    /** Matches any character in string
      *
      * @param chars
      *   String of valid characters
      * @return
      *   Matched character
      */
    def charIn(chars: String)(using Frame): Char < Parse[Char] =
        charIf(c => chars.contains(c))

    /** Matches any character not in string
      *
      * @param chars
      *   String of invalid characters
      * @return
      *   Matched character
      */
    def charNotIn(chars: String)(using Frame): Char < Parse[Char] =
        charIf(c => !chars.contains(c))

    /** Succeeds only at end of input
      *
      * @return
      *   Unit if at end of input
      */
    def end[In](using Frame, StateTag[In]): Unit < Parse[In] =
        Parse.read(s => if s.isEmpty then Maybe((s, ())) else Maybe.empty)

end Parse

case class ParseFailed(states: Seq[Parse.State[?]], message: String)(using Frame) extends KyoException(message, Render.asText(states))
