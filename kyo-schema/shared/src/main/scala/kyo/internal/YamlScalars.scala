package kyo.internal

import kyo.*
import scala.util.control.NonFatal

private[kyo] object YamlScalars:

    enum Core derives CanEqual:
        case Null
        case Bool(value: Boolean)
        case Number(value: String)
        case Special(value: String)
        case Str(value: String)
    end Core

    def resolveCore(value: String): Core =
        resolve(value, Yaml.SpecVersion.Yaml12)

    def resolve(value: String, yamlVersion: Yaml.SpecVersion): Core =
        value match
            case "" | "~" | "null" | "Null" | "NULL" => Core.Null
            case _ =>
                parseBool(value, yamlVersion) match
                    case Present(value) => Core.Bool(value)
                    case Absent         => resolveNumber(value, yamlVersion)
        end match
    end resolve

    def parseBool(value: String, yamlVersion: Yaml.SpecVersion): Maybe[Boolean] =
        yamlVersion match
            case Yaml.SpecVersion.Yaml12 =>
                value match
                    case "true" | "True" | "TRUE"    => Maybe(true)
                    case "false" | "False" | "FALSE" => Maybe(false)
                    case _                           => Absent
            case Yaml.SpecVersion.Yaml11 =>
                value match
                    case "y" | "Y" | "yes" | "Yes" | "YES" | "true" | "True" | "TRUE" | "on" | "On" | "ON" =>
                        Maybe(true)
                    case "n" | "N" | "no" | "No" | "NO" | "false" | "False" | "FALSE" | "off" | "Off" | "OFF" =>
                        Maybe(false)
                    case _ => Absent
        end match
    end parseBool

    def resolvesAsCore(value: String): Boolean =
        resolvesAsCore(value, Yaml.SpecVersion.Yaml12)

    def resolvesAsCore(value: String, yamlVersion: Yaml.SpecVersion): Boolean =
        resolve(value, yamlVersion) match
            case Core.Str(_) => false
            case _           => true
    end resolvesAsCore

    def parseCoreInt(value: String): Maybe[String] =
        parseInt(value, Yaml.SpecVersion.Yaml12)

    def parseInt(value: String, yamlVersion: Yaml.SpecVersion): Maybe[String] =
        yamlVersion match
            case Yaml.SpecVersion.Yaml12 => parseYaml12Int(value)
            case Yaml.SpecVersion.Yaml11 => parseYaml11Int(value)
    end parseInt

    def parseCoreFloat(value: String): Maybe[Core] =
        parseFloat(value, Yaml.SpecVersion.Yaml12)

    def parseFloat(value: String, yamlVersion: Yaml.SpecVersion): Maybe[Core] =
        yamlVersion match
            case Yaml.SpecVersion.Yaml12 => parseYaml12Float(value)
            case Yaml.SpecVersion.Yaml11 => parseYaml11Float(value)
    end parseFloat

    private def resolveNumber(value: String, yamlVersion: Yaml.SpecVersion): Core =
        parseInt(value, yamlVersion) match
            case Present(number) => Core.Number(number)
            case Absent =>
                parseFloat(value, yamlVersion) match
                    case Present(number) => number
                    case Absent          => Core.Str(value)
    end resolveNumber

    private def parseYaml12Int(value: String): Maybe[String] =
        if value.length > 2 && value.charAt(0) == '0' && value.charAt(1) == 'o' && digitsOnly(value, 2, 8) then
            Maybe(BigInt(value.drop(2), 8).toString)
        else if value.length > 2 && value.charAt(0) == '0' && value.charAt(1) == 'x' && digitsOnly(value, 2, 16) then
            Maybe(BigInt(value.drop(2), 16).toString)
        else if isDecimalInt(value) then
            Maybe(canonicalDecimalInt(value))
        else Absent
    end parseYaml12Int

    private def parseYaml11Int(value: String): Maybe[String] =
        val (negative, body) = splitSign(value)
        val normalized       = body.filter(_ != '_')
        val parsed =
            if normalized.length > 2 && normalized.charAt(0) == '0' && normalized.charAt(1) == 'b' &&
                digitsOnly(normalized, 2, 2)
            then Maybe(BigInt(normalized.drop(2), 2).toString)
            else if normalized.length > 2 && normalized.charAt(0) == '0' && normalized.charAt(1) == 'x' &&
                digitsOnly(normalized, 2, 16)
            then Maybe(BigInt(normalized.drop(2), 16).toString)
            else if normalized.length > 1 && normalized.charAt(0) == '0' && digitsOnly(normalized, 1, 8) then
                Maybe(BigInt(normalized.drop(1), 8).toString)
            else if isUnsignedDecimalInt(normalized) then Maybe(canonicalUnsignedDecimalInt(normalized))
            else parseSexagesimalInt(normalized).map(_.toString)
        parsed.map(applySign(_, negative))
    end parseYaml11Int

    private def parseYaml12Float(value: String): Maybe[Core] =
        if isFloatNumber(value) then parseDecimalNumber(value).map(Core.Number(_))
        else parseSpecialFloat(value)
    end parseYaml12Float

    private def parseYaml11Float(value: String): Maybe[Core] =
        val (negative, body) = splitSign(value)
        val normalized       = body.filter(_ != '_')
        if normalized.indexOf(':') >= 0 && normalized.indexOf('.') >= 0 then
            parseSexagesimalFloat(normalized).map { value =>
                val signed =
                    if negative then -value
                    else value
                Core.Number(signed.toString)
            }
        else
            val signed =
                if negative then "-" + normalized
                else if value.nonEmpty && value.charAt(0) == '+' then "+" + normalized
                else normalized
            if isYaml11DecimalFloat(signed) then parseDecimalNumber(signed).map(Core.Number(_))
            else parseSpecialFloat(value)
        end if
    end parseYaml11Float

    private def parseSpecialFloat(value: String): Maybe[Core] =
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
    end parseSpecialFloat

    private def parseSexagesimalInt(value: String): Maybe[BigInt] =
        val parts = value.split(':')
        if parts.length < 2 then Absent
        else
            var i     = 0
            var total = BigInt(0)
            while i < parts.length do
                val part = parts(i)
                if !isUnsignedDecimalInt(part) then return Absent
                val parsed = BigInt(part)
                if i > 0 && parsed >= 60 then return Absent
                total = total * 60 + parsed
                i += 1
            end while
            Maybe(total)
        end if
    end parseSexagesimalInt

    private def parseSexagesimalFloat(value: String): Maybe[BigDecimal] =
        val parts = value.split(':')
        if parts.length < 2 then Absent
        else
            var i     = 0
            var total = BigDecimal(0)
            while i < parts.length do
                val part = parts(i)
                val parsed =
                    if i == parts.length - 1 then
                        parseBigDecimal(part) match
                            case Present(value) => value
                            case Absent         => return Absent
                    else if isUnsignedDecimalInt(part) then BigDecimal(BigInt(part))
                    else return Absent
                if i > 0 && (parsed < 0 || parsed >= 60) then return Absent
                total = total * 60 + parsed
                i += 1
            end while
            Maybe(total)
        end if
    end parseSexagesimalFloat

    private def parseDecimalNumber(value: String): Maybe[String] =
        val normalized =
            if value.startsWith("+") then value.drop(1)
            else value
        Maybe(canonicalDecimalFloat(normalized))
    end parseDecimalNumber

    private def canonicalDecimalInt(value: String): String =
        val negative = value.nonEmpty && value.charAt(0) == '-'
        val start =
            if value.nonEmpty && (value.charAt(0) == '-' || value.charAt(0) == '+') then 1
            else 0
        applySign(canonicalUnsignedDecimalInt(value.substring(start)), negative)
    end canonicalDecimalInt

    private def canonicalUnsignedDecimalInt(value: String): String =
        var i = 0
        while i < value.length - 1 && value.charAt(i) == '0' do i += 1
        value.substring(i)
    end canonicalUnsignedDecimalInt

    private def canonicalDecimalFloat(value: String): String =
        val exponent =
            val e = value.indexOf('e')
            if e >= 0 then e else value.indexOf('E')
        val mantissaEnd =
            if exponent >= 0 then exponent
            else value.length
        val suffix =
            if exponent >= 0 then value.substring(exponent)
            else ""
        val mantissa = value.substring(0, mantissaEnd)
        val normalizedMantissa =
            if mantissa.startsWith("-.") then "-0" + mantissa.substring(1)
            else if mantissa.startsWith(".") then "0" + mantissa
            else if mantissa.endsWith(".") then mantissa + "0"
            else mantissa
        normalizedMantissa + suffix
    end canonicalDecimalFloat

    private def applySign(value: String, negative: Boolean): String =
        if negative && value != "0" then "-" + value
        else value
    end applySign

    private def parseBigDecimal(value: String): Maybe[BigDecimal] =
        try Maybe(BigDecimal(value))
        catch
            case NonFatal(_) => Absent
    end parseBigDecimal

    private def splitSign(value: String): (Boolean, String) =
        if value.nonEmpty && value.charAt(0) == '-' then (true, value.drop(1))
        else if value.nonEmpty && value.charAt(0) == '+' then (false, value.drop(1))
        else (false, value)
    end splitSign

    private def isUnsignedDecimalInt(value: String): Boolean =
        digitsOnly(value, 0, 10)

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

    private def isYaml11DecimalFloat(value: String): Boolean =
        val start =
            if value.nonEmpty && (value.charAt(0) == '-' || value.charAt(0) == '+') then 1
            else 0
        if start >= value.length then false
        else
            var i            = start
            var dot          = false
            var exponent     = false
            var digits       = 0
            var expDigits    = 0
            var expSignFound = false
            while i < value.length do
                val c = value.charAt(i)
                if c >= '0' && c <= '9' then
                    if exponent then expDigits += 1
                    else digits += 1
                    i += 1
                else if c == '.' && !dot && !exponent then
                    dot = true
                    i += 1
                else if (c == 'e' || c == 'E') && !exponent && dot then
                    exponent = true
                    i += 1
                    if i < value.length && (value.charAt(i) == '-' || value.charAt(i) == '+') then
                        expSignFound = true
                        i += 1
                    else return false
                    end if
                else return false
                end if
            end while
            dot && digits > 0 && (!exponent || (expSignFound && expDigits > 0))
        end if
    end isYaml11DecimalFloat

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
