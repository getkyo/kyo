package kyo.internal.teams

import java.nio.charset.StandardCharsets.US_ASCII
import kyo.*
import kyo.TeamsMalformedTokenException.Claim
import kyo.TeamsMalformedTokenException.Part
import kyo.TeamsMalformedTokenException.Problem
import kyo.crypto.Rsa
import kyo.crypto.RsaPkcs1

/** Verification of an inbound request's token, the requirements of Microsoft's "Verify the JWT token" in the order the module checks
  * them: the bearer header and its three canonical base64url segments, the header and the required claims, `RS256` and a `kid`, the
  * issuer and audience, the lifetime within the clock skew, the signature under the key the `kid` names, and only then the two fields
  * of the body the token binds (the key endorses the Activity's channel, which is Teams, and the token names its service URL). Each
  * check runs only when every earlier one passed, so a forged token reaches a key fetch only with a plausible issuer, audience and
  * lifetime, and the body is read only under a verified signature.
  */
private[kyo] object Jwt:

    private inline val Algorithm = "RS256"
    private inline val Channel   = "msteams"

    /** The NumericDate of 9999-12-31T23:59:59Z. */
    private inline val MaxSeconds = 253402300799L

    def verify(teams: Teams, authorization: Maybe[String], body: Span[Byte])(using
        Frame
    ): Unit < (Async & Abort[TeamsWebhookVerifyFailure]) =
        val config = teams.config
        for
            token                       <- bearer(authorization, config.maxTokenLength)
            segments                    <- split(token)
            (header, claims, signature) <- decoded(segments)
            kid                         <- signedWith(header)
            (expiry, notBefore)         <- lifetime(claims)
            _                           <- issuedFor(claims, config)
            _                           <- current(expiry, notBefore, config.clockSkew)
            key                         <- teams.keys.get(kid)
            _                           <- signed(kid, key, segments, signature)
            _                           <- bound(key, claims, body)
        yield ()
        end for
    end verify

    private def bearer(authorization: Maybe[String], max: Int)(using Frame): String < Abort[TeamsAuthenticationException] =
        authorization match
            case Absent         => Abort.fail(TeamsMissingAuthorizationException())
            case Present(value) =>
                if !value.regionMatches(true, 0, "Bearer ", 0, 7) then Abort.fail(TeamsMalformedTokenException(Problem.NotBearer))
                else
                    val token = value.substring(7)
                    if token.length > max then Abort.fail(TeamsMalformedTokenException(Problem.TooLong(token.length, max)))
                    else token
                end if

    private def split(token: String)(using Frame): Chunk[String] < Abort[TeamsAuthenticationException] =
        val segments = Chunk.from(token.split("\\.", -1))
        if segments.size != 3 then Abort.fail(TeamsMalformedTokenException(Problem.Segments(segments.size)))
        else segments
    end split

    private def decoded(segments: Chunk[String])(using
        Frame
    ): (Wire.JwtHeader, Wire.JwtClaims, Span[Byte]) < Abort[TeamsAuthenticationException] =
        for
            headerBytes <- base64(Part.Header, segments(0))
            claimsBytes <- base64(Part.Claims, segments(1))
            signature   <- base64(Part.Signature, segments(2))
            header      <- json[Wire.JwtHeader](Part.Header, headerBytes)
            claims      <- json[Wire.JwtClaims](Part.Claims, claimsBytes)
            _           <- Maybe.fromOption(Chunk(
                Claim.Issuer     -> claims.iss.isEmpty,
                Claim.Audience   -> claims.aud.isEmpty,
                Claim.Expiry     -> claims.exp.isEmpty,
                Claim.ServiceUrl -> claims.serviceUrl.isEmpty
            ).collectFirst { case (claim, true) => claim }) match
                case Present(claim) => Abort.fail(TeamsMalformedTokenException(Problem.MissingClaim(claim)))
                case Absent         => Kyo.unit
        yield (header, claims, signature)

    private def base64(part: Part, segment: String)(using Frame): Span[Byte] < Abort[TeamsAuthenticationException] =
        Base64.decodeUrl(segment) match
            case Result.Success(bytes)   => bytes
            case Result.Failure(failure) => Abort.fail(TeamsMalformedTokenException(Problem.Base64(part, failure)))
            case Result.Panic(ex)        => Abort.panic(ex)

    private def json[A: Schema](part: Part, bytes: Span[Byte])(using Frame): A < Abort[TeamsAuthenticationException] =
        Json.decodeBytes[A](bytes) match
            case Result.Success(a)  => a
            case Result.Failure(ex) => Abort.fail(TeamsMalformedTokenException(Problem.Json(part, TeamsDecodeException.Failure.of(ex)._1)))
            case Result.Panic(ex)   => Abort.panic(ex)

    /** The token's `kid`, when its `alg` is `RS256`: the one algorithm verified, whatever the metadata lists, so `none` and every
      * HMAC algorithm are refused before a key is touched.
      */
    private def signedWith(header: Wire.JwtHeader)(using Frame): String < Abort[TeamsAuthenticationException] =
        if header.alg != Algorithm then Abort.fail(TeamsUnsupportedAlgorithmException(TeamsException.bounded(header.alg, 16)))
        else
            header.kid match
                case Present(kid) => kid
                case Absent       => Abort.fail(TeamsMalformedTokenException(Problem.MissingKeyId))

    private def lifetime(claims: Wire.JwtClaims)(using Frame): (Instant, Maybe[Instant]) < Abort[TeamsAuthenticationException] =
        val outOfRange = (claims.exp.toChunk ++ claims.nbf.toChunk).exists(s => s < 0 || s > MaxSeconds)
        if outOfRange then Abort.fail(TeamsMalformedTokenException(Problem.Json(Part.Claims, TeamsDecodeException.Failure.Range)))
        else (instant(claims.exp.getOrElse(0L)), claims.nbf.map(instant))
    end lifetime

    private def instant(seconds: Long): Instant = Instant.of(seconds.seconds, Duration.Zero)

    private def issuedFor(claims: Wire.JwtClaims, config: TeamsConfig)(using Frame): Unit < Abort[TeamsAuthenticationException] =
        val issuer                  = claims.iss.getOrElse("")
        val audience: Chunk[String] = claims.aud.fold(Chunk.empty[String]) {
            case one: String                    => Chunk(one)
            case many: Chunk[String @unchecked] => many
        }
        if issuer != config.issuer then Abort.fail(TeamsWrongIssuerException(TeamsException.bounded(issuer, 256)))
        else if !audience.contains(config.appId.value) then
            Abort.fail(TeamsWrongAudienceException(audience.take(8).map(TeamsException.bounded(_, 128))))
        else Kyo.unit
        end if
    end issuedFor

    /** Passes when `notBefore - skew <= now < expiry + skew`. */
    private def current(expiry: Instant, notBefore: Maybe[Instant], skew: Duration)(using
        Frame
    ): Unit < (Sync & Abort[TeamsAuthenticationException]) =
        Clock.now.map { now =>
            if !(now < expiry + skew) then Abort.fail(TeamsTokenExpiredException(expiry, now, skew))
            else
                notBefore match
                    case Present(from) if now < from - skew => Abort.fail(TeamsTokenNotYetValidException(from, now, skew))
                    case _                                  => Kyo.unit
        }

    private def signed(kid: String, key: Wire.Key, segments: Chunk[String], signature: Span[Byte])(using
        Frame
    ): Unit < Abort[TeamsAuthenticationException] =
        Rsa.verificationKeyFromJwk(key.n.getOrElse(""), key.e.getOrElse("")) match
            case Result.Success(rsa) =>
                val message = Span.from(s"${segments(0)}.${segments(1)}".getBytes(US_ASCII))
                if RsaPkcs1.verifySha256(rsa, message, signature) then Kyo.unit
                else Abort.fail(TeamsSignatureMismatchException())
            case Result.Failure(problem) => Abort.fail(TeamsInvalidKeyException(TeamsException.bounded(kid, 128), problem))
            case Result.Panic(ex)        => Abort.panic(ex)

    private def bound(key: Wire.Key, claims: Wire.JwtClaims, body: Span[Byte])(using Frame): Unit < Abort[TeamsWebhookVerifyFailure] =
        Json.decodeBytes[Wire.Binding](body) match
            case Result.Success(binding) =>
                if !key.endorsements.contains(binding.channelId) then
                    Abort.fail(TeamsMissingEndorsementException(TeamsException.bounded(binding.channelId, 64)))
                else if binding.channelId != Channel then
                    Abort.fail(TeamsUnsupportedChannelException(TeamsException.bounded(binding.channelId, 64)))
                // Compared as exact strings, as Microsoft's reference validator does: the claim is the text the token was issued for.
                else if !claims.serviceUrl.contains(binding.serviceUrl) then Abort.fail(TeamsServiceUrlMismatchException())
                else Kyo.unit
            case Result.Failure(ex) => Abort.fail(TeamsWebhookDecodeException.of(ex))
            case Result.Panic(ex)   => Abort.panic(ex)

end Jwt
