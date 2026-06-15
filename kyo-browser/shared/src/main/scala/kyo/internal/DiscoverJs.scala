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

    /** Idempotent install of `window.__kyoDiscoverProbe` and `window.__kyoUniqueSelector`. `text` is truncated to 200 chars; empty-post-trim
      * maps to null. `interactive` is tag/role/tabindex/onclick. `topmost` is a single rect-center `elementFromPoint` resolving to `el` or a
      * descendant. `inViewport` is shared with the VERIFY `inViewport` JS.
      */
    private[kyo] val installJs: String =
        """(() => {
            if (window.__kyoDiscoverInstalled) return 'shared';
            window.__kyoDiscoverInstalled = true;
            const INTERACTIVE_TAGS = new Set(['A','BUTTON','INPUT','SELECT','TEXTAREA','OPTION','LABEL']);
            const INTERACTIVE_ROLES = new Set(['button','link','checkbox','radio','tab','menuitem','textbox']);
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
                const t = (el.textContent || '').trim().slice(0, 200);
                const role = el.getAttribute('role');
                const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
                const hit = document.elementFromPoint(cx, cy);
                const topmost = !!(hit && (hit === el || el.contains(hit)));
                const visible = (typeof el.checkVisibility === 'function' ? el.checkVisibility() : true) && r.width > 0 && r.height > 0;
                const inViewport = r.right > 0 && r.bottom > 0 && r.left < window.innerWidth && r.top < window.innerHeight;
                const interactive = INTERACTIVE_TAGS.has(el.tagName) || el.hasAttribute('onclick')
                    || (role && INTERACTIVE_ROLES.has(role)) || el.tabIndex >= 0;
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
