package kyo

/** A `views.*` modal / home-tab view model: the typed view `type` (a closed set
  * with a raw-preserving `Unknown` for forward-safety), an optional callback id,
  * the Block Kit body as raw JSON, and an optional title block as raw JSON.
  */
case class SlackView(
    `type`: SlackView.Type,
    callbackId: Maybe[String] = Absent,
    blocksJson: String,
    titleJson: Maybe[String] = Absent
) derives Schema, CanEqual

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
