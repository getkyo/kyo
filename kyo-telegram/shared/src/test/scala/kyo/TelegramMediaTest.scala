package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramMediaTest extends kyo.test.Test[Any]:
    import Telegram.Media.*

    private val id     = Telegram.FileId("BQACAgIAAxkBAAIB")
    private val unique = Telegram.FileUniqueId("AgADzQ0AAqS")
    private val file   = """"file_id":"BQACAgIAAxkBAAIB","file_unique_id":"AgADzQ0AAqS""""

    "a PhotoSize" in {
        val json = s"""{$file,"width":90,"height":67,"file_size":1234}"""
        val size = PhotoSize(id, unique, 90, 67, Present(1234.bytes))
        assert(Json.decode[PhotoSize](json) == Result.succeed(size))
        assert(wire(size) == wire(json))
    }

    "a Document" in {
        val json = s"""{$file,"file_name":"report.pdf","mime_type":"application/pdf","file_size":20480}"""
        val doc  = Document(id, unique, Present("report.pdf"), Present("application/pdf"), Present(20480.bytes))
        assert(Json.decode[Document](json) == Result.succeed(doc))
        assert(wire(doc) == wire(json))
    }

    "an Audio, its duration in seconds" in {
        val json =
            s"""{$file,"duration":215,"performer":"Band","title":"Song","file_name":"song.mp3","mime_type":"audio/mpeg","file_size":3400000}"""
        val audio = Audio(
            id,
            unique,
            215.seconds,
            Present("Band"),
            Present("Song"),
            Present("song.mp3"),
            Present("audio/mpeg"),
            Present(3400000.bytes)
        )
        assert(Json.decode[Audio](json) == Result.succeed(audio))
        assert(wire(audio) == wire(json))
    }

    "a Video" in {
        val json = s"""{$file,"width":1280,"height":720,"duration":42,"file_name":"clip.mp4","mime_type":"video/mp4","file_size":5000000}"""
        val video = Video(id, unique, 1280, 720, 42.seconds, Present("clip.mp4"), Present("video/mp4"), Present(5000000.bytes))
        assert(Json.decode[Video](json) == Result.succeed(video))
        assert(wire(video) == wire(json))
    }

    "a Voice" in {
        val json  = s"""{$file,"duration":7,"mime_type":"audio/ogg","file_size":12000}"""
        val voice = Voice(id, unique, 7.seconds, Present("audio/ogg"), Present(12000.bytes))
        assert(Json.decode[Voice](json) == Result.succeed(voice))
        assert(wire(voice) == wire(json))
    }

    "a Location" in {
        val json = """{"latitude":51.5007,"longitude":-0.1246}"""
        assert(Json.decode[Location](json) == Result.succeed(Location(51.5007, -0.1246)))
        assert(wire(Location(51.5007, -0.1246)) == wire(json))
    }

    "a size Telegram omits is absent, and a negative one fails" in {
        val json = s"""{$file,"width":1,"height":1}"""
        assert(Json.decode[PhotoSize](json) == Result.succeed(PhotoSize(id, unique, 1, 1)))
        assert(wire(PhotoSize(id, unique, 1, 1)) == wire(json))
        assert(Json.decode[PhotoSize](s"""{$file,"width":1,"height":1,"file_size":-5}""").isFailure)
    }

end TelegramMediaTest
