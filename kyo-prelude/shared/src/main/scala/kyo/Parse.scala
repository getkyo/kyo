package kyo

import kyo.kernel.*

case class AmbigousParse(seq: Seq[?]) extends Exception

opaque type Parse <: (Var[Text] & Choice) = Var[Text] & Choice & Abort[AmbigousParse]

object Parse:

    // avoids splicing the derived tag on each use
    private given Tag[Var[Text]] = Tag[Var[Text]]

    def read[A, S](f: Text => Maybe[(Text, A)] < S)(using Frame): A < (Parse & S) =
        Var.use[Text] { s =>
            f(s).map {
                case Present((remaining, result)) =>
                    Var.set(remaining).as(result)
                case Absent =>
                    Choice.drop
            }
        }

    def drop(using Frame): Nothing < Parse = Choice.drop

    def dropIf(condition: Boolean)(using Frame): Unit < Parse = Choice.dropIf(condition)

    def oneOf[A, S](seq: (A < (Parse & S))*)(using Frame): A < (Parse & S) = Choice.eval(seq)(identity)

    def attempt[A: Flat, S](v: A < (Parse & S))(using Frame): Maybe[A] < (Parse & S) =
        Var.use[Text] { start =>
            Choice.run(v).map { r =>
                result(r).map {
                    case Absent =>
                        Var.set(start).as(Maybe.empty)
                    case result =>
                        result
                }
            }
        }

    def peek[A: Flat, S](v: A < (Parse & S))(using Frame): Maybe[A] < (Parse & S) =
        Var.use[Text] { start =>
            Choice.run(v).map { r =>
                Var.set(start).as(result(r))
            }
        }

    def run[A: Flat, S](input: Text)(v: A < (Parse & S))(using Frame): Maybe[(Text, A)] < (S & Abort[AmbigousParse]) =
        Choice.run(Var.runTuple(input)(v)).map(result)

    def whitespaces(using Frame): Unit < Parse =
        Parse.read { s =>
            val trimmed = s.dropWhile(_.isWhitespace)
            Maybe((trimmed, ()))
        }

    def int(using Frame): Int < Parse =
        Parse.read { s =>
            s.head match
                case Present(c) if c == '-' || c.isDigit =>
                    val (digits, rest) = s.tail.span(_.isDigit)
                    val numStr         = if c == '-' then "-" + digits else s"$c$digits"
                    try
                        Maybe((rest, numStr.toInt))
                    catch
                        case _: NumberFormatException => Maybe.empty
                    end try
                case _ => Maybe.empty
        }

    def decimal(using Frame): Double < Parse =
        Parse.read { s =>
            def isValidDecimal(str: Text) =
                str.count(_ == '.') <= 1 &&
                    str.filterNot(c => c.isDigit || c == '.' || c == '-').isEmpty &&
                    (str.length > 1 || str.is("0")) // Handles single-char edge cases

            s.head match
                case Present(c) if c == '-' || c == '.' || c.isDigit =>
                    // Capture the decimal part including optional '-' prefix, digits before/after '.'
                    val (numStr, rest) = s.span(c => c.isDigit || c == '.' || c == '-')
                    if isValidDecimal(numStr) then
                        try
                            Maybe((rest, numStr.toString.toDouble))
                        catch
                            case _: NumberFormatException => Maybe.empty
                    else Maybe.empty
                    end if
                case _ =>
                    Maybe.empty
            end match
        }

    def boolean(using Frame): Boolean < Parse =
        Parse.read { s =>
            if s.startsWith("true") then
                Maybe((s.drop(4), true))
            else if s.startsWith("false") then
                Maybe((s.drop(5), false))
            else
                Maybe.empty
        }

    def char(c: Char)(using Frame): Char < Parse =
        Parse.read { s =>
            if s.startsWith(s"$c") then
                Maybe((s.drop(1), c))
            else
                Maybe.empty
        }

    def literal(str: Text)(using Frame): Text < Parse =
        Parse.read { s =>
            if s.startsWith(str) then
                Maybe((s.drop(str.length), str))
            else
                Maybe.empty
        }

    private def result[A](seq: Seq[A])(using Frame): Maybe[A] < Abort[AmbigousParse] =
        seq.size match
            case 0 => Maybe.empty
            case 1 => Maybe(seq(0))
            case n => Abort.fail(AmbigousParse(seq))

    def repeat[A: Flat, S](p: A < (Parse & S))(using Frame): List[A] < (Parse & S) =
        def go(acc: List[A]): List[A] < (Parse & S) =
            attempt(p).map {
                case Present(a) => go(a :: acc)
                case Absent     => acc.reverse
            }
        go(Nil)
    end repeat

    def repeatUntil[A: Flat, S](p: A < (Parse & S))(until: Boolean < (Parse & S))(using Frame): List[A] < (Parse & S) =
        def go(acc: List[A]): List[A] < (Parse & S) =
            attempt(until).map {
                case Present(true) => acc.reverse
                case _ =>
                    attempt(p).map {
                        case Present(a) => go(a :: acc)
                        case Absent     => acc.reverse
                    }
            }
        go(Nil)
    end repeatUntil

end Parse
