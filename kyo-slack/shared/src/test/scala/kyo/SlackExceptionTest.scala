package kyo

class SlackExceptionTest extends kyo.test.Test[Any]:

    "each leaf builds its message from its own fields" in {
        val leaves: List[(SlackException, String)] = List(
            SlackTransportException("chat.postMessage", SlackTransportException.Kind.Connect, "slack.com", 443, Absent)(Absent) ->
                "Slack chat.postMessage failed at the transport: connection refused or reset (slack.com:443).",
            SlackTransportException(
                "socket-connect",
                SlackTransportException.Kind.WebSocketHandshake,
                "wss-primary.slack.com",
                443,
                Absent
            )(
                Absent
            ) ->
                "Slack socket-connect failed at the transport: the WebSocket handshake was refused (wss-primary.slack.com:443).",
            SlackTransportException("response_url", SlackTransportException.Kind.Timeout, "hooks.slack.com", 443, Present(5.seconds))(
                Absent
            ) ->
                s"Slack response_url failed at the transport: no answer in time (hooks.slack.com:443, after ${5.seconds.show}).",
            SlackTransportException(
                "auth.test",
                SlackTransportException.Kind.PayloadTooLarge(20.mb, 16.mb),
                "slack.com",
                443,
                Absent
            )(Absent) ->
                s"Slack auth.test failed at the transport: the answer of ${20.mb.show} exceeds ${16.mb.show} (slack.com:443).",
            SlackRefusedUrlException("response_url") ->
                "Slack response_url: the url Slack supplied is not one the module sends to, so nothing was sent.",
            SlackInvalidTokenException(SlackInvalidTokenException.Token.Bot, SlackInvalidTokenException.Problem.InvalidCharacter(7)) ->
                "SlackToken.Bot is not a Slack token: the character at position 7 is not printable ASCII other than space.",
            SlackInvalidTokenException(SlackInvalidTokenException.Token.AppLevel, SlackInvalidTokenException.Problem.Empty) ->
                "SlackToken.AppLevel is not a Slack token: it is empty.",
            SlackInvalidMethodException(SlackInvalidMethodException.Problem.Character(10)) ->
                "SlackMethod is not usable: the character at position 10 is not an ASCII letter, a digit, '.' or '_'.",
            SlackUnexpectedStatusException("auth.test", HttpStatus.ServiceUnavailable) ->
                "Slack auth.test answered HTTP 503 without a Slack response body.",
            SlackDecodeException(
                "auth.test",
                SlackDecodeException.Part.Envelope,
                SlackDecodeException.Failure.Parse,
                Chunk.empty,
                Present(7)
            ) ->
                "Slack auth.test response envelope did not decode: unparseable input, position 7.",
            SlackDecodeException(
                "auth.test",
                SlackDecodeException.Part.Payload,
                SlackDecodeException.Failure.MissingField,
                Chunk("identity", "user_id"),
                Absent
            ) ->
                "Slack auth.test response payload did not decode: missing field at identity.user_id.",
            SlackRateLimitException("chat.postMessage", Present(30.seconds)) ->
                s"Slack rate-limited chat.postMessage; retry after ${30.seconds.show}.",
            SlackRateLimitException("chat.postMessage", Absent) ->
                "Slack rate-limited chat.postMessage; Slack sent no usable Retry-After.",
            SlackChannelNotFoundException("chat.postMessage", Chunk.empty) ->
                "Slack chat.postMessage answered channel_not_found.",
            SlackInvalidArgumentsException("views.open", Chunk("[ERROR] missing required field: view", "[ERROR] bad")) ->
                "Slack views.open answered invalid_arguments: [ERROR] missing required field: view; [ERROR] bad.",
            SlackMissingScopeException("chat.postMessage", Chunk("chat:write"), Chunk("channels:read"), Chunk.empty) ->
                "Slack chat.postMessage answered missing_scope. Needed: chat:write; the token has: channels:read.",
            SlackOtherApiException("chat.postMessage", "fatal_error", Chunk.empty) ->
                "Slack chat.postMessage answered fatal_error.",
            SlackLinkDisabledException() ->
                "Slack disabled the Socket Mode link (disconnect reason link_disabled).",
            SlackInvalidRawBlockException(
                SlackInvalidRawBlockException.Problem.NotJson(SlackDecodeException.Failure.Parse, Present(3))
            ) ->
                "SlackBlock.Raw is not a JSON object: unparseable input at position 3.",
            SlackInvalidRawBlockException(
                SlackInvalidRawBlockException.Problem.NotJson(SlackDecodeException.Failure.LimitExceeded, Absent)
            ) ->
                "SlackBlock.Raw is not a JSON object: limit exceeded.",
            SlackInvalidRawBlockException(SlackInvalidRawBlockException.Problem.NotAnObject) ->
                "SlackBlock.Raw is not a JSON object: the top-level value is not an object.",
            SlackInvalidConfigException(SlackInvalidConfigException.Problem.AckDeadline(Duration.Zero)) ->
                s"SlackConfig.ackDeadline must be a positive, finite duration; got ${Duration.Zero.show}.",
            SlackInvalidConfigException(SlackInvalidConfigException.Problem.BaseUrl(SlackInvalidConfigException.UrlProblem.Query)) ->
                "SlackConfig.baseUrl must be an absolute http or https url on a host: it has a query."
        )
        leaves.foreach { case (ex, msg) =>
            assert(ex.getMessage.contains(msg), s"${ex.getClass.getSimpleName} message: ${ex.getMessage}")
        }
    }

    "every coded leaf reports its Slack code, method and messages" in {
        val m                                         = Chunk("detail")
        val leaves: List[(SlackApiException, String)] = List(
            SlackInvalidAuthException("x", m)                            -> "invalid_auth",
            SlackNotAuthedException("x", m)                              -> "not_authed",
            SlackTokenRevokedException("x", m)                           -> "token_revoked",
            SlackTokenExpiredException("x", m)                           -> "token_expired",
            SlackAccountInactiveException("x", m)                        -> "account_inactive",
            SlackNotAllowedTokenTypeException("x", m)                    -> "not_allowed_token_type",
            SlackMissingScopeException("x", Chunk.empty, Chunk.empty, m) -> "missing_scope",
            SlackInvalidArgumentsException("x", m)                       -> "invalid_arguments",
            SlackChannelNotFoundException("x", m)                        -> "channel_not_found",
            SlackNotInChannelException("x", m)                           -> "not_in_channel",
            SlackIsArchivedException("x", m)                             -> "is_archived",
            SlackUserNotInChannelException("x", m)                       -> "user_not_in_channel",
            SlackNoTextException("x", m)                                 -> "no_text",
            SlackMsgTooLongException("x", m)                             -> "msg_too_long",
            SlackMsgBlocksTooLongException("x", m)                       -> "msg_blocks_too_long",
            SlackInvalidBlocksException("x", m)                          -> "invalid_blocks",
            SlackInvalidBlocksFormatException("x", m)                    -> "invalid_blocks_format",
            SlackCannotReplyToMessageException("x", m)                   -> "cannot_reply_to_message",
            SlackMessageNotFoundException("x", m)                        -> "message_not_found",
            SlackCantUpdateMessageException("x", m)                      -> "cant_update_message",
            SlackExpiredTriggerIdException("x", m)                       -> "expired_trigger_id",
            SlackExchangedTriggerIdException("x", m)                     -> "exchanged_trigger_id",
            SlackInvalidTriggerIdException("x", m)                       -> "invalid_trigger_id",
            SlackViewTooLargeException("x", m)                           -> "view_too_large",
            SlackNotFoundException("x", m)                               -> "not_found",
            SlackOtherApiException("x", "team_not_found", m)             -> "team_not_found"
        )
        assert(leaves.map { case (ex, _) => (ex.method, ex.code, ex.messages) } == leaves.map { case (_, c) => ("x", c, m) })
    }

    "no leaf keeps a kyo-schema failure: the decode leaf has no cause and renders none of the body" in {
        // The marker is built from parts: a development-mode message quotes the source lines around
        // the construction site, so a literal marker would be found there.
        val bodyMarker                   = Seq("body", "marker").mkString("-")
        val decodeCause: DecodeException =
            Json.decode[Int](s"\"$bodyMarker\"") match
                case Result.Failure(ex) => ex
                case other              => throw new IllegalStateException(s"expected a decode failure, got $other")
        val d = SlackDecodeException("auth.test", SlackDecodeException.Part.Payload, decodeCause)
        assert(d.getCause() == null)
        assert(!d.getMessage.contains(bodyMarker), d.getMessage)
        assert(!d.toString.contains(bodyMarker), d.toString)
    }

    "the transport leaf keeps its kyo-net cause out of equality and out of the message" in {
        import SlackTransportException.Kind
        val detailMarker = Seq("net", "detail", "marker").mkString("-")
        val netCause     = kyo.net.NetConnectException("hooks.slack.com", 443, new Exception(detailMarker))
        val withCause    = SlackTransportException("response_url", Kind.Connect, "hooks.slack.com", 443, Absent)(Present(netCause))
        val without      = SlackTransportException("response_url", Kind.Connect, "hooks.slack.com", 443, Absent)(Absent)
        assert(withCause == without)
        assert(withCause.getCause() eq netCause)
        assert(without.getCause() == null)
        assert(!withCause.getMessage.contains(detailMarker), withCause.getMessage)
    }

    "each kyo-schema decode leaf maps to its kind, its path, and a position only for a parse failure" in {
        import SlackDecodeException.Failure
        val path  = Seq("user", "id")
        val cases = Chunk[(DecodeException, (Failure, Chunk[String], Maybe[Int]))](
            ParseException(Json(), "input", "diagnostic", path, 12)          -> (Failure.Parse, Chunk.from(path), Present(12)),
            ParseException(Json(), "input", "diagnostic")                    -> (Failure.Parse, Chunk.empty, Absent),
            MissingFieldException(path, "name")                              -> (Failure.MissingField, Chunk("user", "id", "name"), Absent),
            TypeMismatchException(path, "String", "input")                   -> (Failure.TypeMismatch, Chunk.from(path), Absent),
            UnknownVariantException(path, "input")                           -> (Failure.UnknownVariant, Chunk.from(path), Absent),
            UnknownFieldException(path, "input")                             -> (Failure.UnknownField, Chunk.from(path), Absent),
            NoVariantMatchException(path, Chunk("A"))                        -> (Failure.NoVariantMatch, Chunk.from(path), Absent),
            AmbiguousVariantMatchException(path, Chunk("A", "B"))            -> (Failure.AmbiguousVariantMatch, Chunk.from(path), Absent),
            MissingTagKeyException(path, "type")                             -> (Failure.MissingTagKey, Chunk.from(path), Absent),
            ConstructorRejectedException(path, "Id", "input")                -> (Failure.ConstructorRejected, Chunk.from(path), Absent),
            TruncatedInputException(Json(), "input")                         -> (Failure.TruncatedInput, Chunk.empty, Absent),
            TrailingInputException(Json(), "input")                          -> (Failure.TrailingInput, Chunk.empty, Absent),
            RecordDecodeException(3, 120, MissingFieldException(path, "id")) -> (Failure.RecordDecode, Chunk.empty, Absent),
            LimitExceededException("depth", 600, 512)                        -> (Failure.LimitExceeded, Chunk.empty, Absent),
            RangeException(-1, "Long", 0, 10)                                -> (Failure.Range, Chunk.empty, Absent)
        )
        cases.foreach { case (schemaLeaf, (failure, leafPath, position)) =>
            val part = SlackDecodeException.Part.Payload
            assert(SlackDecodeException("auth.test", part, schemaLeaf) ==
                SlackDecodeException("auth.test", part, failure, leafPath, position))
        }
        succeed
    }

    "leaves compare by their fields" in {
        assert(SlackRateLimitException("m", Present(1.second)) == SlackRateLimitException("m", Present(1.second)))
        assert(SlackRateLimitException("m", Present(1.second)) != SlackRateLimitException("m", Absent))
        assert(SlackOtherApiException("m", "a", Chunk.empty) != SlackOtherApiException("m", "b", Chunk.empty))
        assert((SlackChannelNotFoundException("m", Chunk.empty): SlackException) != SlackNotInChannelException("m", Chunk.empty))
    }

end SlackExceptionTest
