package kyo

class TeamsMemberTest extends kyo.test.Test[Any]:

    import TeamsInvalidPageException.Problem

    "a page is 50 to 500 members, the bounds Teams documents" in {
        assert(Chunk(50, 500).map(s => Teams.Member.Page.init(s).map(_.size)) == Chunk(Result.succeed(50), Result.succeed(500)))
        assert(Chunk(49, 501, 0).map(s => Teams.Member.Page.init(s).failure.map(_.problem)) ==
            Chunk(Present(Problem.Size(49, 50, 500)), Present(Problem.Size(501, 50, 500)), Present(Problem.Size(0, 50, 500))))
        assert(Teams.Member.Page.init(100, Present("token-1")).map(_.continuation) == Result.succeed(Present("token-1")))
        assert((Teams.Member.Page.first.size, Teams.Member.Page.first.continuation) == (200, Absent))
        assert(Teams.Member.Page.init(10).failure.map(_.getMessage).exists(_.contains("the size must be from 50 to 500; got 10")))
    }

    "a page of members decodes from the Bot Framework's PagedMembersResult" in {
        val json =
            """{
            "continuationToken": "next-1",
            "members": [
                {"id": "29:a", "name": "Ada", "aadObjectId": "00000000-0000-0000-0000-000000000001", "role": "user", "email": "ada@example.com"},
                {"id": "28:bot", "role": "bot"}
            ]
        }"""
        assert(Json.decode[Teams.Member.Paged](json) == Result.succeed(Teams.Member.Paged(
            Chunk(
                Teams.Account(
                    Teams.UserId.init("29:a").getOrThrow,
                    Present("Ada"),
                    Present(Teams.AadObjectId.init("00000000-0000-0000-0000-000000000001").getOrThrow),
                    Present(Teams.Account.Role.User)
                ),
                Teams.Account(Teams.UserId.init("28:bot").getOrThrow, role = Present(Teams.Account.Role.Bot))
            ),
            Present("next-1")
        )))
        assert(Json.decode[Teams.Member.Paged]("""{"members":[]}""") == Result.succeed(Teams.Member.Paged()))
    }

end TeamsMemberTest
