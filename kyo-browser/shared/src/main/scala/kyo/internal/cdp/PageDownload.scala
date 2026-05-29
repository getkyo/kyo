package kyo.internal.cdp

import kyo.*
import kyo.internal.CdpClient

/** Typed wrapper around CDP `Page.setDownloadBehavior`.
  *
  * When `behavior = Allow`, Chrome saves downloads to `downloadPath` and emits `Page.downloadWillBegin` / `Page.downloadProgress` events on
  * the same CDP session. Observation of those events happens via `client.exchange.events`; this wrapper only configures the browser. It
  * does not own the subscription.
  */
private[kyo] object PageDownload:

    enum Behavior derives CanEqual:
        case Allow, Deny, Default

        private[kyo] def wire: String = this match
            case Allow   => "allow"
            case Deny    => "deny"
            case Default => "default"
    end Behavior

    object Behavior:
        /** Serialises as the CDP wire string. Used by [[SetDownloadBehaviorParams]].
          *
          * Unknown wire values surface as a typed `Result.Failure[UnknownVariantException]` via the surrounding `Json.decode` pipeline (the
          * throw is caught by `Result.catching[DecodeException]`).
          *
          * Frame-free given so `Schema.derived` for [[SetDownloadBehaviorParams]] resolves this Schema and serialises the field as the
          * CDP wire string; a `using Frame` variant would silently fall back to the default sealed-trait encoding.
          */
        given Schema[Behavior] = Schema.init[Behavior](
            writeFn = (v, w) => w.string(v.wire),
            readFn = r =>
                r.string() match
                    case "allow"   => Allow
                    case "deny"    => Deny
                    case "default" => Default
                    case other     => throw UnknownVariantException(Seq("Behavior"), other)(using r.frame)
        )
    end Behavior

    /** CDP wire shape for `Page.setDownloadBehavior`. Co-located with [[Behavior]] to keep the wire encoding entirely within the
      * `PageDownload` domain (`CdpParams.scala` does not depend on `kyo.internal.cdp.*`).
      */
    final private[kyo] case class SetDownloadBehaviorParams(
        behavior: Behavior,
        downloadPath: Maybe[String] = Absent,
        eventsEnabled: Maybe[Boolean] = Absent
    ) derives Schema

    /** Configure download behavior for the current browser context.
      *
      * When `behavior = Allow`, downloads land in `downloadPath` and Chrome emits `Page.downloadWillBegin` and `Page.downloadProgress`
      * events. The caller is responsible for subscribing to those events to observe completion.
      */
    def setDownloadBehavior(
        client: CdpClient,
        behavior: Behavior,
        downloadPath: Maybe[String]
    )(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        val params = SetDownloadBehaviorParams(
            behavior = behavior,
            downloadPath = downloadPath,
            eventsEnabled = Present(true)
        )
        client.sendUnit("Page.setDownloadBehavior", params)
    end setDownloadBehavior

    /** CDP wire shape for `Page.downloadWillBegin`. Used by `Browser.parseDownloadEvent` to decode the event's `paramsJson` into a typed
      * `Browser.DownloadEvent.WillBegin`.
      */
    final private[kyo] case class DownloadWillBeginWire(
        guid: String,
        url: String,
        suggestedFilename: String
    ) derives Schema

    /** CDP wire shape for `Page.downloadProgress`. Used by `Browser.parseDownloadEvent` to decode the event's `paramsJson` into a typed
      * `Browser.DownloadEvent.Progress`.
      */
    final private[kyo] case class DownloadProgressWire(
        guid: String,
        totalBytes: Long,
        receivedBytes: Long,
        state: String
    ) derives Schema

    /** Bounded capacity for the per-tab download-event channel exposed via `Browser.onDownload`. Sized to ride out a short consumer stall
      * (slow handler, GC pause) without dropping events; the channel is fan-out, drops the oldest event on overflow.
      */
    private[kyo] val onDownloadChannelCapacity: Int = 64

end PageDownload
