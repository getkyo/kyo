package demo

import kyo.*
import scala.language.implicitConversions

/** Stress-tests rapid add/remove on foreachKeyed while toggling visibility. */
object RapidMutationUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.bg("#dc2626").color(Color.white).padding(8, 20).rounded(4)
        .borderStyle(_.none).cursor(_.pointer)
    private val btnSm = Style.padding(4, 12).rounded(4).border(1, "#ccc").cursor(_.pointer).fontSize(12)
    private val tag   = Style.bg("#fecaca").padding(4, 12).rounded(12)

    def build: UI < Async =
        for
            items   <- Signal.initRef(Chunk("A", "B", "C"))
            counter <- Signal.initRef(0)
            visible <- Signal.initRef(true)
            log     <- Signal.initRef(Chunk.empty[String])
        yield div.style(app)(
            header.style(Style.bg("#dc2626").color(Color.white).padding(16, 32))(
                h1("Rapid Mutation Tests")
            ),
            main.style(content)(
                // Add + remove rapidly
                section.cls("rapid-list").style(card)(
                    h3("Rapid Add/Remove"),
                    p.style(Style.fontSize(13).color("#64748b"))("Rapidly mutate a keyed list:"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        button.cls("add-btn").style(btn).onClick {
                            for
                                c <- counter.getAndUpdate(_ + 1)
                                _ <- items.getAndUpdate(_.append(s"Item-${c + 1}"))
                                _ <- log.getAndUpdate(_.append(s"Added Item-${c + 1}"))
                            yield ()
                        }("Add"),
                        button.cls("remove-first-btn").style(btnSm).onClick {
                            for
                                cur <- items.get
                                _ <-
                                    if cur.nonEmpty then
                                        for
                                            _ <- items.set(cur.drop(1))
                                            _ <- log.getAndUpdate(_.append(s"Removed ${cur.head}"))
                                        yield ()
                                    else log.getAndUpdate(_.append("Nothing to remove"))
                            yield ()
                        }("Remove First"),
                        button.cls("remove-last-btn").style(btnSm).onClick {
                            for
                                cur <- items.get
                                _ <-
                                    if cur.nonEmpty then
                                        for
                                            _ <- items.set(cur.dropRight(1))
                                            _ <- log.getAndUpdate(_.append(s"Removed ${cur.last}"))
                                        yield ()
                                    else log.getAndUpdate(_.append("Nothing to remove"))
                            yield ()
                        }("Remove Last"),
                        button.cls("clear-btn").style(btnSm).onClick {
                            for
                                _ <- items.set(Chunk.empty)
                                _ <- log.getAndUpdate(_.append("Cleared all"))
                            yield ()
                        }("Clear All"),
                        button.cls("burst-btn").style(Style.bg("#7c3aed").color(Color.white).padding(4, 12).rounded(4)
                            .borderStyle(_.none).cursor(_.pointer).fontSize(12)).onClick {
                            for
                                c <- counter.get
                                _ <- counter.set(c + 5)
                                _ <- items.getAndUpdate { cur =>
                                    cur.append(s"Burst-${c + 1}")
                                        .append(s"Burst-${c + 2}")
                                        .append(s"Burst-${c + 3}")
                                        .append(s"Burst-${c + 4}")
                                        .append(s"Burst-${c + 5}")
                                }
                                _ <- log.getAndUpdate(_.append(s"Burst added 5 items"))
                            yield ()
                        }("Burst Add 5")
                    ),
                    div.cls("item-list").style(Style.row.gap(4).margin(8, 0, 0, 0))(
                        items.foreachKeyed(identity) { item =>
                            span.style(tag)(item)
                        }
                    ),
                    div.cls("item-count").style(Style.padding(4).color("#64748b").fontSize(12).margin(4, 0, 0, 0))(
                        items.map(c => s"Count: ${c.size}")
                    ),
                    UI.when(items.map(_.isEmpty))(
                        div.cls("empty-state").style(Style.padding(12).bg("#fef2f2").rounded(8).margin(4, 0, 0, 0).textAlign(_.center))(
                            p("Empty — add some items!")
                        )
                    )
                ),
                // Visibility toggle while items mutate
                section.cls("toggle-visible").style(card)(
                    h3("Toggle Visibility + Mutate"),
                    p.style(Style.fontSize(13).color("#64748b"))("Hide/show list while adding items:"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        button.cls("toggle-btn").style(btn).onClick(visible.getAndUpdate(!_).unit)(
                            visible.map(v => if v then "Hide List" else "Show List")
                        ),
                        button.cls("add-while-hidden-btn").style(btnSm).onClick {
                            for
                                c <- counter.getAndUpdate(_ + 1)
                                _ <- items.getAndUpdate(_.append(s"Hidden-${c + 1}"))
                                _ <- log.getAndUpdate(_.append(s"Added Hidden-${c + 1} (may be hidden)"))
                            yield ()
                        }("Add (even if hidden)")
                    ),
                    UI.when(visible)(
                        div.cls("toggled-list").style(Style.gap(4).margin(8, 0, 0, 0).padding(12).bg("#f8fafc").rounded(8))(
                            items.foreachKeyed(identity) { item =>
                                div.style(Style.padding(4, 8).bg("#dbeafe").rounded(4))(item)
                            }
                        )
                    )
                ),
                // Operation log
                section.cls("log").style(card)(
                    h3("Operation Log"),
                    div.style(Style.maxHeight(200).overflow(_.scroll).padding(8).bg("#f9fafb").rounded(8).fontSize(12).gap(2))(
                        log.foreachIndexed { (idx, entry) =>
                            div.style(Style.color("#64748b"))(s"${idx + 1}. $entry")
                        }
                    ),
                    button.cls("clear-log-btn").style(btnSm.margin(8, 0, 0, 0)).onClick(log.set(Chunk.empty).unit)("Clear Log")
                )
            )
        )

end RapidMutationUI
