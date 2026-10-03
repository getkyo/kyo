package kyo

class TelegramChatActionTest extends kyo.test.Test[Any]:

    "each chat action is the name sendChatAction takes" in {
        import Telegram.ChatAction.*
        val actions = Chunk(
            Typing,
            UploadPhoto,
            RecordVideo,
            UploadVideo,
            RecordVoice,
            UploadVoice,
            UploadDocument,
            ChooseSticker,
            FindLocation,
            RecordVideoNote,
            UploadVideoNote
        )
        val names = Chunk(
            "typing",
            "upload_photo",
            "record_video",
            "upload_video",
            "record_voice",
            "upload_voice",
            "upload_document",
            "choose_sticker",
            "find_location",
            "record_video_note",
            "upload_video_note"
        )
        assert(actions.map(Json.encode(_)) == names.map(n => s"\"$n\""))
        assert(names.map(n => Json.decode[Telegram.ChatAction](s"\"$n\"")) == actions.map(Result.succeed(_)))
    }

end TelegramChatActionTest
