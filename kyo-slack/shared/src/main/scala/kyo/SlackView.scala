package kyo

/** A `views.*` modal / home-tab view model: the typed view `type` (a closed set with a
  * raw-preserving `Unknown` for forward-safety), an optional callback id, the typed Block Kit
  * `blocks`, and the modal controls. `title`/`submit`/`close` are plain-text labels (rendered
  * to `plain_text` objects). A modal that collects input requires `submit` (Slack rejects input
  * blocks without a submit button); `privateMetadata` round-trips to the
  * `view_submission`/`view_closed` payload, and `notifyOnClose` must be set for Slack to deliver
  * a `view_closed` event when the user dismisses the modal.
  */
case class SlackView(
    `type`: SlackView.Type,
    blocks: Chunk[SlackBlock] = Chunk.empty,
    callbackId: Maybe[String] = Absent,
    title: Maybe[String] = Absent,
    submit: Maybe[String] = Absent,
    close: Maybe[String] = Absent,
    privateMetadata: Maybe[String] = Absent,
    notifyOnClose: Boolean = false
) derives CanEqual

object SlackView:

    /** The view surface kind. `Modal` and `Home` are the documented values;
      * `Unknown(raw)` preserves any unmodeled value for forward-safety (the
      * `DisconnectReason` hand-rolled-Schema precedent).
      */
    enum Type derives CanEqual:
        case Modal // wire "modal"
        case Home  // wire "home"
        case Unknown(raw: String)
    end Type

    object Type:
        given Schema[Type] = Schema.stringSchema.transform[Type] {
            case "modal" => Type.Modal
            case "home"  => Type.Home
            case other   => Type.Unknown(other)
        } {
            case Type.Modal        => "modal"
            case Type.Home         => "home"
            case Type.Unknown(raw) => raw
        }
    end Type

end SlackView
