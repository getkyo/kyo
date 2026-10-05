package kyo.internal.teams

import kyo.*
import kyo.schema.rename

/** The wire documents no public type represents: token answers, error bodies, the Bot Connector's resource answers, the OpenID metadata
  * and key set, and an inbound token's header and claims.
  */
private[kyo] object Wire:

    /** The identity platform's token answer. */
    final case class TokenResponse(
        @rename("token_type") tokenType: String,
        @rename("expires_in") expiresIn: Long,
        @rename("access_token") accessToken: String
    ) derives Schema

    /** The managed identity endpoint's token answer; `expires_on` is the expiry as Unix seconds, written as a string. */
    final case class IdentityTokenResponse(
        @rename("token_type") tokenType: String,
        @rename("expires_on") expiresOn: String,
        @rename("access_token") accessToken: String
    ) derives Schema

    /** The identity platform's error object. `error_description` is not read: it quotes the request. */
    final case class IdentityError(
        error: String,
        @rename("error_codes") errorCodes: Chunk[Int] = Chunk.empty,
        @rename("trace_id") traceId: Maybe[String] = Absent,
        @rename("correlation_id") correlationId: Maybe[String] = Absent
    ) derives Schema

    /** The Bot Connector's `ErrorResponse`. Its `innerHttpError` has no documented shape and is not read. */
    final case class ErrorResponse(error: ErrorBody) derives Schema
    final case class ErrorBody(code: String, message: Maybe[String] = Absent) derives Schema

    /** The answer to a write to a user who blocked, muted or uninstalled the bot: `message` is itself JSON holding `subCode`. */
    final case class BlockedResponse(errorCode: Int, message: String) derives Schema
    final case class BlockedMessage(subCode: String, message: Maybe[String] = Absent) derives Schema

    /** The Bot Connector's `ResourceResponse`. */
    final case class ResourceResponse(id: Teams.ActivityId) derives Schema

    /** The Bot Connector's `ConversationResourceResponse`. */
    final case class ConversationResourceResponse(
        id: Teams.ConversationId,
        serviceUrl: Maybe[Teams.ServiceUrl] = Absent,
        activityId: Maybe[Teams.ActivityId] = Absent
    ) derives Schema

    /** A typing indicator, the one Activity `typing` sends. */
    final case class TypingActivity(@rename("type") kind: String = "typing") derives Schema

    /** The Bot Framework's OpenID metadata, the three fields the module reads. */
    final case class OpenIdMetadata(
        issuer: String,
        @rename("jwks_uri") jwksUri: String,
        @rename("id_token_signing_alg_values_supported") algorithms: Chunk[String] = Chunk.empty
    ) derives Schema

    /** The signing key set. */
    final case class KeySet(keys: Chunk[Key]) derives Schema

    /** One JSON Web Key, as the key set holds it. Every field but `kty` is optional on the wire; a key the module cannot use is dropped
      * when the set is read.
      */
    final case class Key(
        kty: String,
        use: Maybe[String] = Absent,
        kid: Maybe[String] = Absent,
        n: Maybe[String] = Absent,
        e: Maybe[String] = Absent,
        endorsements: Chunk[String] = Chunk.empty
    ) derives Schema

    /** A JSON Web Token's header, the two fields the module reads. */
    final case class JwtHeader(alg: String, kid: Maybe[String] = Absent) derives Schema

    /** A JSON Web Token's claims, each optional on the wire so a missing one is its own failure. `aud` is a string or an array of them
      * (RFC 7519 section 4.1.3); `exp` and `nbf` are NumericDate seconds.
      */
    final case class JwtClaims(
        iss: Maybe[String] = Absent,
        aud: Maybe[String | Chunk[String]] = Absent,
        exp: Maybe[Long] = Absent,
        nbf: Maybe[Long] = Absent,
        serviceUrl: Maybe[String] = Absent
    ) derives Schema

    /** The two fields of an Activity its token binds. */
    final case class Binding(serviceUrl: String, channelId: String) derives Schema

end Wire
