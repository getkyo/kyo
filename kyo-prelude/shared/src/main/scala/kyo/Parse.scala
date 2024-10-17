package kyo

import kyo.kernel.*

case class AmbigousParse(seq: Seq[?]) extends Exception

opaque type Parse <: (Var[String] & Choice) = Var[String] & Choice & Abort[AmbigousParse]

object Parse:

    def read[A, S](f: String => Maybe[(String, A)] < S)(using Frame): A < (Parse & S) =
        Var.use[String] { s =>
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
        Var.use[String] { start =>
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
        Var.use[String] { start =>
            Choice.run(v).map { r =>
                Var.set(start).as(result(r))
            }
        }

    def run[A: Flat, S](input: String)(v: A < (Parse & S))(using Frame): Maybe[(String, A)] < (S & Abort[AmbigousParse]) =
        Choice.run(Var.runTuple(input)(v)).map(result)

    def whitespaces(using Frame): Unit < Parse =
        Parse.read { s =>
            val trimmed = s.dropWhile(_.isWhitespace)
            Maybe((trimmed, ()))
        }

    def int(using Frame): Int < Parse =
        Parse.read { s =>
            s.headOption match
                case Some(c) if c == '-' || c.isDigit =>
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
            def isValidDecimal(str: String) =
                str.count(_ == '.') <= 1 &&
                    str.filterNot(c => c.isDigit || c == '.' || c == '-').isEmpty &&
                    (str.length > 1 || str == "0") // Handles single-char edge cases

            s.headOption match
                case Some(c) if c == '-' || c == '.' || c.isDigit =>
                    // Capture the decimal part including optional '-' prefix, digits before/after '.'
                    val (numStr, rest) = s.span(c => c.isDigit || c == '.' || c == '-')
                    if isValidDecimal(numStr) then
                        try
                            Maybe((rest, numStr.toDouble))
                        catch
                            case _: NumberFormatException => Maybe.empty
                    else Maybe.empty
                    end if
                case _ =>
                    Maybe.empty
            end match
        }

    def string(using Frame): String < Parse =
        Parse.read { s =>
            s.headOption match
                case Some('"') =>
                    var escaped = false
                    var i       = 1
                    var builder = new StringBuilder
                    while i < s.length && (escaped || s(i) != '"') do
                        if escaped then
                            builder.append(s(i) match
                                case 'n' => '\n'
                                case 't' => '\t'
                                case 'r' => '\r'
                                case c   => c
                            )
                            escaped = false
                        else if s(i) == '\\' then
                            escaped = true
                        else
                            builder.append(s(i))
                        end if
                        i += 1
                    end while

                    if i < s.length && s(i) == '"' then
                        Maybe((s.substring(i + 1), builder.toString))
                    else
                        Maybe.empty
                    end if
                case _ => Maybe.empty
        }

    def boolean(using Frame): Boolean < Parse =
        Parse.read { s =>
            if s.startsWith("true") then
                Maybe((s.substring(4), true))
            else if s.startsWith("false") then
                Maybe((s.substring(5), false))
            else
                Maybe.empty
        }

    def char(c: Char)(using Frame): Char < Parse =
        Parse.read { s =>
            if s.startsWith(c.toString) then
                Maybe((s.substring(1), c))
            else
                Maybe.empty
        }

    def literal(str: String)(using Frame): String < Parse =
        Parse.read { s =>
            if s.startsWith(str) then
                Maybe((s.substring(str.length), str))
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
