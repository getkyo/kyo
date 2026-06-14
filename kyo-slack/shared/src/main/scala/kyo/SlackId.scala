package kyo

/** Typed Slack identifiers: opaque types over `String` that keep the distinct id
  * kinds from being silently interchanged. Each carries a `Schema` (over the
  * string codec via `Schema.stringSchema.transform`) so it decodes from and
  * encodes to a JSON string verbatim, and a `CanEqual` for safe comparison.
  *
  * A `ChannelId` is not assignable where a `TriggerId` (or `UserId`) is required;
  * the compiler rejects a mismatched id at a Web API call site.
  */
object SlackId:

    opaque type ChannelId  = String
    opaque type UserId     = String
    opaque type TeamId     = String
    opaque type AppId      = String
    opaque type TriggerId  = String
    opaque type EnvelopeId = String
    opaque type ViewId     = String
    opaque type BotId      = String
    opaque type ActionId   = String
    opaque type BlockId    = String

    object ChannelId:
        def apply(s: String): ChannelId             = s
        extension (id: ChannelId) def value: String = id
        given Schema[ChannelId]                     = Schema.stringSchema.transform[ChannelId](apply)(_.value)
        given CanEqual[ChannelId, ChannelId]        = CanEqual.derived
    end ChannelId

    object UserId:
        def apply(s: String): UserId             = s
        extension (id: UserId) def value: String = id
        given Schema[UserId]                     = Schema.stringSchema.transform[UserId](apply)(_.value)
        given CanEqual[UserId, UserId]           = CanEqual.derived
    end UserId

    object TeamId:
        def apply(s: String): TeamId             = s
        extension (id: TeamId) def value: String = id
        given Schema[TeamId]                     = Schema.stringSchema.transform[TeamId](apply)(_.value)
        given CanEqual[TeamId, TeamId]           = CanEqual.derived
    end TeamId

    object AppId:
        def apply(s: String): AppId             = s
        extension (id: AppId) def value: String = id
        given Schema[AppId]                     = Schema.stringSchema.transform[AppId](apply)(_.value)
        given CanEqual[AppId, AppId]            = CanEqual.derived
    end AppId

    object TriggerId:
        def apply(s: String): TriggerId             = s
        extension (id: TriggerId) def value: String = id
        given Schema[TriggerId]                     = Schema.stringSchema.transform[TriggerId](apply)(_.value)
        given CanEqual[TriggerId, TriggerId]        = CanEqual.derived
    end TriggerId

    object EnvelopeId:
        def apply(s: String): EnvelopeId             = s
        extension (id: EnvelopeId) def value: String = id
        given Schema[EnvelopeId]                     = Schema.stringSchema.transform[EnvelopeId](apply)(_.value)
        given CanEqual[EnvelopeId, EnvelopeId]       = CanEqual.derived
    end EnvelopeId

    object ViewId:
        def apply(s: String): ViewId             = s
        extension (id: ViewId) def value: String = id
        given Schema[ViewId]                     = Schema.stringSchema.transform[ViewId](apply)(_.value)
        given CanEqual[ViewId, ViewId]           = CanEqual.derived
    end ViewId

    object BotId:
        def apply(s: String): BotId             = s
        extension (id: BotId) def value: String = id
        given Schema[BotId]                     = Schema.stringSchema.transform[BotId](apply)(_.value)
        given CanEqual[BotId, BotId]            = CanEqual.derived
    end BotId

    object ActionId:
        def apply(s: String): ActionId             = s
        extension (id: ActionId) def value: String = id
        given Schema[ActionId]                     = Schema.stringSchema.transform[ActionId](apply)(_.value)
        given CanEqual[ActionId, ActionId]         = CanEqual.derived
    end ActionId

    object BlockId:
        def apply(s: String): BlockId             = s
        extension (id: BlockId) def value: String = id
        given Schema[BlockId]                     = Schema.stringSchema.transform[BlockId](apply)(_.value)
        given CanEqual[BlockId, BlockId]          = CanEqual.derived
    end BlockId

end SlackId

/** The opaque Slack message timestamp id, used pervasively in event and message
  * signatures. A `SlackTs` is a `String` underneath, carries a `Schema` over the
  * string codec, and a `CanEqual` for safe comparison; it is not assignable where a
  * channel/user/other id is required.
  */
opaque type SlackTs = String

object SlackTs:
    def apply(s: String): SlackTs             = s
    extension (id: SlackTs) def value: String = id
    given Schema[SlackTs]                     = Schema.stringSchema.transform[SlackTs](apply)(_.value)
    given CanEqual[SlackTs, SlackTs]          = CanEqual.derived
end SlackTs
