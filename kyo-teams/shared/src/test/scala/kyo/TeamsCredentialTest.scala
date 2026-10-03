package kyo

class TeamsCredentialTest extends kyo.test.Test[Any]:

    import TeamsInvalidTokenException.Problem
    import TeamsInvalidTokenException.Token

    // Built from parts so the whole secret appears nowhere in this file's source text.
    private val secretText = Chunk("Ab1~", "x.Y_z-", "credTEST").mkString

    "a client secret renders redacted, compares by value, and its schema writes its text" in {
        val secret = Teams.ClientSecret.init(secretText).getOrThrow
        assert(secret.toString == "Teams.ClientSecret(<redacted>)")
        assert(secret.value == secretText)
        assert(secret == Teams.ClientSecret.init(secretText).getOrThrow)
        assert(Json.encode(secret) == "\"" + secretText + "\"")
        assert(Json.decode[Teams.ClientSecret]("\"" + secretText + "\"") == Result.succeed(secret))
    }

    "an assertion and an identity header render redacted" in {
        val assertion = Teams.ClientAssertion.init("eyJ0eXAi.eyJzdWIi.c2ln").getOrThrow
        val header    = Teams.IdentityHeader.init("853b9a84-5bfa-4b22-a3f3-0b9a43d9ad8a").getOrThrow
        assert((assertion.toString, header.toString) == ("Teams.ClientAssertion(<redacted>)", "Teams.IdentityHeader(<redacted>)"))
        assert(assertion.value == "eyJ0eXAi.eyJzdWIi.c2ln")
    }

    "init refuses empty text, text over the bound, and a character outside printable ASCII other than space, at its position" in {
        val refused = Chunk(
            Teams.ClientSecret.init("").failure,
            Teams.ClientSecret.init("s" * 257).failure,
            Teams.ClientSecret.init("ab cd").failure,
            Teams.ClientAssertion.init("a" * 16385).failure,
            Teams.ClientAssertion.init("ab\ncd").failure,
            Teams.IdentityHeader.init("h" * 1025).failure,
            Teams.IdentityHeader.init("é").failure
        )
        assert(refused.map(_.map(e => (e.token, e.problem))) == Chunk(
            Present((Token.ClientSecret, Problem.Empty)),
            Present((Token.ClientSecret, Problem.TooLong(257, 256))),
            Present((Token.ClientSecret, Problem.Character(2))),
            Present((Token.ClientAssertion, Problem.TooLong(16385, 16384))),
            Present((Token.ClientAssertion, Problem.Character(2))),
            Present((Token.IdentityHeader, Problem.TooLong(1025, 1024))),
            Present((Token.IdentityHeader, Problem.Character(0)))
        ))
    }

    "a refusal's message names the position, never the text" in {
        // Built from parts: the message of a failure built on this line quotes the line in development mode.
        val text    = Chunk("cred", "TEST secret").mkString
        val message = Teams.ClientSecret.init(text).failure.map(_.getMessage).getOrElse("")
        assert(message.contains("Teams.ClientSecret is not usable: the character at position 8"))
        assert(!message.contains("credTEST"))
    }

    "the bounds are accepted" in {
        assert(Teams.ClientSecret.init("~" * 256).map(_.value.length) == Result.succeed(256))
        assert(Teams.ClientAssertion.init("a" * 16384).map(_.value.length) == Result.succeed(16384))
    }

    "a secret's schema refuses what its init refuses" in {
        assert(Json.decode[Teams.ClientSecret]("\"\"").isFailure)
        assert(Json.decode[Teams.IdentityHeader]("\"a b\"").isFailure)
    }

    "each credential's schema refusal carries the decode call's Frame" in {
        val decodeSite = summon[Frame]

        def refusedAt[A](json: String)(using schema: Schema[A]): Maybe[(Frame, TeamsInvalidTokenException.Token)] =
            Json.decode[A](json)(using summon[Json], schema, decodeSite) match
                case Result.Failure(e: ConstructorRejectedException) =>
                    e.rejection match
                        case leaf: TeamsInvalidTokenException => Present((leaf.frame, leaf.token))
                        case _                                => Absent
                case _ => Absent
        import TeamsInvalidTokenException.Token
        assert(refusedAt[Teams.ClientSecret]("\"\"") == Present((decodeSite, Token.ClientSecret)))
        assert(refusedAt[Teams.ClientAssertion]("\"a b\"") == Present((decodeSite, Token.ClientAssertion)))
        assert(refusedAt[Teams.IdentityHeader]("\"a b\"") == Present((decodeSite, Token.IdentityHeader)))
    }

end TeamsCredentialTest
