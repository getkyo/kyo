package kyo.internal

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.*

private[kyo] object HtmlRenderer:

    /** Render a UI tree to HTML with data-kyo-path attributes. */
    def render(ui: UI, path: Seq[String])(using Frame): String < Sync =
        val sb = new StringBuilder
        renderTo(sb, ui, path).andThen(sb.toString)

    /** Wrap body HTML in a full page with inline JS client. */
    def renderPage(title: String, body: String, css: String, sessionId: String, basePath: String): String =
        s"""<!DOCTYPE html>
           |<html>
           |<head>
           |<meta charset="UTF-8">
           |<title>${esc(title)}</title>
           |<style>$baseCss$css</style>
           |</head>
           |<body>$body
           |<script>${clientJs(esc(sessionId), esc(basePath))}</script>
           |</body>
           |</html>""".stripMargin

    /** Wrap body HTML in a complete static HTML document with a configurable head (for SSG/SSR).
      *
      * Unlike `renderPage` (which injects the SSE client JS for server-push), this helper emits a
      * clean static document with an optional module script: the caller's bundle or nothing. The
      * `baseCss` reset is always emitted before `head.css` so framework defaults can be overridden.
      * Called by `UI.runRenderPage`.
      */
    private[kyo] def page(head: UI.PageHead, body: String): String =
        val metaTags = head.meta.map((n, c) => s"""<meta name="${esc(n)}" content="${esc(c)}">""").mkString
        val linkTags = head.links.map((r, h) => s"""<link rel="${esc(r)}" href="${esc(h)}">""").mkString
        val script = head.moduleScript match
            case Present(src) => s"""<script type="module" src="${esc(src)}"></script>"""
            case Absent       => ""
        s"""<!DOCTYPE html>
           |<html lang="en">
           |<head>
           |<meta charset="utf-8">
           |<meta name="viewport" content="width=device-width, initial-scale=1">
           |<title>${esc(head.title)}</title>
           |$metaTags$linkTags
           |<style>$baseCss${head.css}</style>
           |</head>
           |<body>$body</body>
           |$script
           |</html>""".stripMargin
    end page

    // ---- Core rendering ----

    private def renderTo(sb: StringBuilder, ui: UI, path: Seq[String])(using Frame): Unit < Sync =
        ui match
            case dd: Dropdown =>
                renderDropdown(sb, dd, path)
            case elem: Element =>
                val tag  = tagName(elem)
                val void = elem.isInstanceOf[Void]
                w(sb, s"""<$tag data-kyo-path="${pathAttr(path)}"""")
                renderCommonAttrs(sb, elem.attrs)
                renderEventAttr(sb, elem)
                for _ <- renderElementAttrs(sb, elem)
                yield
                    if void then
                        w(sb, " />")
                        elem match
                            case ta: Textarea =>
                                sb.delete(sb.length - 3, sb.length)
                                w(sb, ">")
                                renderTextareaValue(sb, ta).andThen(w(sb, "</textarea>"))
                            case _: Iframe =>
                                // iframe is not a void element: it needs an explicit closing tag.
                                sb.delete(sb.length - 3, sb.length)
                                w(sb, "></iframe>")
                            case _ => ()
                        end match
                    else
                        w(sb, ">")
                        Kyo.foreachDiscard(elem.children.toSeq.zipWithIndex) { (child, i) =>
                            renderTo(sb, child, path :+ i.toString)
                        }.andThen(w(sb, s"</$tag>"))
                    end if
                end for

            case UI.Ast.Text(value) =>
                w(sb, esc(value))

            case Fragment(children) =>
                // Use key for KeyedChild, index for everything else. This matches the path scheme
                // walkStatic uses, so server-side event routing aligns with rendered data-kyo-path.
                Kyo.foreachDiscard(children.toSeq.zipWithIndex) { (child, i) =>
                    val childPath = child match
                        case kc: KeyedChild => path :+ kc.key
                        case _              => path :+ i.toString
                    renderTo(sb, child, childPath)
                }

            case KeyedChild(_, child) =>
                renderTo(sb, child, path)

            case r: Reactive =>
                w(sb, s"""<span data-kyo-path="${pathAttr(path)}" data-kyo-reactive>""")
                for current <- r.signal.current(using r.frame)
                yield renderTo(sb, current, path).andThen(w(sb, "</span>"))

            case fe: Foreach[?] @unchecked =>
                w(sb, s"""<span data-kyo-path="${pathAttr(path)}" data-kyo-reactive>""")
                fe.applyTyped {
                    [T] =>
                        (signal, keyFn, renderFn) =>
                            for items <- signal.current(using fe.frame)
                            yield Kyo.foreachDiscard(items.toSeq.zipWithIndex) { (item, i) =>
                                val key = keyFn match
                                    case Present(f) => f(item)
                                    case Absent     => i.toString
                                renderTo(sb, renderFn(i, item), path :+ key)
                            }.andThen(w(sb, "</span>"))
                            end for
                }
    end renderTo

    private def renderTextareaValue(sb: StringBuilder, ta: Textarea)(using Frame): Unit < Sync =
        ta.value match
            case Present(Bound.Const(s)) => w(sb, esc(s))
            case Present(Bound.Ref(ref)) =>
                for str <- ref.get
                yield w(sb, esc(str))
            case _ => ()

    // ---- Dropdown (custom div-based overlay) ----

    private def renderDropdown(sb: StringBuilder, dd: Dropdown, path: Seq[String])(using Frame): Unit < Sync =
        val baseId = dd.attrs.identifier.getOrElse("")
        // Read current selected value for initial highlight
        val currentValueEffect: Unit < Sync = dd.value match
            case Present(Bound.Ref(ref)) =>
                ref.get.map { currentVal =>
                    renderDropdownWithValue(sb, dd, path, baseId, currentVal)
                }
            case Present(Bound.Const(s)) =>
                renderDropdownWithValue(sb, dd, path, baseId, s)
            case _ =>
                renderDropdownWithValue(sb, dd, path, baseId, "")
        currentValueEffect
    end renderDropdown

    private def renderDropdownWithValue(sb: StringBuilder, dd: Dropdown, path: Seq[String], baseId: String, currentVal: String)(using
        Frame
    ): Unit =
        val pathStr   = pathAttr(path)
        val idAttr    = if baseId.nonEmpty then s""" id="${esc(baseId)}"""" else ""
        val ddAttr    = if baseId.nonEmpty then s""" data-kyo-dropdown="${esc(baseId)}"""" else " data-kyo-dropdown"
        val disAttr   = if dd.disabled.getOrElse(false) then " data-kyo-disabled" else ""
        val hidAttr   = if dd.attrs.hidden.getOrElse(false) then " hidden" else ""
        val tabAttr   = dd.attrs.tabIndex.map(n => s""" tabindex="$n"""").getOrElse("")
        val styleAttr = CssStyleRenderer.render(dd.attrs.uiStyle)
        val styleStr  = if styleAttr.nonEmpty then s""" style="${esc(styleAttr)}"""" else ""
        // Determine initial trigger label
        val firstLabel    = dd.options.headOption.map(_._1).getOrElse("")
        val currentLabel  = dd.options.toSeq.find(_._2 == currentVal).map(_._1).getOrElse(firstLabel)
        val triggerLabel  = esc(if currentLabel.nonEmpty then s"$currentLabel ▾" else "▾")
        val triggerId     = if baseId.nonEmpty then s""" id="${esc(baseId + "-trigger")}"""" else ""
        val optionsId     = if baseId.nonEmpty then s""" id="${esc(baseId + "-options")}"""" else ""
        val triggerDdAttr = if baseId.nonEmpty then s""" data-kyo-dropdown-trigger="${esc(baseId)}"""" else ""
        val optionsDdAttr = if baseId.nonEmpty then s""" data-kyo-dropdown-options="${esc(baseId)}"""" else ""
        // Wrapper div
        w(sb, s"""<div data-kyo-path="$pathStr"$idAttr$ddAttr data-kyo-ev="click,keydown,change"$hidAttr$disAttr$tabAttr$styleStr>""")
        // Trigger button
        w(sb, s"""<button$triggerId type="button"$triggerDdAttr tabindex="0">$triggerLabel</button>""")
        // Options container (hidden by default)
        w(sb, s"""<div$optionsId$optionsDdAttr hidden>""")
        dd.options.toSeq.zipWithIndex.foreach { case ((label, value), idx) =>
            val hlAttr = if value == currentVal && currentVal.nonEmpty then """ data-kyo-dropdown-hl="true"""" else ""
            w(sb, s"""<div data-kyo-dropdown-opt="$idx" data-kyo-dropdown-val="${esc(value)}"$hlAttr>${esc(label)}</div>""")
        }
        w(sb, "</div>")
        w(sb, "</div>")
    end renderDropdownWithValue

    // ---- Tag names ----

    private def tagName(elem: Element): String = elem match
        case _: Div           => "div"
        case _: P             => "p"
        case _: Section       => "section"
        case _: Main          => "main"
        case _: Header        => "header"
        case _: Footer        => "footer"
        case _: Pre           => "pre"
        case _: Code          => "code"
        case _: Ul            => "ul"
        case _: Ol            => "ol"
        case _: Table         => "table"
        case _: H1            => "h1"
        case _: H2            => "h2"
        case _: H3            => "h3"
        case _: H4            => "h4"
        case _: H5            => "h5"
        case _: H6            => "h6"
        case _: Hr            => "hr"
        case _: Br            => "br"
        case _: SpanElement   => "span"
        case _: Nav           => "nav"
        case _: Li            => "li"
        case _: Tr            => "tr"
        case _: Td            => "td"
        case _: Th            => "th"
        case _: Label         => "label"
        case _: Form          => "form"
        case _: Textarea      => "textarea"
        case _: Select        => "select"
        case _: Opt           => "option"
        case _: Button        => "button"
        case _: Anchor        => "a"
        case _: Img           => "img"
        case _: Iframe        => "iframe"
        case _: Input         => "input"
        case _: PasswordInput => "input"
        case _: EmailInput    => "input"
        case _: TelInput      => "input"
        case _: UrlInput      => "input"
        case _: SearchInput   => "input"
        case _: NumberInput   => "input"
        case _: Checkbox      => "input"
        case _: Radio         => "input"
        case _: DateInput     => "input"
        case _: TimeInput     => "input"
        case _: ColorInput    => "input"
        case _: RangeInput    => "input"
        case _: FileInput     => "input"
        case _: HiddenInput   => "input"
        case _: Dropdown      => "div"

    // ---- Common attributes ----

    private def renderCommonAttrs(sb: StringBuilder, attrs: Attrs): Unit =
        attrs.identifier.foreach(id => w(sb, s""" id="${esc(id)}""""))
        if attrs.cssClasses.nonEmpty then w(sb, s""" class="${esc(attrs.cssClasses.mkString(" "))}"""")
        attrs.hidden.foreach(v => if v then w(sb, " hidden"))
        attrs.tabIndex.foreach(n => w(sb, s""" tabindex="$n""""))
        attrs.focusTrap.foreach(v => if v then w(sb, """ data-kyo-focus-trap="1""""))
        attrs.focusGroup.foreach(id => w(sb, s""" data-kyo-focus-group="${esc(id)}""""))
        val css = CssStyleRenderer.render(attrs.uiStyle)
        if css.nonEmpty then w(sb, s""" style="${esc(css)}"""")
        attrs.ariaAttrs.toSeq.sortBy(_._1).foreach { case (name, value) =>
            w(sb, s""" aria-$name="${esc(value)}"""")
        }
        attrs.dataAttrs.toSeq.sortBy(_._1).foreach { case (name, value) =>
            w(sb, s""" data-$name="${esc(value)}"""")
        }
        attrs.jsProps.toSeq.sortBy(_._1).foreach { case (name, value) =>
            w(sb, s""" data-kyo-prop-$name="${esc(value)}"""")
        }
    end renderCommonAttrs

    // ---- Element-specific attributes ----

    private def renderElementAttrs(sb: StringBuilder, elem: Element)(using Frame): Unit < Sync =
        elem match
            case b: Button =>
                w(sb, " type=\"submit\"")
                boolAttr(sb, "disabled", b.disabled)
            case cb: Checkbox =>
                w(sb, " type=\"checkbox\"")
                boolAttr(sb, "disabled", cb.disabled)
                renderCheckedAttr(sb, cb.checked)
            case r: Radio =>
                w(sb, " type=\"radio\"")
                boolAttr(sb, "disabled", r.disabled)
                renderCheckedAttr(sb, r.checked).andThen {
                    r.name.foreach(n => w(sb, s""" name="${esc(n)}""""))
                }
            case i: Input =>
                w(sb, " type=\"text\"");
                renderValueAttr(sb, i.value)
                    .andThen {
                        boolAttr(sb, "disabled", i.disabled); boolAttr(sb, "readonly", i.readOnly);
                        i.placeholder.foreach(p => w(sb, s""" placeholder="${esc(p)}""""))
                    }
            case p: PasswordInput =>
                w(sb, " type=\"password\"");
                renderValueAttr(sb, p.value)
                    .andThen {
                        boolAttr(sb, "disabled", p.disabled); boolAttr(sb, "readonly", p.readOnly);
                        p.placeholder.foreach(p2 => w(sb, s""" placeholder="${esc(p2)}""""))
                    }
            case e: EmailInput =>
                w(sb, " type=\"email\"");
                renderValueAttr(sb, e.value)
                    .andThen {
                        boolAttr(sb, "disabled", e.disabled); boolAttr(sb, "readonly", e.readOnly);
                        e.placeholder.foreach(p => w(sb, s""" placeholder="${esc(p)}""""))
                    }
            case t: TelInput =>
                w(sb, " type=\"tel\"");
                renderValueAttr(sb, t.value)
                    .andThen {
                        boolAttr(sb, "disabled", t.disabled); boolAttr(sb, "readonly", t.readOnly);
                        t.placeholder.foreach(p => w(sb, s""" placeholder="${esc(p)}""""))
                    }
            case u: UrlInput =>
                w(sb, " type=\"url\"");
                renderValueAttr(sb, u.value)
                    .andThen {
                        boolAttr(sb, "disabled", u.disabled); boolAttr(sb, "readonly", u.readOnly);
                        u.placeholder.foreach(p => w(sb, s""" placeholder="${esc(p)}""""))
                    }
            case s: SearchInput =>
                w(sb, " type=\"search\"");
                renderValueAttr(sb, s.value)
                    .andThen {
                        boolAttr(sb, "disabled", s.disabled); boolAttr(sb, "readonly", s.readOnly);
                        s.placeholder.foreach(p => w(sb, s""" placeholder="${esc(p)}""""))
                    }
            case n: NumberInput =>
                w(sb, " type=\"number\"")
                renderValueAttr(sb, n.value).andThen {
                    boolAttr(sb, "disabled", n.disabled); boolAttr(sb, "readonly", n.readOnly)
                    n.placeholder.foreach(p => w(sb, s""" placeholder="${esc(p)}""""))
                    n.min.foreach(v => w(sb, s""" min="${fmtD(v)}""""))
                    n.max.foreach(v => w(sb, s""" max="${fmtD(v)}""""))
                    n.step.foreach(v => w(sb, s""" step="${fmtD(v)}""""))
                }
            case d: DateInput  => w(sb, " type=\"date\""); renderPickerAttrs(sb, d)
            case t: TimeInput  => w(sb, " type=\"time\""); renderPickerAttrs(sb, t)
            case c: ColorInput => w(sb, " type=\"color\""); renderPickerAttrs(sb, c)
            case r: RangeInput =>
                w(sb, " type=\"range\"")
                boolAttr(sb, "disabled", r.disabled)
                val rv: Unit < Sync = r.value match
                    case Present(Bound.Const(d)) => w(sb, s""" value="${fmtD(d)}"""")
                    case Present(Bound.Ref(ref)) =>
                        for d <- ref.get
                        yield w(sb, s""" value="${fmtD(d)}"""")
                    case _ => ()
                rv.andThen {
                    r.min.foreach(v => w(sb, s""" min="${fmtD(v)}""""))
                    r.max.foreach(v => w(sb, s""" max="${fmtD(v)}""""))
                    r.step.foreach(v => w(sb, s""" step="${fmtD(v)}""""))
                }
            case f: FileInput =>
                w(sb, " type=\"file\"")
                boolAttr(sb, "disabled", f.disabled)
                f.accept.foreach { accepts =>
                    val value = accepts.map {
                        case FileAccept.AnyImage             => "image/*"
                        case FileAccept.AnyVideo             => "video/*"
                        case FileAccept.AnyAudio             => "audio/*"
                        case FileAccept.Pdf                  => "application/pdf"
                        case FileAccept.Image(ImageExt.Png)  => ".png"
                        case FileAccept.Image(ImageExt.Jpeg) => ".jpg"
                        case FileAccept.Image(ImageExt.Webp) => ".webp"
                        case FileAccept.Image(ImageExt.Gif)  => ".gif"
                        case FileAccept.Image(ImageExt.Svg)  => ".svg"
                        case FileAccept.Image(ImageExt.Avif) => ".avif"
                        case FileAccept.Extension(ext)       => ext
                        case FileAccept.MediaType(mime)      => mime
                    }.mkString(",")
                    w(sb, s""" accept="${esc(value)}"""")
                }
            case h: HiddenInput =>
                w(sb, " type=\"hidden\"")
                renderValueAttr(sb, h.value)
            case ta: Textarea =>
                boolAttr(sb, "disabled", ta.disabled)
                boolAttr(sb, "readonly", ta.readOnly)
                ta.placeholder.foreach(p => w(sb, s""" placeholder="${esc(p)}""""))
            case sel: Select =>
                boolAttr(sb, "disabled", sel.disabled)
                val selValue: Unit < Sync = sel.value match
                    case Present(Bound.Const(s)) => w(sb, s""" value="${esc(s)}"""")
                    case Present(Bound.Ref(ref)) =>
                        for s <- ref.get
                        yield w(sb, s""" value="${esc(s)}"""")
                    case _ =>
                        // Fall back to first Opt child with selected(true)
                        val selected = sel.children.toSeq.collectFirst {
                            case opt: Opt if opt.selected == Present(true) =>
                                opt.value.getOrElse("")
                        }
                        selected match
                            case Some(v) if v.nonEmpty => w(sb, s""" value="${esc(v)}"""")
                            case _                     => ()
                selValue
            case opt: Opt =>
                opt.value.foreach(v => w(sb, s""" value="${esc(v)}""""))
                boolAttr(sb, "selected", opt.selected)
            case a: Anchor =>
                a.href.foreach { href =>
                    val value = href match
                        case Href.Absolute(url)       => url.full
                        case Href.Path(p)             => p
                        case Href.Fragment(id)        => s"#$id"
                        case Href.External(scheme, v) => s"$scheme:$v"
                    w(sb, s""" href="${esc(value)}"""")
                }
                a.target.foreach { t =>
                    val tv = t match
                        case Target.Self   => "_self"
                        case Target.Blank  => "_blank"
                        case Target.Parent => "_parent"
                        case Target.Top    => "_top"
                    w(sb, s""" target="$tv"""")
                }
            case img: Img =>
                img.src.foreach { src =>
                    val value = src match
                        case ImgSrc.Absolute(url)       => url.full
                        case ImgSrc.Path(p)             => p
                        case ImgSrc.Data(mime, payload) => s"data:$mime;base64,$payload"
                    w(sb, s""" src="${esc(value)}"""")
                }
                img.alt.foreach(a => w(sb, s""" alt="${esc(a)}""""))
            case f: Iframe =>
                f.src.foreach(s => w(sb, s""" src="${esc(s)}""""))
                f.frameTitle.foreach(t => w(sb, s""" title="${esc(t)}""""))
            case td: Td =>
                td.colspan.foreach(n => w(sb, s""" colspan="$n""""))
                td.rowspan.foreach(n => w(sb, s""" rowspan="$n""""))
            case th: Th =>
                th.colspan.foreach(n => w(sb, s""" colspan="$n""""))
                th.rowspan.foreach(n => w(sb, s""" rowspan="$n""""))
            case lbl: Label =>
                lbl.forId.foreach(f => w(sb, s""" for="${esc(f)}""""))
            case _ => ()

    private def boolAttr(sb: StringBuilder, name: String, value: Maybe[Boolean]): Unit =
        value.foreach(v => if v then w(sb, s" $name"))

    private def renderCheckedAttr(sb: StringBuilder, value: Maybe[Bound[Boolean]])(using Frame): Unit < Sync =
        value match
            case Present(Bound.Const(b)) => if b then w(sb, " checked")
            case Present(Bound.Ref(ref)) =>
                for b <- ref.get
                yield if b then w(sb, " checked")
            case _ => ()

    /** Render a value attribute, reading SignalRef if needed. */
    private def renderValueAttr(sb: StringBuilder, value: Maybe[Bound[String]])(using Frame): Unit < Sync =
        value match
            case Present(Bound.Const(s)) => w(sb, s""" value="${esc(s)}"""")
            case Present(Bound.Ref(ref)) =>
                for s <- ref.get
                yield w(sb, s""" value="${esc(s)}"""")
            case _ => ()

    private def renderPickerAttrs(sb: StringBuilder, pi: PickerInput)(using Frame): Unit < Sync =
        boolAttr(sb, "disabled", pi.disabled)
        renderValueAttr(sb, pi.value)

    // ---- Event attributes ----

    private def hasSignalRefValue(value: Maybe[Bound[?]]): Boolean = value match
        case Present(_: Bound.Ref[?]) => true
        case _                        => false

    private def renderEventAttr(sb: StringBuilder, elem: Element): Unit =
        val events = Seq.newBuilder[String]
        val attrs  = elem.attrs
        if attrs.onClick.nonEmpty || attrs.onClickEvt.nonEmpty ||
            attrs.onClickSelf.nonEmpty || attrs.onClickSelfEvt.nonEmpty
        then events += "click"
        if attrs.onFocus.nonEmpty || attrs.onFocusEvt.nonEmpty then events += "focus"
        if attrs.onBlur.nonEmpty || attrs.onBlurEvt.nonEmpty then events += "blur"
        if attrs.onKeyDown.nonEmpty then events += "keydown"
        if attrs.onKeyUp.nonEmpty then events += "keyup"
        // "input" event: when handler is set OR when .value(SignalRef) auto-binding is in use
        elem match
            case ti: TextInput if ti.onInput.nonEmpty || hasSignalRefValue(ti.value) => events += "input"
            case _                                                                   =>
        // "change" event: when handler is set OR when .value/.checked(SignalRef) auto-binding is in use
        elem match
            case ti: TextInput if ti.onChange.nonEmpty || hasSignalRefValue(ti.value)          => events += "change"
            case pi: PickerInput if pi.onChange.nonEmpty || hasSignalRefValue(pi.value)        => events += "change"
            case bi: BooleanInput if bi.onChange.nonEmpty || hasSignalRefValue(bi.checked)     => events += "change"
            case ni: NumberInput if ni.onChangeNumeric.nonEmpty || hasSignalRefValue(ni.value) => events += "change"
            case ri: RangeInput if ri.onChange.nonEmpty || hasSignalRefValue(ri.value)         => events += "change"
            case fi: FileInput if fi.onChange.nonEmpty                                         => events += "change"
            case sel: Select if hasSignalRefValue(sel.value)                                   => events += "change"
            case _                                                                             =>
        end match
        elem match
            case f: Form if f.onSubmit.nonEmpty || f.onSubmitEvt.nonEmpty => events += "submit"
            case _                                                        =>
        val ev = events.result()
        if ev.nonEmpty then w(sb, s""" data-kyo-ev="${ev.mkString(",")}"""")
    end renderEventAttr

    // ---- Helpers ----

    private[kyo] val baseCss =
        """*, *::before, *::after { box-sizing: border-box; }
          |body { font-family: system-ui, -apple-system, sans-serif; margin: 0; padding: 0; }
          |div, section, main, header, footer, form, article, aside, p, ul, ol, pre, code, h1, h2, h3, h4, h5, h6, label { display: flex; flex-direction: column; }
          |nav, li, span, button, a { display: flex; flex-direction: row; align-items: center; }
          |[data-kyo-reactive] { display: contents; }
          |ul, ol { list-style: none; padding: 0; margin: 0; }
          |h1, h2, h3, h4, h5, h6, p { margin: 0; }
          |a { color: inherit; text-decoration: none; }
          |table { border-collapse: collapse; width: 100%; }
          |[hidden] { display: none !important; }
          |""".stripMargin

    private def pathAttr(path: Seq[String]): String = path.mkString(".")

    private def fmtD(v: Double): String = NumberFormat.double(v)

    private inline def w(sb: StringBuilder, s: String): Unit =
        sb.append(s); ()

    private def esc(s: String): String =
        val sb = new StringBuilder(s.length)
        s.foreach {
            case '&'  => sb.append("&amp;")
            case '<'  => sb.append("&lt;")
            case '>'  => sb.append("&gt;")
            case '"'  => sb.append("&quot;")
            case '\'' => sb.append("&#39;")
            case c    => sb.append(c)
        }
        sb.toString
    end esc

    // ---- Client JS ----

    private def clientJs(sessionId: String, basePath: String): String =
        s"""(function(){
           |var base="$basePath";
           |var evtUrl=base+"/_kyo/event";
           |var sse=new EventSource(base+"/_kyo/sse");
           |sse.onmessage=function(e){
           |  var op=JSON.parse(e.data);
           |  if(op.Replace){
           |    var p=op.Replace.path.join(".");
           |    var el=document.querySelector('[data-kyo-path="'+p+'"]');
           |    // Two-way binding echoes each keystroke back as a Replace. An outerHTML replace of the focused field
           |    // resets its caret (email/number inputs do not support selectionStart, so it cannot be restored),
           |    // reversing typed text. So when the focused editable element is inside this Replace, MORPH it in place
           |    // instead: sync attributes (the value attribute keeps mirroring the signal) but assign the live .value
           |    // only on a genuine change. The user's own keystroke echo carries the value the field already holds, so
           |    // .value is left alone and the caret is preserved; a submit-clear or external update carries a different
           |    // value and is applied. The re-render is wrapped in <span data-kyo-reactive>, so the field is inside `el`.
           |    // Only morph the field's OWN value echo: its data-kyo-path equals this Replace's path (the bound input
           |    // and its wrapper span share the path). A larger subtree re-render (a foreach reorder, a `when` swap)
           |    // has a shorter path and merely CONTAINS the focused field; that must fall through to the normal
           |    // replace so focus-follows-item and structural changes still work.
           |    var __ae=document.activeElement;
           |    var __morphed=false;
           |    if(el&&__ae&&(__ae.tagName==="INPUT"||__ae.tagName==="TEXTAREA")&&__ae.getAttribute("data-kyo-path")===p&&el.contains(__ae)){
           |      var __t=document.createElement("template");__t.innerHTML=op.Replace.html.trim();
           |      var __ni=__t.content.querySelector(__ae.tagName.toLowerCase());
           |      if(__ni){
           |        for(var __i=0;__i<__ni.attributes.length;__i++){var __a=__ni.attributes[__i];if(__ae.getAttribute(__a.name)!==__a.value)__ae.setAttribute(__a.name,__a.value);}
           |        var __names=[];for(var __j=0;__j<__ae.attributes.length;__j++)__names.push(__ae.attributes[__j].name);
           |        for(var __k=0;__k<__names.length;__k++){if(!__ni.hasAttribute(__names[__k]))__ae.removeAttribute(__names[__k]);}
           |        var __nv=(__ae.tagName==="TEXTAREA")?__ni.textContent:(__ni.getAttribute("value")||"");
           |        if(__nv!==__ae.value)__ae.value=__nv;
           |        applyJsProps(__ae);
           |        __morphed=true;
           |      }
           |    }
           |    if(el&&!__morphed&&el.outerHTML!==op.Replace.html){
           |      var ae=document.activeElement;
           |      var ap=ae&&ae!==document.body&&ae.getAttribute?ae.getAttribute("data-kyo-path"):null;
           |      var ss=(ae&&typeof ae.selectionStart==='number')?ae.selectionStart:null;
           |      var se=(ae&&typeof ae.selectionEnd==='number')?ae.selectionEnd:null;
           |      el.outerHTML=op.Replace.html;
           |      var nel=document.querySelector('[data-kyo-path="'+p+'"]');if(nel)applyJsProps(nel);
           |      if(ap){var rf=document.querySelector('[data-kyo-path="'+ap+'"]');if(rf&&rf.hasAttribute&&rf.hasAttribute('data-kyo-reactive')){var inner=rf.querySelector('input,textarea,select,[contenteditable]');if(inner)rf=inner;}if(rf){rf.focus();if(ss!==null&&typeof rf.setSelectionRange==='function'){try{rf.setSelectionRange(ss,se);}catch(e){if(e.name!=='InvalidStateError')throw e;}}}}
           |    }
           |  }else if(op.Remove){
           |    var p=op.Remove.path.join(".");
           |    var el=document.querySelector('[data-kyo-path="'+p+'"]');
           |    if(el)el.remove();
           |  }else if(op.InjectCss){
           |    var s=document.createElement("style");
           |    s.textContent=op.InjectCss.css;
           |    document.head.appendChild(s);
           |  }
           |};
           |function fp(el){
           |  while(el&&el!==document.body){
           |    if(el.hasAttribute("data-kyo-path"))return el;
           |    el=el.parentElement;
           |  }
           |  return null;
           |}
           |function he(el,t){
           |  // Walk up from `el` checking each ancestor for the event marker.
           |  // Bubbling events (keydown/keyup/click) are forwarded if ANY ancestor declared the handler.
           |  var n=el;
           |  while(n&&n!==document.body){
           |    var ev=n.getAttribute&&n.getAttribute("data-kyo-ev");
           |    if(ev&&ev.split(",").indexOf(t)>=0)return true;
           |    n=n.parentElement;
           |  }
           |  return false;
           |}
           |// Chain POSTs through a single Promise so the server sees events in the order they fired
           |// on the page. Without this, parallel fetch()es race and (e.g.) a blur on the previous
           |// focused element can land after a focus on the next, since both fire in close succession.
           |window._kyoPostQ = window._kyoPostQ || Promise.resolve();
           |function post(b){
           |  window._kyoPostQ = window._kyoPostQ.then(function(){
           |    var ctrl = new AbortController();
           |    var timer = setTimeout(function(){ ctrl.abort('kyo-ui post timeout'); }, 15000);
           |    return fetch(evtUrl,{method:"POST",headers:{"Content-Type":"text/plain"},body:JSON.stringify(b),signal:ctrl.signal})
           |      .finally(function(){ clearTimeout(timer); });
           |  }).catch(function(e){
           |    window.__kyoPostFailures = (window.__kyoPostFailures || 0) + 1;
           |    window.__kyoPostLastError = String(e);
           |    console.error('kyo-ui event POST failed:', e);
           |  });
           |}
           |function pa(el){
           |  var p=el.getAttribute("data-kyo-path");
           |  return p===""?[]:p.split(".");
           |}
           |// Apply data-kyo-prop-* HTML attributes as JS DOM properties then remove the attr.
           |// Mirrors DomBackend.applyJsPropsSync for the HTTP/JVM rendering path.
           |function applyJsProps(root){
           |  var pfx="data-kyo-prop-";
           |  var els=root.querySelectorAll("[data-kyo-prop-indeterminate],[data-kyo-prop-checked]");
           |  var list=[];
           |  if(root.hasAttribute&&root.getAttribute){
           |    var an=root.getAttributeNames?root.getAttributeNames():[];
           |    for(var i=0;i<an.length;i++){if(an[i].indexOf(pfx)===0){list.push(root);break;}}
           |  }
           |  for(var i=0;i<els.length;i++)list.push(els[i]);
           |  for(var j=0;j<list.length;j++){
           |    var el=list[j];
           |    var names=el.getAttributeNames?el.getAttributeNames():[];
           |    var rem=[];
           |    for(var k=0;k<names.length;k++){
           |      var n=names[k];
           |      if(n.indexOf(pfx)===0){el[n.slice(pfx.length)]=el.getAttribute(n);rem.push(n);}
           |    }
           |    for(var k=0;k<rem.length;k++)el.removeAttribute(rem[k]);
           |  }
           |}
           |applyJsProps(document.body);
           |// Dropdown helpers: close all dropdowns except the given id
           |function kyoCloseDropdown(exceptId){
           |  var all=document.querySelectorAll('[data-kyo-dropdown-options]');
           |  Array.prototype.forEach.call(all,function(el){
           |    var id=el.getAttribute('data-kyo-dropdown-options');
           |    if(id!==exceptId)el.hidden=true;
           |  });
           |}
           |// Build a mouse payload, omitting targetId when absent (null JSON would break Maybe[String] decode).
           |function mkMouse(mods,tid){var m={modifiers:mods};if(tid)m.targetId=tid;return m;}
           |// Build a keyboard payload, omitting targetId when absent.
           |function mkKbd(key,mods,tid){var k={key:key,modifiers:mods};if(tid)k.targetId=tid;return k;}
           |function handle(e){
           |  var el=fp(e.target);
           |  if(!el)return;
           |  var p=pa(el),t=e.type;
           |  if(t==="click"){
           |    // Dropdown trigger click: open/close the option list.
           |    // Skip isTrusted=false synthetic clicks (e.g. from runSpaceClickSynthesis after Space keydown).
           |    if(e.isTrusted!==false&&e.target&&e.target.getAttribute('data-kyo-dropdown-trigger')){
           |      var did=e.target.getAttribute('data-kyo-dropdown-trigger');
           |      var opts=document.querySelector('[data-kyo-dropdown-options="'+did+'"]');
           |      if(opts){
           |        var opening=opts.hidden;
           |        kyoCloseDropdown(opening?did:null);
           |        opts.hidden=!opening;
           |        if(!opts.hidden){
           |          var hlEl=opts.querySelector('[data-kyo-dropdown-hl]');
           |          if(!hlEl){var first=opts.querySelector('[data-kyo-dropdown-opt]');if(first)first.setAttribute('data-kyo-dropdown-hl','true');}
           |        }
           |      }
           |      return;
           |    }
           |    // Dropdown option click: confirm selection
           |    if(e.target&&e.target.getAttribute('data-kyo-dropdown-val')!==null){
           |      var val=e.target.getAttribute('data-kyo-dropdown-val');
           |      var wrap=e.target.closest('[data-kyo-dropdown]');
           |      if(wrap){
           |        var dOpts=document.querySelector('[data-kyo-dropdown-options="'+wrap.getAttribute('data-kyo-dropdown')+'"]');
           |        if(dOpts)dOpts.hidden=true;
           |        var wp=pa(wrap);
           |        post({Change:{path:wp,value:val}});
           |      }
           |      return;
           |    }
           |    var mid=e.target&&e.target.id?e.target.id:null;if(el.tagName&&el.tagName.toLowerCase()==='a')e.preventDefault();post({Click:{path:p,mouse:mkMouse({ctrl:e.ctrlKey,alt:e.altKey,shift:e.shiftKey,meta:e.metaKey},mid)}});window._kyoClickSubmit=true;setTimeout(function(){window._kyoClickSubmit=false},0);
           |  }
           |  else if(t==="input"&&he(el,"input"))post({Input:{path:p,value:e.target.value}});
           |  else if(t==="change"&&he(el,"change")){
           |    var tgt=e.target,typ=tgt.type;
           |    if(typ==="checkbox"||typ==="radio")post({ChangeChecked:{path:p,checked:tgt.checked}});
           |    else if(typ==="number"||typ==="range")post({ChangeNumeric:{path:p,value:parseFloat(tgt.value)}});
           |    else post({Change:{path:p,value:tgt.value}});
           |  }else if(t==="submit"){e.preventDefault();if(!window._kyoClickSubmit&&he(el,"submit")){var smid=e.target&&e.target.id?e.target.id:null;post({Submit:{path:p,mouse:mkMouse({ctrl:false,alt:false,shift:false,meta:false},smid)}});}}
           |  else if(t==="keydown"){
           |    // Focus-trap: when Tab is pressed inside a [data-kyo-focus-trap="1"] container,
           |    // wrap focus within the trap's focusable children instead of escaping to the page.
           |    // Escape falls through so the element's onKeyDown handler can close the modal.
           |    if(e.key==="Tab"&&e.target){
           |      var trap=e.target.closest('[data-kyo-focus-trap="1"]');
           |      if(trap){
           |        var focusables=Array.prototype.filter.call(
           |          trap.querySelectorAll('a[href],button:not([disabled]),input:not([disabled]):not([type=hidden]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'),
           |          function(fe){return !fe.hidden&&fe.offsetParent!==null;}
           |        );
           |        if(focusables.length>0){
           |          var ci=focusables.indexOf(document.activeElement);
           |          var dir=e.shiftKey?-1:1;
           |          var ni=((ci<0?0:ci)+dir+focusables.length)%focusables.length;
           |          var next=focusables[ni];
           |          if(next){
           |            // Reposition data-kyo-tab-prev so runTabFocusAdvance (Browser.press post-shim)
           |            // also lands on next rather than escaping the trap.
           |            var allF=Array.prototype.filter.call(
           |              document.querySelectorAll('a[href],button:not([disabled]),input:not([disabled]):not([type=hidden]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'),
           |              function(fe){return !fe.hidden&&fe.offsetParent!==null;}
           |            );
           |            var allPos=allF.filter(function(fe){return fe.tabIndex>0;}).sort(function(a,b){return a.tabIndex-b.tabIndex;});
           |            var allNat=allF.filter(function(fe){return fe.tabIndex<=0;});
           |            var allOrd=allPos.concat(allNat);
           |            var nextIdx=allOrd.indexOf(next);
           |            var oldMark=document.querySelector('[data-kyo-tab-prev="1"]');
           |            if(oldMark)oldMark.removeAttribute('data-kyo-tab-prev');
           |            if(nextIdx>=0){
           |              var preNext=allOrd[((nextIdx-dir+allOrd.length)%allOrd.length)];
           |              if(preNext)preNext.setAttribute('data-kyo-tab-prev','1');
           |            }
           |            next.focus();
           |            e.preventDefault();
           |            e.stopPropagation();
           |            return;
           |          }
           |        }
           |      }
           |    }
           |    // ArrowUp/Down on a focused <input type=number>: call stepUp()/stepDown() (respects step/min/max
           |    // natively), dispatch input + change events so kyo-ui's ChangeNumeric path fires, then
           |    // preventDefault to suppress the browser's native increment (which would otherwise double-step).
           |    // No return; the keydown post below still fires so onKeyDown handlers see ArrowUp/Down.
           |    if((e.key==="ArrowUp"||e.key==="ArrowDown")&&e.target&&e.target.tagName==="INPUT"&&e.target.type==="number"){
           |      if(!e.target.disabled&&!e.target.readOnly){
           |        if(e.key==="ArrowUp")e.target.stepUp();else e.target.stepDown();
           |        e.target.dispatchEvent(new Event("input",{bubbles:true}));
           |        e.target.dispatchEvent(new Event("change",{bubbles:true}));
           |        e.preventDefault();
           |      }
           |    }
           |    // Enter on a focused <select> would otherwise trigger the form's default submit;
           |    // kyo-ui treats Enter on Select as a dropdown interaction (see ReactiveUI.dispatchToElement),
           |    // so we suppress the browser default to keep TUI/browser parity.
           |    if(e.key==="Enter"&&e.target&&e.target.tagName==="SELECT")e.preventDefault();
           |    // Enter on a focused <input type=checkbox|radio>: HTML spec only activates these via Space,
           |    // not Enter. kyo-ui synthesizes Enter activation via click() for TUI/browser parity.
           |    // click() fires the native change event which the existing change handler picks up as
           |    // ChangeChecked. preventDefault stops any form submission. No return; keydown post fires.
           |    if(e.key==="Enter"&&e.target&&e.target.tagName==="INPUT"&&
           |       (e.target.type==="checkbox"||e.target.type==="radio")){
           |      if(!e.target.disabled){e.target.click();e.preventDefault();}
           |    }
           |    // Focus-group: ArrowLeft/Right cycles among siblings sharing data-kyo-focus-group.
           |    // preventDefault stops browser's native horizontal scroll-on-arrow.
           |    // No return; keydown post still fires so onKeyDown sees the event.
           |    if((e.key==="ArrowLeft"||e.key==="ArrowRight")&&e.target){
           |      var grp=e.target.getAttribute&&e.target.getAttribute("data-kyo-focus-group");
           |      if(grp){
           |        var peers=Array.prototype.slice.call(document.querySelectorAll('[data-kyo-focus-group="'+grp+'"]'));
           |        peers=peers.filter(function(pe){return !pe.disabled&&!pe.hidden&&pe.offsetParent!==null;});
           |        if(peers.length>1){
           |          var i=peers.indexOf(e.target);
           |          var dir=e.key==="ArrowRight"?1:-1;
           |          var nx=peers[((i<0?0:i)+dir+peers.length)%peers.length];
           |          if(nx&&nx!==e.target){nx.focus();e.preventDefault();}
           |        }
           |      }
           |    }
           |    // Custom dropdown (div-based): Space opens, ArrowDown/Up navigate, Enter confirms, Escape/Tab closes.
           |    // Type-ahead: single printable char jumps to next matching option.
           |    // Must run BEFORE the keydown post so early returns suppress server dispatch.
           |    var ddWrap=e.target&&e.target.closest('[data-kyo-dropdown]');
           |    if(ddWrap){
           |      var did2=ddWrap.getAttribute('data-kyo-dropdown');
           |      var opts2=did2?document.querySelector('[data-kyo-dropdown-options="'+did2+'"]'):null;
           |      var isOpen=opts2&&!opts2.hidden;
           |      var isSpaceKey=(e.key===' '||e.key==='Space'||e.keyCode===32||e.which===32);
           |      if(isSpaceKey&&!isOpen){
           |        kyoCloseDropdown(did2);opts2.hidden=false;
           |        var first2=opts2.querySelector('[data-kyo-dropdown-opt]');if(first2)first2.setAttribute('data-kyo-dropdown-hl','true');
           |        e.preventDefault();return;
           |      }
           |      if(isOpen){
           |        var items=Array.prototype.slice.call(opts2.querySelectorAll('[data-kyo-dropdown-opt]'));
           |        var hlEl2=opts2.querySelector('[data-kyo-dropdown-hl]');
           |        var hi=hlEl2?items.indexOf(hlEl2):0;
           |        if(e.key==='ArrowDown'){
           |          if(hlEl2)hlEl2.removeAttribute('data-kyo-dropdown-hl');
           |          items[(hi+1)%items.length].setAttribute('data-kyo-dropdown-hl','true');
           |          e.preventDefault();return;
           |        }
           |        if(e.key==='ArrowUp'){
           |          if(hlEl2)hlEl2.removeAttribute('data-kyo-dropdown-hl');
           |          items[((hi-1)+items.length)%items.length].setAttribute('data-kyo-dropdown-hl','true');
           |          e.preventDefault();return;
           |        }
           |        if(e.key==='Enter'){
           |          if(hlEl2){
           |            var val2=hlEl2.getAttribute('data-kyo-dropdown-val');
           |            opts2.hidden=true;
           |            post({Change:{path:pa(ddWrap),value:val2}});
           |          }
           |          e.preventDefault();return;
           |        }
           |        if(e.key==='Escape'){opts2.hidden=true;e.preventDefault();return;}
           |        if(e.key==='Tab'){opts2.hidden=true;}
           |        if(e.key.length===1){
           |          var ch=e.key.toLowerCase();
           |          var startIdx=(hi+1)%items.length;
           |          var found=null;
           |          for(var ii=0;ii<items.length&&!found;ii++){
           |            var candidate=items[(startIdx+ii)%items.length];
           |            if(candidate.textContent.trim().toLowerCase().charAt(0)===ch)found=candidate;
           |          }
           |          if(found){if(hlEl2)hlEl2.removeAttribute('data-kyo-dropdown-hl');found.setAttribute('data-kyo-dropdown-hl','true');}
           |          e.preventDefault();return;
           |        }
           |      }
           |      // Dropdown closed: suppress Enter (avoid form submit) and Space is handled above
           |      if((e.key==='Enter'||isSpaceKey)&&!isOpen&&opts2){e.preventDefault();return;}
           |    }
           |    if(he(el,"keydown")){var ktid=e.target&&e.target.id?e.target.id:null;post({KeyDown:{path:p,keyboard:mkKbd(e.key,{ctrl:e.ctrlKey,alt:e.altKey,shift:e.shiftKey,meta:e.metaKey},ktid)}});}
           |  }
           |  else if(t==="keyup"&&he(el,"keyup")){var kutid=e.target&&e.target.id?e.target.id:null;post({KeyUp:{path:p,keyboard:mkKbd(e.key,{ctrl:e.ctrlKey,alt:e.altKey,shift:e.shiftKey,meta:e.metaKey},kutid)}});}
           |  else if(t==="focus"&&he(el,"focus")){var ftid=e.target&&e.target.id?e.target.id:null;post({Focus:{path:p,mouse:mkMouse({ctrl:false,alt:false,shift:false,meta:false},ftid)}});}
           |  else if(t==="blur"&&he(el,"blur")){var btid=e.target&&e.target.id?e.target.id:null;post({Blur:{path:p,mouse:mkMouse({ctrl:false,alt:false,shift:false,meta:false},btid)}});}
           |}
           |["click","input","change","submit","keydown","keyup","focus","blur"].forEach(function(t){
           |  document.body.addEventListener(t,handle,true);
           |});
           |})();""".stripMargin

end HtmlRenderer
