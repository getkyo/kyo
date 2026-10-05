package kyo

/** The `response_url` Slack sends with a slash command, a block action and a message action: a
  * short-lived webhook through which the app answers that interaction.
  *
  * The whole URL is the credential. Its path authorizes posting to the conversation, so anyone who
  * holds it can post there, up to five times within thirty minutes of the payload. It is therefore
  * held like a token: `toString` renders `SlackResponseUrl(<redacted>)`, `value` is the only way to
  * read the text, values compare by that text, and there is no `Schema`, so no command or interaction
  * that holds one can be printed or serialized with it.
  *
  * It is not a [[kyo.SlackToken]]: it authenticates nothing and never rides a header.
  *
  * Construction does not validate the text. Slack's answer to a URL that expired or was used up is
  * Slack's to give, and a GovSlack workspace sends a URL on another domain, so a host check would
  * refuse valid values. `apply` is public, so a caller that keeps the URL past its handler can store
  * `value` and rebuild the type.
  */
final class SlackResponseUrl private (val value: String):
    override def equals(other: Any): Boolean =
        other match
            case that: SlackResponseUrl => value == that.value
            case _                      => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = "SlackResponseUrl(<redacted>)"
end SlackResponseUrl

object SlackResponseUrl:
    def apply(value: String): SlackResponseUrl         = new SlackResponseUrl(value)
    given CanEqual[SlackResponseUrl, SlackResponseUrl] = CanEqual.derived
end SlackResponseUrl
