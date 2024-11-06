package kyo

import kyo.Ansi.*
import kyo.Emit.Ack
import kyo.kernel.*

/** The Parse effect combines three fundamental capabilities needed for parsing:
  *   - State management (Var[Parse.State]) to track input position
  *   - Choice for handling alternatives and backtracking
  *   - Error handling (Abort[ParseFailed]) for parse failures
  *
  * This combination enables building complex parsers that can handle ambiguous grammars, implement look-ahead, and provide detailed error
  * messages.
  */
opaque type Parse <: (Var[Parse.State] & Choice & Abort[ParseFailed]) = Var[Parse.State] & Choice & Abort[ParseFailed]

object Parse:

    // avoids splicing the derived tag on each use
    private given Tag[Var[State]] = Tag[Var[State]]

    /** Attempts to parse input using the provided parsing function
      *
      * @param f
      *   Function that takes remaining text and returns:
      *   - Present((remaining, value)) when parsing succeeds, containing the unconsumed text and parsed value
      *   - Absent when the parser doesn't match at the current position, allowing for backtracking
      * @return
      *   Parsed value if successful, drops the current parse branch if unsuccessful
      */
    def read[A, S](f: Text => Maybe[(Text, A)] < S)(using Frame): A < (Parse & S) =
        Var.use[State] { state =>
            f(state.remaining).map {
                case Present((remaining, result)) =>
                    val consumed = state.remaining.length - remaining.length
                    Var.set(state.advance(consumed)).as(result)
                case Absent =>
                    Choice.drop
            }
        }

    /** Drops the current parse branch
      *
      * @return
      *   Nothing, as this always fails the current branch
      */
    def drop(using Frame): Nothing < Parse =
        Choice.drop

    /** Drops the current parse branch if condition is true
      *
      * @param condition
      *   When true, drops the current parse branch
      */
    def dropIf(condition: Boolean)(using Frame): Unit < Parse =
        Choice.dropIf(condition)

    /** Terminally fail parsing with a message
      *
      * @param message
      *   Error message for the failure
      */
    def fail(message: String)(using frame: Frame): Nothing < Parse =
        Var.use[State](s => fail(Seq(s), message))

    private def fail(states: Seq[State], message: String)(using frame: Frame): Nothing < Abort[ParseFailed] =
        Abort.fail(ParseFailed(frame, states, message))

    /** Tries each parser in sequence and ensures exactly one succeeds. Unlike firstOf, this will evaluate all parsers, but will fail if
      * zero or multiple parsers succeed. This is useful for grammars where ambiguous parses should be considered an error.
      *
      * @param seq
      *   Sequence of parsers to try
      * @return
      *   Result from the single successful parser, fails if zero or multiple parsers succeed
      */
    def anyOf[A, S](seq: (A < (Parse & S))*)(using Frame): A < (Parse & S) =
        Choice.eval(seq)(identity)

    /** Tries parsers in sequence until the first success, backtracking between attempts.
      *
      * Unlike anyOf, this stops at the first successful parse and won't detect ambiguities. This makes it suitable for ordered alternatives
      * where earlier parsers take precedence over later ones. The parser backtracks (restores input position) after each failed attempt. If
      * no parsers succeed, the parse branch is dropped.
      *
      * @param seq
      *   Sequence of parsers to try in order
      * @return
      *   Result from first successful parser, drops the parse branch if none succeed
      */
    def firstOf[A: Flat, S](seq: (A < (Parse & S))*)(using Frame): A < (Parse & S) =
        Loop(seq) {
            case Seq() => Choice.drop
            case head +: tail =>
                attempt(head).map {
                    case Present(value) => Loop.done(value)
                    case Absent         => Loop.continue(tail)
                }
        }

    /** Parses a sequence of parsers in order and collects their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @param parsers
      *   Sequence of parsers to execute sequentially
      * @return
      *   Chunk containing all parsed results in order. The parse branch is dropped if any parser fails
      */
    def inOrder[A: Flat, S](parsers: Seq[A < (Parse & S)])(using Frame): Chunk[A] < (Parse & S) =
        parsers.size match
            case 0 => Chunk.empty
            case 1 => parsers(0).map(Chunk(_))
            case _ =>
                Kyo.foldLeft(parsers)(Chunk.empty[A]) {
                    case (acc, parser) =>
                        attempt(parser).map {
                            case Present(result) => acc.append(result)
                            case Absent          => Parse.drop
                        }
                }

    /** Parses two parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @param p1
      *   First parser to execute
      * @param p2
      *   Second parser to execute
      * @return
      *   Tuple containing both parsed results in order. The parse branch is dropped if either parser fails
      */
    def inOrder[A, B, S](p1: A < (Parse & S), p2: B < (Parse & S))(using Frame): (A, B) < (Parse & S) =
        inOrder(Seq(p1, p2))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A], s(1).asInstanceOf[B])
        }

    /** Parses three parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @param p1
      *   First parser to execute
      * @param p2
      *   Second parser to execute
      * @param p3
      *   Third parser to execute
      * @return
      *   Tuple containing all three parsed results in order. The parse branch is dropped if any parser fails
      */
    def inOrder[A, B, C, S](p1: A < (Parse & S), p2: B < (Parse & S), p3: C < (Parse & S))(using Frame): (A, B, C) < (Parse & S) =
        inOrder(Seq(p1, p2, p3))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A], s(1).asInstanceOf[B], s(2).asInstanceOf[C])
        }

    /** Parses four parsers in sequence and combines their results. This operation is commonly known as "sequence" in other parsing
      * libraries.
      *
      * @param p1
      *   First parser to execute
      * @param p2
      *   Second parser to execute
      * @param p3
      *   Third parser to execute
      * @param p4
      *   Fourth parser to execute
      * @return
      *   Tuple containing all four parsed results in order. The parse branch is dropped if any parser fails
      */
    def inOrder[A, B, C, D, S](p1: A < (Parse & S), p2: B < (Parse & S), p3: C < (Parse & S), p4: D < (Parse & S))(using
        Frame
    ): (A, B, C, D) < (Parse & S) =
        inOrder(Seq(p1, p2, p3, p4))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A], s(1).asInstanceOf[B], s(2).asInstanceOf[C], s(3).asInstanceOf[D])
        }

    /** Selects between multiple successful parses using a selection function. This is useful for implementing custom disambiguation
      * strategies between multiple valid parses.
      *
      * @param seq
      *   Sequence of parsers to try - all parsers will be evaluated
      * @param f
      *   Selection function that receives pairs of successful parses (with their states) and decides which one to keep. Returns Maybe.empty
      *   to skip both.
      * @return
      *   The parse result selected by the selection function
      */
    def select[A: Flat, S, S2](seq: (A < (Parse & S))*)(f: ((State, A), (State, A)) => Maybe[(State, A)] < S2)(
        using Frame
    ): A < (Parse & S & S2) =
        Loop(seq, Maybe.empty[(State, A)]) { (seq, current) =>
            seq match
                case Seq() =>
                    current match
                        case Absent => Choice.drop
                        case Present((state, a)) =>
                            Var.set(state).as(Loop.done(a))
                case head +: tail =>
                    peek(head.map(a => Var.use[State]((_, a)))).map {
                        case Absent =>
                            Loop.continue(tail, current)
                        case next @ Present(nextState) =>
                            current match
                                case Absent =>
                                    Loop.continue(tail, next)
                                case Present(currentState) =>
                                    f(currentState, nextState).map(Loop.continue(tail, _))
                    }
        }
    end select

    /** Skips input until parser succeeds
      *
      * @param v
      *   Parser to try at each position
      * @return
      *   Result when parser succeeds
      */
    def skipUntil[A: Flat, S](v: A < (Parse & S))(using Frame): A < (Parse & S) =
        attempt(v).map {
            case Absent =>
                Var.use[State] { state =>
                    if state.done then Parse.drop
                    else Var.set(state.advance(1)).as(skipUntil(v))
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
    def attempt[A: Flat, S](v: A < (Parse & S))(using Frame): Maybe[A] < (Parse & S) =
        Var.use[State] { start =>
            Choice.run(v).map { r =>
                result(r).map {
                    case Absent =>
                        Var.set(start).as(Maybe.empty)
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
    def require[A: Flat, S](v: A < (Parse & S))(using Frame): A < (Parse & S) =
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
    def peek[A: Flat, S](v: A < (Parse & S))(using Frame): Maybe[A] < (Parse & S) =
        Var.use[State] { start =>
            Choice.run(v).map { r =>
                Var.set(start).as(result(r))
            }
        }

    /** Repeats a parser until it fails
      *
      * @param p
      *   Parser to repeat
      * @return
      *   Chunk of all successful results
      */
    def repeat[A: Flat, S](p: A < (Parse & S))(using Frame): Chunk[A] < (Parse & S) =
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
    def repeat[A: Flat, S](n: Int)(p: A < (Parse & S))(using Frame): Chunk[A] < (Parse & S) =
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
    def separatedBy[A: Flat, S](
        element: A < (Parse & S),
        separator: Unit < (Parse & S),
        allowTrailing: Boolean = false
    )(using Frame): Chunk[A] < (Parse & S) =
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
    def between[A: Flat, S](
        left: Unit < (Parse & S),
        content: A < (Parse & S),
        right: Unit < (Parse & S)
    )(using Frame): A < (Parse & S) =
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
    def run[A: Flat, S](input: Text)(v: A < (Parse & S))(using Frame): A < (S & Abort[ParseFailed]) =
        Choice.run(Var.runTuple(State(input, 0))(v)).map {
            case Seq() =>
                Parse.fail(Seq(State(input, 0)), "No valid parse results found")
            case Seq((state, value)) =>
                if state.done then value
                else Parse.fail(Seq(state), "Incomplete parse - remaining input not consumed")
            case seq =>
                Parse.fail(seq.map(_._1), "Ambiguous parse - multiple results found")
        }

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
    def run[A: Flat, S, S2](input: Stream[Text, S])(v: A < (Parse & S2))(
        using
        Frame,
        Tag[Emit[Chunk[Text]]],
        Tag[Emit[Chunk[A]]]
    ): Stream[A, S & S2 & Abort[ParseFailed]] =
        Stream {
            input.emit.pipe {
                // Maintains a running buffer of text and repeatedly attempts parsing
                Emit.runFold[Chunk[Text]](Text.empty) {
                    (acc: Text, curr: Chunk[Text]) =>
                        // Concatenate new chunks with existing accumulated text
                        val text = acc + curr.foldLeft(Text.empty)(_ + _)
                        if text.isEmpty then
                            // If no text to parse, request more input
                            (text, Ack.Continue())
                        else
                            Choice.run(Var.runTuple(State(text, 0))(v)).map {
                                case Seq() =>
                                    // No valid parse found yet - keep current text and continue
                                    // collecting more input as the parse might succeed with additional text
                                    (text, Ack.Continue())
                                case Seq((state, value)) =>
                                    if state.done then
                                        // Parser consumed all input - might need more text to complete
                                        // the next parse, so continue
                                        (text, Ack.Continue())
                                    else
                                        // Successfully parsed a value with remaining text.
                                        // Emit the parsed value and continue with unconsumed text
                                        Emit.andMap(Chunk(value)) { ack =>
                                            (state.remaining, ack)
                                        }
                                case seq =>
                                    Parse.fail(seq.map(_._1), "Ambiguous parse - multiple results found")
                            }
                        end if
                }
            }.map { (text, ack) =>
                // Handle any remaining text after input stream ends
                ack match
                    case Ack.Stop => Ack.Stop
                    case _        =>
                        // Try to parse any complete results from remaining text
                        run(text)(repeat(v)).map(Emit(_))
            }
        }

    private def result[A](seq: Seq[A])(using Frame): Maybe[A] < Parse =
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
    case class State(input: Text, position: Int):

        /** Returns the remaining unparsed input text */
        def remaining: Text =
            input.drop(position)

        /** Advances the position by n characters, not exceeding input length
          *
          * @param n
          *   Number of characters to advance
          * @return
          *   New state with updated position
          */
        def advance(n: Int): State =
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

    /** Selects shortest matching parse from alternatives
      *
      * @param seq
      *   Sequence of parsers to try
      * @return
      *   Result that consumed least input
      */
    def shortest[A: Flat, S](seq: (A < (Parse & S))*)(using Frame): A < (Parse & S) =
        select(seq*) {
            case (curr @ (state1, _), next @ (state2, _)) =>
                if state2.position < state1.position then Maybe(next)
                else Maybe(curr)
        }

    /** Selects longest matching parse from alternatives
      *
      * @param seq
      *   Sequence of parsers to try
      * @return
      *   Result that consumed most input
      */
    def longest[A: Flat, S](seq: (A < (Parse & S))*)(using Frame): A < (Parse & S) =
        select(seq*) {
            case (curr @ (state1, _), next @ (state2, _)) =>
                if state2.position > state1.position then Maybe(next)
                else Maybe(curr)
        }

    /** Consumes whitespace characters
      *
      * @return
      *   Unit after consuming whitespace
      */
    def whitespaces(using Frame): Unit < Parse =
        Parse.read(s => Maybe((s.dropWhile(_.isWhitespace), ())))

    /** Parses an integer
      *
      * @return
      *   Parsed integer value
      */
    def int(using Frame): Int < Parse =
        Parse.read { s =>
            val (num, rest) = s.span(c => c.isDigit || c == '-')
            Maybe.fromOption(num.show.toIntOption).map((rest, _))
        }

    /** Parses a decimal number
      *
      * @return
      *   Parsed double value
      */
    def decimal(using Frame): Double < Parse =
        Parse.read { s =>
            val (num, rest) = s.span(c => c.isDigit || c == '.' || c == '-')
            Maybe.fromOption(num.show.toDoubleOption).map((rest, _))
        }

    /** Parses a boolean ("true" or "false")
      *
      * @return
      *   Parsed boolean value
      */
    def boolean(using Frame): Boolean < Parse =
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
    def char(c: Char)(using Frame): Unit < Parse =
        Parse.read { s =>
            s.head.filter(_ == c).map(_ => (s.drop(1), ()))
        }

    /** Matches exact text
      *
      * @param str
      *   Text to match
      * @return
      *   Unit if text matches
      */
    def literal(str: Text)(using Frame): Unit < Parse =
        Parse.read(s => if s.startsWith(str) then Maybe((s.drop(str.length), ())) else Maybe.empty)

    /** Consumes any single character
      *
      * @return
      *   The character consumed
      */
    def anyChar(using Frame): Char < Parse =
        Parse.read(s => s.head.map(c => (s.drop(1), c)))

    /** Consumes a character matching predicate
      *
      * @param p
      *   Predicate function for character
      * @return
      *   Matching character
      */
    def satisfy(p: Char => Boolean)(using Frame): Char < Parse =
        Parse.read(s => s.head.filter(p).map(c => (s.drop(1), c)))

    /** Matches text using regex pattern
      *
      * @param pattern
      *   Regex pattern string
      * @return
      *   Matched text
      */
    def regex(pattern: String)(using Frame): Text < Parse =
        Parse.read { s =>
            val regex = pattern.r
            Maybe.fromOption(regex.findPrefixOf(s.show).map(m => (s.drop(m.length), Text(m))))
        }

    /** Parses an identifier (letter/underscore followed by letters/digits/underscores)
      *
      * @return
      *   Parsed identifier text
      */
    def identifier(using Frame): Text < Parse =
        Parse.read { s =>
            s.head.filter(c => c.isLetter || c == '_').map { _ =>
                val (id, rest) = s.span(c => c.isLetterOrDigit || c == '_')
                (rest, id)
            }
        }

    /** Matches any character in string
      *
      * @param chars
      *   String of valid characters
      * @return
      *   Matched character
      */
    def oneOf(chars: String)(using Frame): Char < Parse =
        satisfy(c => chars.contains(c))

    /** Matches any character not in string
      *
      * @param chars
      *   String of invalid characters
      * @return
      *   Matched character
      */
    def noneOf(chars: String)(using Frame): Char < Parse =
        satisfy(c => !chars.contains(c))

    /** Succeeds only at end of input
      *
      * @return
      *   Unit if at end of input
      */
    def end(using Frame): Unit < Parse =
        Parse.read(s => if s.isEmpty then Maybe((s, ())) else Maybe.empty)

end Parse

case class ParseFailed(frame: Frame, states: Seq[Parse.State], message: String) extends Exception with Serializable:

    override def getMessage() = frame.render("Parse failed! ".red.bold + message, states)
end ParseFailed
