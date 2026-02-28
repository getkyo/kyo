package demo

import kyo.Style

object DemoStyles:

    val app = Style.maxWidth(800).margin(Style.Size.Px(0), Style.Size.auto)

    val headerStyle = Style.bg("#2563eb").color(Style.Color.white).padding(16, 32)

    val navStyle = Style.row.gap(16)

    val themeToggle = Style
        .bg(Style.Color.rgba(255, 255, 255, 0.2))
        .color(Style.Color.white)
        .border(1, Style.BorderStyle.solid, Style.Color.rgba(255, 255, 255, 0.3))
        .padding(4, 12)
        .rounded(4)
        .cursor(_.pointer)

    val content = Style.padding(32).gap(24)

    val card = Style.bg(Style.Color.white).padding(24, 32).rounded(8).shadow(y = 1, blur = 3, c = Style.Color.rgba(0, 0, 0, 0.1))

    val counterRow = Style.row.gap(16).align(_.center)

    val counterBtn = Style
        .width(40).height(40)
        .fontSize(19)
        .border(1, "#ddd")
        .rounded(8)
        .cursor(_.pointer)
        .bg("#f0f0f0")

    val counterValue = Style.fontSize(32).bold.minWidth(60).textAlign(_.center)

    val todoInput = Style.row.gap(8).margin(0, 0, 16, 0)

    val submitBtn = Style
        .bg("#2563eb")
        .color(Style.Color.white)
        .borderStyle(_.none)
        .padding(8, 24)
        .rounded(4)
        .cursor(_.pointer)

    val todoList = Style.padding(0).margin(0)

    val todoItem = Style.row.padding(8, 0).borderBottom(1, "#eee").gap(8).align(_.center)

    val deleteBtn = Style
        .bg("#ef4444")
        .color(Style.Color.white)
        .borderStyle(_.none)
        .rounded(4)
        .cursor(_.pointer)
        .padding(2, 8)

    val footerStyle = Style.textAlign(_.center).padding(32).color("#666")

end DemoStyles
