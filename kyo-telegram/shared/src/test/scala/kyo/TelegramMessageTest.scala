package kyo

import Telegram.Entity
import Telegram.Entity.Kind
import Telegram.FileId
import Telegram.FileUniqueId
import Telegram.Media
import Telegram.Message
import Telegram.Message.Caption
import Telegram.Message.Content
import Telegram.MessageId
import TelegramTest.Fixtures.*

class TelegramMessageTest extends kyo.test.Test[Any]:

    private val chat = Telegram.Chat(Telegram.ChatId(5L), Telegram.Chat.Type.Private)
    private val file = Media.Document(FileId("F"), FileUniqueId("U"))

    private def message(content: Content) = Message(MessageId(1), chat, at(1), content)

    "text is a text message's text or a media message's caption" in {
        val caption = Present(Caption("cap"))
        assert(Chunk(
            message(Content.Text("hi", Chunk.empty)),
            message(Content.Document(file, caption)),
            message(Content.Document(file, Absent)),
            message(Content.Location(Media.Location(1, 2)))
        ).map(m => (m.text, m.caption)) == Chunk(
            (Present("hi"), Absent),
            (Present("cap"), caption),
            (Absent, Absent),
            (Absent, Absent)
        ))
    }

    "a private text message with a command, a hashtag and a url" in {
        val json = s"""{"message_id":42,"from":$aliceJson,"chat":$privateChatJson,"date":1735689600,""" +
            """"text":"/start@kyo_bot hello #kyo https://kyo.dev","entities":[{"type":"bot_command","offset":0,"length":14},""" +
            """{"type":"hashtag","offset":21,"length":4},{"type":"url","offset":26,"length":15}]}"""
        val value = Message(
            MessageId(42),
            privateChat,
            at(1735689600),
            Content.Text(
                "/start@kyo_bot hello #kyo https://kyo.dev",
                Chunk(Entity(Kind.BotCommand, 0, 14), Entity(Kind.Hashtag, 21, 4), Entity(Kind.Url, 26, 15))
            ),
            from = Present(alice)
        )
        assert(Json.decode[Message](json) == Result.succeed(value))
        assert(wire(value) == wire(json))
    }

    "a text message with no entities has none, and writes no entities field" in {
        val json  = s"""{"message_id":43,"chat":$privateChatJson,"date":1735689601,"text":"plain"}"""
        val value = Message(MessageId(43), privateChat, at(1735689601), Content.Text("plain", Chunk.empty))
        assert(Json.decode[Message](json) == Result.succeed(value))
        assert(wire(value) == wire(json))
    }

    "a photo is its sizes, smallest first, with its caption and caption entities folded into one Caption" in {
        val json = s"""{"message_id":44,"chat":$forumJson,"date":1735689602,"photo":[""" +
            """{"file_id":"AgACAgQAAxkBAAIBY2","file_unique_id":"AQADsmall","width":90,"height":60,"file_size":1532},""" +
            """{"file_id":"AgACAgQAAxkBAAIBY3","file_unique_id":"AQADlarge","width":1280,"height":853,"file_size":98113}],""" +
            """"caption":"release notes","caption_entities":[{"type":"bold","offset":0,"length":7}]}"""
        val value = Message(
            MessageId(44),
            forum,
            at(1735689602),
            Content.Photo(
                Chunk(
                    Media.PhotoSize(FileId("AgACAgQAAxkBAAIBY2"), FileUniqueId("AQADsmall"), 90, 60, bytes(1532)),
                    Media.PhotoSize(FileId("AgACAgQAAxkBAAIBY3"), FileUniqueId("AQADlarge"), 1280, 853, bytes(98113))
                ),
                Present(Caption("release notes", Chunk(Entity(Kind.Bold, 0, 7))))
            )
        )
        assert(Json.decode[Message](json) == Result.succeed(value))
        assert(wire(value) == wire(json))
    }

    "a document over 2^31 bytes keeps its size, and a thumbnail the module does not model is dropped" in {
        val document = """"document":{"file_id":"BQACAgQAAxkBAAIBZA","file_unique_id":"AgADdump","file_name":"dump.tar",""" +
            """"mime_type":"application/x-tar","file_size":3000000000"""
        val json = s"""{"message_id":45,"chat":$privateChatJson,"date":1735689603,$document,""" +
            """"thumbnail":{"file_id":"AAMCBA","file_unique_id":"AQADthumb","width":320,"height":320}}}"""
        val value = Message(
            MessageId(45),
            privateChat,
            at(1735689603),
            Content.Document(
                Media.Document(
                    FileId("BQACAgQAAxkBAAIBZA"),
                    FileUniqueId("AgADdump"),
                    Present("dump.tar"),
                    Present("application/x-tar"),
                    bytes(3000000000L)
                ),
                Absent
            )
        )
        assert(Json.decode[Message](json) == Result.succeed(value))
        assert(wire(value) == wire(s"""{"message_id":45,"chat":$privateChatJson,"date":1735689603,$document}}"""))
    }

    "a document with a negative file_size is not a document, so the message is Unknown with all of it" in {
        val json =
            s"""{"message_id":46,"chat":$aliceChatJson,"date":1735689604,"document":{"file_id":"x","file_unique_id":"y","file_size":-1}}"""
        assert(Json.decode[Message](json) == Result.succeed(Message(MessageId(46), aliceChat, at(1735689604), Content.Unknown(raw(json)))))
    }

    "audio, video and voice carry their duration in whole seconds" in {
        val audio =
            s"""{"message_id":47,"chat":$aliceChatJson,"date":1735689605,"audio":{"file_id":"CQACAgQ","file_unique_id":"AgADaudio",""" +
                """"duration":215,"performer":"Kyo Band","title":"Effects","mime_type":"audio/mpeg","file_size":3441230}}"""
        val video =
            s"""{"message_id":48,"chat":$aliceChatJson,"date":1735689606,"video":{"file_id":"BAACAgQ","file_unique_id":"AgADvideo",""" +
                """"width":1920,"height":1080,"duration":12,"mime_type":"video/mp4","file_size":5242880}}"""
        val voice =
            s"""{"message_id":49,"chat":$aliceChatJson,"date":1735689607,"voice":{"file_id":"AwACAgQ","file_unique_id":"AgADvoice",""" +
                """"duration":3,"mime_type":"audio/ogg","file_size":9216}}"""
        val values = Chunk(
            Message(
                MessageId(47),
                aliceChat,
                at(1735689605),
                Content.Audio(
                    Media.Audio(
                        FileId("CQACAgQ"),
                        FileUniqueId("AgADaudio"),
                        215.seconds,
                        performer = Present("Kyo Band"),
                        title = Present("Effects"),
                        mimeType = Present("audio/mpeg"),
                        fileSize = bytes(3441230)
                    ),
                    Absent
                )
            ),
            Message(
                MessageId(48),
                aliceChat,
                at(1735689606),
                Content.Video(
                    Media.Video(
                        FileId("BAACAgQ"),
                        FileUniqueId("AgADvideo"),
                        1920,
                        1080,
                        12.seconds,
                        mimeType = Present("video/mp4"),
                        fileSize = bytes(5242880)
                    ),
                    Absent
                )
            ),
            Message(
                MessageId(49),
                aliceChat,
                at(1735689607),
                Content.Voice(
                    Media.Voice(FileId("AwACAgQ"), FileUniqueId("AgADvoice"), 3.seconds, Present("audio/ogg"), bytes(9216)),
                    Absent
                )
            )
        )
        val json = Chunk(audio, video, voice)
        assert(json.map(Json.decode[Message](_)) == values.map(Result.succeed(_)))
        assert(values.map(wire(_)) == json.map(wire(_)))
    }

    "a location drops the live-location fields the module does not model" in {
        val json = s"""{"message_id":50,"chat":$aliceChatJson,"date":1735689608,""" +
            """"location":{"latitude":52.520008,"longitude":13.404954,"horizontal_accuracy":12.5,"live_period":900}}"""
        val value = Message(MessageId(50), aliceChat, at(1735689608), Content.Location(Media.Location(52.520008, 13.404954)))
        assert(Json.decode[Message](json) == Result.succeed(value))
        assert(wire(value) == wire(
            s"""{"message_id":50,"chat":$aliceChatJson,"date":1735689608,"location":{"latitude":52.520008,"longitude":13.404954}}"""
        ))
    }

    "a venue also sets location, and reads as the location" in {
        val json = s"""{"message_id":51,"chat":$aliceChatJson,"date":1735689609,""" +
            """"venue":{"location":{"latitude":52.5,"longitude":13.4},"title":"Office","address":"Main St 1"},""" +
            """"location":{"latitude":52.5,"longitude":13.4}}"""
        assert(Json.decode[Message](json) ==
            Result.succeed(Message(MessageId(51), aliceChat, at(1735689609), Content.Location(Media.Location(52.5, 13.4)))))
    }

    "an animation also sets document, and reads as the document" in {
        val json = s"""{"message_id":52,"chat":$aliceChatJson,"date":1735689610,""" +
            """"animation":{"file_id":"CgACAgQ","file_unique_id":"AgADanim","width":320,"height":240,"duration":2},""" +
            """"document":{"file_id":"CgACAgQ","file_unique_id":"AgADanim","file_name":"loop.mp4","mime_type":"video/mp4"}}"""
        val document = Media.Document(FileId("CgACAgQ"), FileUniqueId("AgADanim"), Present("loop.mp4"), Present("video/mp4"))
        assert(Json.decode[Message](json) ==
            Result.succeed(Message(MessageId(52), aliceChat, at(1735689610), Content.Document(document, Absent))))
    }

    "a live photo also sets photo, and reads as the photo" in {
        val size  = """{"file_id":"AgACAgQ","file_unique_id":"AQADlive","width":90,"height":60}"""
        val json  = s"""{"message_id":54,"chat":$aliceChatJson,"date":1735689613,"live_photo":{"photo":[$size]},"photo":[$size]}"""
        val photo = Media.PhotoSize(FileId("AgACAgQ"), FileUniqueId("AQADlive"), 90, 60)
        assert(Json.decode[Message](json) ==
            Result.succeed(Message(MessageId(54), aliceChat, at(1735689613), Content.Photo(Chunk(photo), Absent))))
    }

    "content the module does not model (a dice) is Unknown with the whole message, written back as it came" in {
        val json  = s"""{"message_id":53,"from":$aliceUserJson,"chat":$aliceChatJson,"date":1735689611,"dice":{"emoji":"🎲","value":4}}"""
        val value = Message(MessageId(53), aliceChat, at(1735689611), Content.Unknown(raw(json)), from = Present(aliceUser))
        assert(Json.decode[Message](json) == Result.succeed(value))
        assert(wire(value) == wire(json))
    }

    "a service message (a group migrated to a supergroup) is Unknown" in {
        val json = s"""{"message_id":1,"from":$aliceUserJson,"chat":$groupJson,"date":1735689612,"migrate_to_chat_id":-1004444444444}"""
        assert(Json.decode[Message](json) ==
            Result.succeed(Message(MessageId(1), group, at(1735689612), Content.Unknown(raw(json)), from = Present(aliceUser))))
    }

    "a reply in a forum topic: the thread, the replied-to message nested one level, an edit date" in {
        val reply = s"""{"message_id":60,"message_thread_id":7,"from":$botJson,"chat":$forumJson,"date":1735689610,"text":"ping"}"""
        val head  = s""""message_id":61,"message_thread_id":7"""
        val rest  =
            s""""from":$aliceUserJson,"chat":$forumJson,"date":1735689620,"edit_date":1735689700,"reply_to_message":$reply,"text":"pong""""
        val json  = s"""{$head,"is_topic_message":true,$rest}"""
        val value = Message(
            MessageId(61),
            forum,
            at(1735689620),
            Content.Text("pong", Chunk.empty),
            from = Present(aliceUser),
            thread = Present(Telegram.MessageThreadId(7)),
            editDate = Present(at(1735689700)),
            replyTo = Present(Message(
                MessageId(60),
                forum,
                at(1735689610),
                Content.Text("ping", Chunk.empty),
                from = Present(bot),
                thread = Present(Telegram.MessageThreadId(7))
            ))
        )
        assert(Json.decode[Message](json) == Result.succeed(value))
        assert(wire(value) == wire(s"""{$head,$rest}"""))
    }

    "a channel post has sender_chat and no from" in {
        val json =
            s"""{"message_id":900,"sender_chat":$channelJson,"chat":$channelJson,"date":1735689630,"author_signature":"Ed","text":"v1.0 is out"}"""
        val value =
            Message(MessageId(900), channel, at(1735689630), Content.Text("v1.0 is out", Chunk.empty), senderChat = Present(channel))
        assert(Json.decode[Message](json) == Result.succeed(value))
        assert(wire(value) ==
            wire(s"""{"message_id":900,"sender_chat":$channelJson,"chat":$channelJson,"date":1735689630,"text":"v1.0 is out"}"""))
    }

    "an ephemeral message has id 0, and fields the module does not model are ignored" in {
        val json = s"""{"message_id":0,"ephemeral_message_id":5,"receiver_user":$aliceUserJson,"chat":$aliceChatJson,"date":1735689640,""" +
            """"text":"only you can see this","effect_id":"5104841245755180586"}"""
        assert(Json.decode[Message](json) ==
            Result.succeed(Message(MessageId(0), aliceChat, at(1735689640), Content.Text("only you can see this", Chunk.empty))))
    }

end TelegramMessageTest
