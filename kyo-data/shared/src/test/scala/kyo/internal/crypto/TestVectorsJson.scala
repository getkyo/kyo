package kyo.internal.crypto

import scala.annotation.tailrec

/** A strict JSON reader for the vendored vector files [[TestVectors]] serves (Wycheproof, ed25519-speccheck).
  *
  * kyo-data's tests cannot use kyo-schema's codecs, which depend on kyo-data. Anything that is not JSON throws, and so does reading a
  * member or type that is not there, so a vector file the reader does not understand fails the suite reading it instead of being skipped.
  */
object TestVectorsJson:

    enum Json:
        case Obj(fields: Seq[(String, Json)])
        case Arr(values: Seq[Json])
        case Str(value: String)
        case Num(text: String)
        case Bool(value: Boolean)
        case Null

        def apply(key: String): Json = this match
            case Obj(fields) => fields.collectFirst { case (`key`, value) => value }.getOrElse(throw new NoSuchElementException(key))
            case other       => throw new IllegalStateException(s"not an object when reading '$key': $other")

        def items: Seq[Json] = this match
            case Arr(values) => values
            case other       => throw new IllegalStateException(s"not an array: $other")

        def string: String = this match
            case Str(value) => value
            case other      => throw new IllegalStateException(s"not a string: $other")

        def int: Int = this match
            case Num(text) => text.toInt
            case other     => throw new IllegalStateException(s"not a number: $other")
    end Json

    object Json:
        def parse(text: String): Json =
            val (value, end) = readValue(text, skip(text, 0))
            require(skip(text, end) == text.length, s"trailing content at offset $end")
            value
        end parse

        private def skip(text: String, at: Int): Int =
            if at < text.length && " \t\r\n".indexOf(text.charAt(at)) >= 0 then skip(text, at + 1) else at

        private def expect(text: String, at: Int, char: Char): Int =
            require(at < text.length && text.charAt(at) == char, s"expected '$char' at offset $at")
            at + 1

        private def readValue(text: String, at: Int): (Json, Int) =
            require(at < text.length, "unexpected end of input")
            text.charAt(at) match
                case '{'                                 => readObject(text, skip(text, at + 1), Vector.empty)
                case '['                                 => readArray(text, skip(text, at + 1), Vector.empty)
                case '"'                                 => readString(text, at + 1, new java.lang.StringBuilder)
                case 't' if text.startsWith("true", at)  => (Bool(true), at + 4)
                case 'f' if text.startsWith("false", at) => (Bool(false), at + 5)
                case 'n' if text.startsWith("null", at)  => (Null, at + 4)
                case c if c == '-' || c.isDigit          =>
                    val end = text.indexWhere(ch => !(ch.isDigit || "+-.eE".indexOf(ch) >= 0), at) match
                        case -1 => text.length
                        case i  => i
                    val number = text.substring(at, end)
                    require(number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"), s"malformed number '$number' at $at")
                    (Num(number), end)
                case other => throw new IllegalArgumentException(s"unexpected '$other' at offset $at")
            end match
        end readValue

        @tailrec private def readObject(text: String, at: Int, fields: Vector[(String, Json)]): (Json, Int) =
            if fields.isEmpty && at < text.length && text.charAt(at) == '}' then (Obj(fields), at + 1)
            else
                val (key, afterKey) = readValue(text, at) match
                    case (Str(k), end) => (k, end)
                    case (other, _)    => throw new IllegalArgumentException(s"object key is not a string at offset $at: $other")
                val (value, afterValue) = readValue(text, skip(text, expect(text, skip(text, afterKey), ':')))
                val next                = skip(text, afterValue)
                require(next < text.length, "unterminated object")
                text.charAt(next) match
                    case ',' => readObject(text, skip(text, next + 1), fields :+ (key -> value))
                    case '}' => (Obj(fields :+ (key -> value)), next + 1)
                    case c   => throw new IllegalArgumentException(s"unexpected '$c' in object at offset $next")
                end match

        @tailrec private def readArray(text: String, at: Int, values: Vector[Json]): (Json, Int) =
            if values.isEmpty && at < text.length && text.charAt(at) == ']' then (Arr(values), at + 1)
            else
                val (value, afterValue) = readValue(text, at)
                val next                = skip(text, afterValue)
                require(next < text.length, "unterminated array")
                text.charAt(next) match
                    case ',' => readArray(text, skip(text, next + 1), values :+ value)
                    case ']' => (Arr(values :+ value), next + 1)
                    case c   => throw new IllegalArgumentException(s"unexpected '$c' in array at offset $next")
                end match

        @tailrec private def readString(text: String, at: Int, out: java.lang.StringBuilder): (Json, Int) =
            require(at < text.length, "unterminated string")
            text.charAt(at) match
                case '"'  => (Str(out.toString), at + 1)
                case '\\' =>
                    require(at + 1 < text.length, "unterminated escape")
                    text.charAt(at + 1) match
                        case '"'  => readString(text, at + 2, out.append('"'))
                        case '\\' => readString(text, at + 2, out.append('\\'))
                        case '/'  => readString(text, at + 2, out.append('/'))
                        case 'b'  => readString(text, at + 2, out.append('\b'))
                        case 'f'  => readString(text, at + 2, out.append('\f'))
                        case 'n'  => readString(text, at + 2, out.append('\n'))
                        case 'r'  => readString(text, at + 2, out.append('\r'))
                        case 't'  => readString(text, at + 2, out.append('\t'))
                        case 'u'  =>
                            val digits = text.substring(at + 2, math.min(at + 6, text.length))
                            require(digits.matches("[0-9a-fA-F]{4}"), s"malformed \\u escape at offset $at")
                            readString(text, at + 6, out.append(Integer.parseInt(digits, 16).toChar))
                        case c => throw new IllegalArgumentException(s"unknown escape '\\$c' at offset $at")
                    end match
                case c if c < ' ' => throw new IllegalArgumentException(s"control character in string at offset $at")
                case c            => readString(text, at + 1, out.append(c))
            end match
        end readString
    end Json

end TestVectorsJson
