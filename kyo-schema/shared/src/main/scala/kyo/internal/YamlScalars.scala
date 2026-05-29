package kyo.internal

import kyo.*

private[kyo] object YamlScalars:

    enum Core derives CanEqual:
        case Null
        case Bool(value: Boolean)
        case Number(value: String)
        case Special(value: String)
        case Str(value: String)
    end Core

    def resolveCore(value: String): Core =
        value match
            case "" | "~" | "null" | "Null" | "NULL" => Core.Null
            case "true" | "True" | "TRUE"            => Core.Bool(true)
            case "false" | "False" | "FALSE"         => Core.Bool(false)
            case _ =>
                parseCoreInt(value) match
                    case Present(number) => Core.Number(number)
                    case Absent =>
                        parseCoreFloat(value) match
                            case Present(number) => number
                            case Absent          => Core.Str(value)
        end match
    end resolveCore

    def resolvesAsCore(value: String): Boolean =
        resolveCore(value) match
            case Core.Str(_) => false
            case _           => true
    end resolvesAsCore

    def parseCoreInt(value: String): Maybe[String] =
        if value.length > 2 && value.charAt(0) == '0' && value.charAt(1) == 'o' && digitsOnly(value, 2, 8) then
            Maybe(BigInt(value.drop(2), 8).toString)
        else if value.length > 2 && value.charAt(0) == '0' && value.charAt(1) == 'x' && digitsOnly(value, 2, 16) then
            Maybe(BigInt(value.drop(2), 16).toString)
        else if isDecimalInt(value) then
            Maybe(BigInt(value).toString)
        else Absent
    end parseCoreInt

    def parseCoreFloat(value: String): Maybe[Core] =
        if isFloatNumber(value) then Maybe(Core.Number(BigDecimal(value).toString))
        else
            value match
                case ".inf" | ".Inf" | ".INF" | "+.inf" | "+.Inf" | "+.INF" =>
                    Maybe(Core.Special("Infinity"))
                case "-.inf" | "-.Inf" | "-.INF" =>
                    Maybe(Core.Special("-Infinity"))
                case ".nan" | ".NaN" | ".NAN" =>
                    Maybe(Core.Special("NaN"))
                case _ =>
                    Absent
            end match
    end parseCoreFloat

    private def isDecimalInt(value: String): Boolean =
        val start =
            if value.nonEmpty && (value.charAt(0) == '-' || value.charAt(0) == '+') then 1
            else 0
        start < value.length && digitsOnly(value, start, 10)
    end isDecimalInt

    private def isFloatNumber(value: String): Boolean =
        val start =
            if value.nonEmpty && (value.charAt(0) == '-' || value.charAt(0) == '+') then 1
            else 0
        if start >= value.length then false
        else
            var i              = start
            var digitsBefore   = 0
            var digitsAfterDot = 0
            var dot            = false
            var exponent       = false
            var expDigits      = 0
            while i < value.length do
                val c = value.charAt(i)
                if c >= '0' && c <= '9' then
                    if exponent then expDigits += 1
                    else if dot then digitsAfterDot += 1
                    else digitsBefore += 1
                    i += 1
                else if c == '.' && !dot && !exponent then
                    dot = true
                    i += 1
                else if (c == 'e' || c == 'E') && !exponent && digitsBefore > 0 then
                    exponent = true
                    i += 1
                    if i < value.length && (value.charAt(i) == '-' || value.charAt(i) == '+') then i += 1
                else return false
                end if
            end while
            val mantissa =
                if dot && digitsBefore == 0 then digitsAfterDot > 0
                else digitsBefore > 0
            mantissa && ((dot && digitsBefore + digitsAfterDot > 0) || (exponent && expDigits > 0))
        end if
    end isFloatNumber

    private def digitsOnly(value: String, start: Int, radix: Int): Boolean =
        var i      = start
        var digits = 0
        while i < value.length do
            if Character.digit(value.charAt(i), radix) < 0 then return false
            digits += 1
            i += 1
        end while
        digits > 0
    end digitsOnly
end YamlScalars
