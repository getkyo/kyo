package kyo

class TeamsConversationTest extends kyo.test.Test[Any]:

    private def tree(json: String): Structure.Value = TeamsJsonTree(json)

    private val tenant = Teams.TenantId.init("72f988bf-86f1-41af-91ab-2d7cd011db47").getOrThrow

    "a conversation with a user encodes as the Bot Framework's ConversationParameters, the tenant in channelData" in {
        val create = Teams.Conversation.Create(
            bot = Present(Teams.Account(Teams.UserId.init("28:bot").getOrThrow)),
            members = Chunk(Teams.Account(Teams.UserId.init("29:user").getOrThrow)),
            isGroup = Present(false),
            tenantId = Present(tenant),
            channelData = Present(Teams.ChannelData(tenant = Present(Teams.ChannelData.Tenant(tenant)))),
            activity = Present(Teams.Message.Create.text("hello"))
        )
        assert(tree(Json.encode(create)) == tree("""{
            "bot": {"id": "28:bot"},
            "members": [{"id": "29:user"}],
            "isGroup": false,
            "tenantId": "72f988bf-86f1-41af-91ab-2d7cd011db47",
            "channelData": {"tenant": {"id": "72f988bf-86f1-41af-91ab-2d7cd011db47"}},
            "activity": {"text": "hello", "type": "message"}
        }"""))
        assert(Json.decode[Teams.Conversation.Create](Json.encode(create)) == Result.succeed(create))
    }

    "a conversation in a channel names the channel in channelData" in {
        val channel = Teams.ChannelId.init("19:abc@thread.skype").getOrThrow
        val create  = Teams.Conversation.Create(
            isGroup = Present(true),
            channelData = Present(Teams.ChannelData(channel = Present(Teams.ChannelData.Channel(channel)))),
            activity = Present(Teams.Message.Create.text("hi team"))
        )
        assert(tree(Json.encode(create)) == tree(
            """{"isGroup":true,"channelData":{"channel":{"id":"19:abc@thread.skype"}},"activity":{"text":"hi team","type":"message"}}"""
        ))
    }

    "a reference is the Bot Framework's ConversationReference and round-trips, so a caller stores it" in {
        val reference = Teams.ConversationReference(
            serviceUrl = Teams.ServiceUrl.init("https://smba.trafficmanager.net/teams/").getOrThrow,
            conversation = Teams.ConversationAccount(Teams.ConversationId.init("a:1qhNL").getOrThrow, tenantId = Present(tenant)),
            bot = Present(Teams.Account(Teams.UserId.init("28:bot").getOrThrow))
        )
        val json = """{
            "serviceUrl": "https://smba.trafficmanager.net/teams/",
            "conversation": {"id": "a:1qhNL", "tenantId": "72f988bf-86f1-41af-91ab-2d7cd011db47"},
            "bot": {"id": "28:bot"},
            "channelId": "msteams"
        }"""
        assert(tree(Json.encode(reference)) == tree(json))
        assert(Json.decode[Teams.ConversationReference](json) == Result.succeed(reference))
    }

    "a stored reference whose service URL is not one the module sends to fails the decode" in {
        assert(Json.decode[Teams.ConversationReference](
            """{"serviceUrl":"ftp://x/","conversation":{"id":"a:1"},"channelId":"msteams"}"""
        ).isFailure)
    }

end TeamsConversationTest
