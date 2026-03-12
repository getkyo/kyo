package kyo.internal.tui2.widget

import kyo.*
import kyo.Maybe.*
import kyo.discard
import kyo.internal.tui2.*
import scala.annotation.tailrec

/** Image widget — renders via terminal image protocols (iTerm2, Kitty) or falls back to alt text. */
private[kyo] object ImgW:

    def render(img: UI.Img, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, canvas: Canvas, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Unit =
        val protocol = PlatformCmd.imageProtocol
        val rendered =
            if protocol != PlatformCmd.NoImage then
                val path = ValueResolver.resolveStringSignal(img.src, ctx.signals)
                if path.nonEmpty then
                    // All Maybe ops (orElse, flatMap, map) are inline — zero allocation
                    val data = ctx.imageCache.get(path, protocol, cw, ch).orElse {
                        ctx.imageCache.loadRaw(path).flatMap { bytes =>
                            val enc = protocol match
                                case PlatformCmd.ITermImg => encodeITerm2(bytes, cx, cy, cw, ch)
                                case PlatformCmd.KittyImg => encodeKitty(bytes, cx, cy, cw, ch)
                                case _                    => Absent
                            enc.map { d =>
                                ctx.imageCache.putEncoded(path, protocol, cw, ch, d)
                                d
                            }
                        }
                    }
                    if data.nonEmpty then
                        canvas.rawSequences.add(data.get)
                        true
                    else false
                    end if
                else false
                end if
            else false

        // Fallback to alt text
        if !rendered then
            img.alt.foreach { alt =>
                val style = CellStyle(rs.fg, rs.bg, false, true, false, false, false)
                discard(canvas.drawString(cx, cy, cw, alt, 0, style))
            }
        end if
    end render

    /** Encode image bytes as iTerm2 inline image escape sequence. */
    private def encodeITerm2(bytes: Array[Byte], cx: Int, cy: Int, cw: Int, ch: Int): Maybe[Array[Byte]] =
        val b64    = java.util.Base64.getEncoder.encodeToString(bytes)
        val cursor = s"\u001b[${cy + 1};${cx + 1}H"
        val seq    = s"${cursor}\u001b]1337;File=inline=1;width=${cw}c;height=${ch}c;preserveAspectRatio=1:${b64}\u0007"
        Present(seq.getBytes)
    end encodeITerm2

    /** Encode image bytes as Kitty graphics protocol escape sequence. */
    private def encodeKitty(bytes: Array[Byte], cx: Int, cy: Int, cw: Int, ch: Int): Maybe[Array[Byte]] =
        val b64       = java.util.Base64.getEncoder.encodeToString(bytes)
        val cursor    = s"\u001b[${cy + 1};${cx + 1}H"
        val chunkSize = 4096
        if b64.length <= chunkSize then
            val seq = s"${cursor}\u001b_Ga=T,f=100,c=${cw},r=${ch};${b64}\u001b\\"
            Present(seq.getBytes)
        else
            val sb = new java.lang.StringBuilder(b64.length + 256)
            sb.append(cursor)
            @tailrec def chunks(offset: Int): Unit =
                val end  = math.min(offset + chunkSize, b64.length)
                val last = end >= b64.length
                val m    = if last then 0 else 1
                if offset == 0 then
                    sb.append(s"\u001b_Ga=T,f=100,c=${cw},r=${ch},m=${m};")
                else
                    sb.append(s"\u001b_Gm=${m};")
                end if
                sb.append(b64, offset, end)
                sb.append("\u001b\\")
                if !last then chunks(end)
            end chunks
            chunks(0)
            Present(sb.toString.getBytes)
        end if
    end encodeKitty

end ImgW
