package kyo.internal.email

import kyo.*

private[kyo] object CommandLine:

    /** `value` when it is a non-empty line of at most `limit` UTF-8 octets with no CR, LF or NUL, or the problem. */
    def check(value: String, limit: Int)(using Frame): Result[EmailInvalidCommandException, String] =
        val position = value.indexWhere(c => c == '\r' || c == '\n' || c == '\u0000')
        if value.isEmpty then Result.fail(EmailInvalidCommandException(EmailInvalidCommandException.Problem.Empty))
        else if position >= 0 then Result.fail(EmailInvalidCommandException(EmailInvalidCommandException.Problem.LineBreakOrNul(position)))
        else if kyo.internal.charset.Utf8.encode(value).size > limit then
            Result.fail(EmailInvalidCommandException(EmailInvalidCommandException.Problem.TooLong(limit)))
        else Result.succeed(value)
        end if
    end check

end CommandLine
