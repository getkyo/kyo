package kyo.internal

import kyo.*

/** The single injected idempotent in-page DISCOVER helper. Installed once per page (gated by `window.__kyoDiscoverInstalled`, like the
  * console-capture install), it exposes `window.__kyoDiscoverProbe(el)` returning the `ElementInfo` wire shape for one element and
  * `window.__kyoUniqueSelector(el)` building a stable unique CSS path. `element` / `elements` / `elementAt` call it via `Runtime.evaluate`,
  * settled through `SettleRead`.
  */
private[kyo] object DiscoverJs:

    /** Wire shape for one probed element, decoded into `Browser.ElementInfo`. All fields have defaults so missing JSON keys decode to
      * `Absent` / zero / false without error.
      */
    final private[kyo] case class ElementInfoWire(
        selector: String = "",
        tag: String = "",
        id: Maybe[String] = Absent,
        classes: Chunk[String] = Chunk.empty,
        text: Maybe[String] = Absent,
        x: Double = 0.0,
        y: Double = 0.0,
        width: Double = 0.0,
        height: Double = 0.0,
        visible: Boolean = false,
        inViewport: Boolean = false,
        topmost: Boolean = false,
        interactive: Boolean = false,
        role: Maybe[String] = Absent
    ) derives Schema

    /** Idempotent install of `window.__kyoDiscoverProbe` and `window.__kyoUniqueSelector`. `text` is trimmed and truncated to 200 UTF-16
      * units without splitting a surrogate pair; lone surrogates become U+FFFD. Empty text maps to null.
      * `interactive` combines tag/role/tabindex/onclick, editing-host and pointer-cursor hints, suppressing inherited
      * decorations and wrappers with no visible text outside their controls. `topmost` is a single rect-center `elementFromPoint`
      * resolving to `el` or a descendant. `inViewport` is shared with the VERIFY `inViewport` JS.
      */
    private[kyo] val installJs: String =
        """(() => {
            if (window.__kyoDiscoverInstalled) return 'shared';
            window.__kyoDiscoverInstalled = true;
            const INTERACTIVE_TAGS = new Set(['A','BUTTON','INPUT','SELECT','TEXTAREA','OPTION','LABEL']);
            const INTERACTIVE_ROLES = new Set(['button','link','checkbox','radio','tab','menuitem','textbox']);
            const isEditingHost = (el) => el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable);
            const isControl = (n) => INTERACTIVE_TAGS.has(n.localName.toUpperCase()) || n.hasAttribute('onclick') ||
                INTERACTIVE_ROLES.has(n.getAttribute('role')) || isEditingHost(n);
            const hasPointerCursor = (n) => getComputedStyle(n).cursor === 'pointer';
            const wrapsControls = (el) => {
                let control = false;
                const range = document.createRange();
                const texts = document.createTreeWalker(el, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
                    acceptNode: n => {
                        if (n.nodeType === Node.ELEMENT_NODE) {
                            if (isControl(n)) { control = true; return NodeFilter.FILTER_REJECT; }
                            return NodeFilter.FILTER_SKIP;
                        }
                        if (!n.textContent.trim()) return NodeFilter.FILTER_SKIP;
                        const parent = n.parentElement, style = getComputedStyle(parent);
                        if (style.visibility === 'hidden' || style.visibility === 'collapse') return NodeFilter.FILTER_SKIP;
                        for (let p = parent; p; p = p.parentElement) {
                            const css = p === parent ? style : getComputedStyle(p);
                            if (css.display === 'none' || (css.display !== 'contents' && (css.opacity === '0' || css.contentVisibility === 'hidden')))
                                return NodeFilter.FILTER_SKIP;
                        }
                        range.selectNodeContents(n);
                        const box = range.getBoundingClientRect();
                        return box.width > 0 && box.height > 0 ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
                    }
                });
                // A control's subtree is skipped, so the first accepted text belongs to the wrapper itself.
                return !texts.nextNode() && control;
            };
            const isPointerTarget = (el) => {
                if (!hasPointerCursor(el)) return false;
                // Editable contents share their host's target; non-editable islands may contain independent controls.
                for (let p = el.parentElement; p; p = p.parentElement)
                    if (isControl(p) && (!isEditingHost(p) || el.isContentEditable)) return false;
                const named = (el.getAttribute('aria-label') || '').trim() !== '' || (el.getAttribute('title') || '').trim() !== '';
                return (!el.parentElement || !hasPointerCursor(el.parentElement) || named) && !wrapsControls(el);
            };
            window.__kyoUniqueSelector = (el) => {
                const parts = [];
                for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
                    if (n.id && document.querySelectorAll('#' + CSS.escape(n.id)).length === 1) {
                        parts.unshift('#' + CSS.escape(n.id));
                        break;
                    }
                    const tag = n.tagName.toLowerCase();
                    let k = 1; for (let s = n.previousElementSibling; s; s = s.previousElementSibling) if (s.tagName === n.tagName) k++;
                    parts.unshift(tag + ':nth-of-type(' + k + ')');
                }
                return parts.join(' > ');
            };
            window.__kyoDiscoverProbe = (el) => {
                const r = el.getBoundingClientRect();
                const whole = (el.textContent || '').trim();
                const cut = whole.slice(0, whole.length > 200 && whole.codePointAt(199) > 0xFFFF ? 199 : 200);
                let t;
                if (typeof cut.toWellFormed === 'function') t = cut.toWellFormed();
                else {
                    // Older Chromium versions lack toWellFormed. Copy only when a lone surrogate needs replacement.
                    let start = 0, text = '';
                    for (let i = 0; i < cut.length; i++) {
                        const code = cut.charCodeAt(i), next = cut.charCodeAt(i + 1);
                        if (code >= 0xD800 && code <= 0xDBFF && next >= 0xDC00 && next <= 0xDFFF) i++;
                        else if (code >= 0xD800 && code <= 0xDFFF) {
                            text += cut.slice(start, i) + '�';
                            start = i + 1;
                        }
                    }
                    t = start === 0 ? cut : text + cut.slice(start);
                }
                const role = el.getAttribute('role');
                const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
                const hit = document.elementFromPoint(cx, cy);
                const topmost = !!(hit && (hit === el || el.contains(hit)));
                const visible = (typeof el.checkVisibility === 'function' ? el.checkVisibility() : true) && r.width > 0 && r.height > 0;
                const inViewport = r.right > 0 && r.bottom > 0 && r.left < window.innerWidth && r.top < window.innerHeight;
                const interactive = isControl(el) || el.tabIndex >= 0 || isPointerTarget(el);
                const obj = {
                    selector: window.__kyoUniqueSelector(el),
                    tag: el.tagName.toLowerCase(),
                    classes: Array.from(el.classList),
                    x: r.x, y: r.y, width: r.width, height: r.height,
                    visible: visible, inViewport: inViewport, topmost: topmost, interactive: interactive
                };
                if (el.id) obj.id = el.id;
                if (t) obj.text = t;
                if (role) obj.role = role;
                return obj;
            };
            return 'installed';
        })()"""
end DiscoverJs
