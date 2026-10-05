package kyo

class TeamsIdTest extends kyo.test.Test[Any]:

    import TeamsInvalidIdException.Id
    import TeamsInvalidIdException.Problem

    "an id keeps every character Teams issues and encodes as a bare JSON string" in {
        val conversation = Teams.ConversationId.init("19:efa9296d959346209fea44151c742e73@thread.skype;messageid=1").getOrThrow
        assert(conversation.value == "19:efa9296d959346209fea44151c742e73@thread.skype;messageid=1")
        assert(Json.encode(conversation) == "\"19:efa9296d959346209fea44151c742e73@thread.skype;messageid=1\"")
        assert(Json.decode[Teams.ConversationId]("\"a:1qhNL\"") == Teams.ConversationId.init("a:1qhNL"))
    }

    "an id the schema refuses fails with the decode call's Frame" in {
        val decodeSite = summon[Frame]
        Json.decode[Teams.ConversationId]("\"\"")(using summon[Json], summon[Schema[Teams.ConversationId]], decodeSite) match
            case Result.Failure(e: ConstructorRejectedException) =>
                assert(e.frame == decodeSite, s"rejected at ${e.frame}, decoded at $decodeSite")
                e.rejection match
                    case leaf: TeamsInvalidIdException =>
                        assert(leaf.id == Id.Conversation)
                        assert(leaf.frame == decodeSite, s"refused at ${leaf.frame}, decoded at $decodeSite")
                    case other => fail(s"expected a TeamsInvalidIdException, got $other")
                end match
            case other => fail(s"expected a ConstructorRejectedException, got $other")
        end match
    }

    "every id init refuses empty text, naming the id" in {
        val refused = Chunk(
            Teams.AppId.init("").failure,
            Teams.TenantId.init("").failure,
            Teams.ConversationId.init("").failure,
            Teams.ActivityId.init("").failure,
            Teams.UserId.init("").failure,
            Teams.ChannelId.init("").failure,
            Teams.TeamId.init("").failure,
            Teams.AadObjectId.init("").failure
        )
        assert(refused.map(_.map(e => (e.id, e.problem))) == Chunk(
            Present((Id.App, Problem.Empty)),
            Present((Id.Tenant, Problem.Empty)),
            Present((Id.Conversation, Problem.Empty)),
            Present((Id.Activity, Problem.Empty)),
            Present((Id.User, Problem.Empty)),
            Present((Id.Channel, Problem.Empty)),
            Present((Id.Team, Problem.Empty)),
            Present((Id.AadObject, Problem.Empty))
        ))
        assert(refused.head.map(_.getMessage).exists(_.contains("Teams.AppId is not usable: it is empty")))
    }

    "an id of any length is accepted" in {
        assert(Teams.UserId.init("u" * 4096).map(_.value.length) == Result.succeed(4096))
        assert(Teams.TenantId.init("t" * 4096).map(_.value.length) == Result.succeed(4096))
    }

    "an id's schema refuses what its init refuses, with the leaf init fails with" in {
        def problem[A: Schema](json: String): Maybe[(TeamsInvalidIdException.Id, TeamsInvalidIdException.Problem)] =
            Json.decode[A](json) match
                case Result.Failure(e: ConstructorRejectedException) =>
                    e.rejection match
                        case leaf: TeamsInvalidIdException => Present((leaf.id, leaf.problem))
                        case _                             => Absent
                case _ => Absent
        assert(problem[Teams.ActivityId]("\"\"") ==
            Present((TeamsInvalidIdException.Id.Activity, TeamsInvalidIdException.Problem.Empty)))
        assert(problem[Teams.TenantId]("\"\"") ==
            Present((TeamsInvalidIdException.Id.Tenant, TeamsInvalidIdException.Problem.Empty)))
    }

end TeamsIdTest
