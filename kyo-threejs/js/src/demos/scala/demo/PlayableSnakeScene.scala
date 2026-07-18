package demo

import kyo.*
import kyo.Style.*
import kyo.Three.foreachKeyed
import kyo.Three.render

/** A real, playable 3D Snake: you steer it, and the whole game lives in one `SignalRef[Game]`.
  *
  * Every tick reads the CURRENT direction and folds one step of game logic into a fresh `Game` value:
  * the head advances, a food hit grows the body and moves the food, and a self-collision or reaching the
  * target length ends the game. `foreachKeyed` renders one cube per body segment keyed by a segment id that
  * never changes once assigned, so a step patches only the cubes whose position actually moved (the tail
  * cube is disposed, a new head cube is created, and every other cube's GPU buffers survive untouched)
  * rather than tearing the whole body down and rebuilding it thirteen times a second.
  *
  * Steering is keyboard-first, through `UIWindow.onKeyDown`: kyo-ui's DOCUMENT-level key subscription,
  * which maps the arrow keys (and WASD) to a direction. The obvious move is an `onKeyDown` on the board
  * element itself, and it is the wrong one: an element's key handler only fires while that element has
  * focus, so the arrow keys would do nothing until the player happened to click the board first, and
  * nothing on screen would tell them why. A game's controls belong to the page, not to one element, so the
  * subscription does too; it is `Scope`-bound, so the listener goes away with the mount. The on-screen
  * arrow buttons write the SAME `SignalRef[Direction]`, so a click and a keystroke are interchangeable
  * inputs to the one game loop.
  *
  * Neither kyo-ui nor kyo-threejs gained anything new for this: `UIWindow.onKeyDown` and `Group.onFrame`
  * already existed.
  *
  * Read next: `ServerVizScene` (a signal driven from across the network, not from a keyboard).
  */
object PlayableSnakeScene:

    // Dark, because the board beside these controls clears to near-black: a light page would read as a hole
    // punched in a white sheet.
    private val text: Color = Color.rgb(221, 227, 234)

    private val pageStyle: Style =
        Style.column.gap(12.px).padding(16.px).fontFamily(FontFamily.SansSerif).color(text)

    private val statusStyle: Style = Style.fontSize(15.px).color(text)

    private val buttonStyle: Style =
        Style.padding(8.px, 14.px).fontSize(15.px).rounded(6.px)
            .border(1.px, Color.rgb(58, 68, 83)).bg(Color.rgb(35, 42, 53)).color(text)
            .hover(_.bg(Color.rgb(46, 55, 69)))

    // The steering pad: a cross of arrows, so the buttons read as a d-pad and not a stack of full-width
    // bars. The keys are square for the same reason.
    private val arrowPadStyle: Style = Style.column.gap(6.px).align(Alignment.center).padding(8.px)
    private val arrowRowStyle: Style = Style.row.gap(6.px).justify(Justification.center)
    private val arrowKeyStyle: Style = buttonStyle.width(44.px).textAlign(TextAlign.center)

    final case class Segment(id: Int, pos: Three.Vec3) derives CanEqual

    /** The steering directions, a closed alternative to the raw key strings a `KeyboardEvent` carries. */
    enum Direction derives CanEqual:
        case Up, Down, Left, Right

    /** Whether the game is still running, or how it ended. */
    enum Status derives CanEqual:
        case Playing, Won, Lost

    final case class Game(
        body: Chunk[Segment],
        food: Three.Vec3,
        foodIndex: Int,
        nextId: Int,
        status: Status
    ) derives CanEqual

    /** The body length a win requires: six food pickups beyond the starting length. */
    private val winLength = 10

    /** The fixed-interval tick driving the snake, independent of the keyboard's own timing. */
    val frames: ThreeFrames = ThreeFrames.Clock(150.millis)

    /** Half-extent of the square playfield: cells run from `-gridHalf` to `+gridHalf` on each axis. */
    private val gridHalf = 5

    /** Where food spawns, in the order it is consumed; cycles once every pickup passes the end. */
    private val foodSpawnPoints: Chunk[Three.Vec3] = Chunk(
        Three.Vec3(3, 0, -2),
        Three.Vec3(-3, 0, 2),
        Three.Vec3(4, 0, 4),
        Three.Vec3(-4, 0, -4),
        Three.Vec3(0, 0, 4),
        Three.Vec3(4, 0, -4)
    )

    private val initialBody: Chunk[Segment] = Chunk(
        Segment(0, Three.Vec3(0, 0, 0)),
        Segment(1, Three.Vec3(-1, 0, 0)),
        Segment(2, Three.Vec3(-2, 0, 0)),
        Segment(3, Three.Vec3(-3, 0, 0))
    )

    /** The scene, plus the direction ref both input paths write and the status the HUD reads. */
    def scene(using Frame): (Three.Ast.Scene, SignalRef[Direction], Signal[Status]) < Sync =
        for
            dir  <- Signal.initRef(Direction.Right)
            game <- Signal.initRef(Game(initialBody, foodSpawnPoints.head, 0, initialBody.size, Status.Playing))
            status = game.map(_.status)
            cubes = game.map(_.body).foreachKeyed(_.id.toString) { seg =>
                Three.mesh(
                    Three.Geometry.box(0.9, 0.9, 0.9),
                    Three.Material.standard(color = Three.Color.green, emissive = Three.Color(0x114411))
                ).position(seg.pos)
            }
            food = game.render { g =>
                Three.mesh(
                    Three.Geometry.sphere(0.4),
                    Three.Material.standard(color = Three.Color.red, emissive = Three.Color(0x440000))
                ).position(g.food)
            }
            ticker = Three.group().onFrame { _ =>
                dir.current.map(d => game.updateAndGet(step(vecOf(d))).unit)
            }
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 0.6),
                Three.Light.directional(intensity = 1.2, position = Three.Vec3(4, 8, 6)),
                cubes,
                food,
                ticker
            ),
            dir,
            status
        )
        end for
    end scene

    /** The angled camera framing the whole playfield, looking down at the grid centre. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            fov = Three.Radians.deg(70),
            position = Three.Vec3(0, 5, 8),
            lookAt = Three.Vec3(0, 0, 0)
        )

    /** The page: the embedded board, a HUD status label, and four arrow buttons, all driving the one
      * `SignalRef[Direction]`, plus a document-level key subscription driving that same ref.
      *
      * The keys come from `UIWindow.onKeyDown`, kyo-ui's DOCUMENT-level subscription, not from an
      * `onKeyDown` on the board element. An element's key handler only fires while that element has focus,
      * so an element-scoped handler makes a game demo depend on the player clicking the board first, and
      * silently does nothing until they do. A game's controls belong to the page, so the subscription does
      * too. It is bound to the enclosing `Scope`, so the listener is removed when the mount tears down.
      */
    def ui(using Frame): UI < (Async & Scope) =
        scene.map { case (built, dir, status) =>
            val hud = UI.p(status.map {
                case Status.Playing => "Playing: arrow keys or WASD to steer, or use the buttons below."
                case Status.Won     => "You win! The snake reached its target length."
                case Status.Lost    => "Game over: the snake ran into itself."
            }).id("status").style(statusStyle)

            val buttons = UI.div(
                UI.button("^").id("btn-up").onClick(dir.set(Direction.Up)).style(arrowKeyStyle),
                UI.div(
                    UI.button("<").id("btn-left").onClick(dir.set(Direction.Left)).style(arrowKeyStyle),
                    UI.button(">").id("btn-right").onClick(dir.set(Direction.Right)).style(arrowKeyStyle)
                ).style(arrowRowStyle),
                UI.button("v").id("btn-down").onClick(dir.set(Direction.Down)).style(arrowKeyStyle)
            ).id("arrow-pad").style(arrowPadStyle)

            UIWindow.onKeyDown { evt =>
                directionOf(evt.key) match
                    case Present(d) => dir.set(d)
                    case Absent     => Kyo.unit
            }.andThen(
                UI.div(
                    hud,
                    Three.embed(built, camera, frames).id("stage"),
                    buttons
                )
                    .id("playable-snake-demo")
                    .style(pageStyle)
            )
        }

    /** Maps a keyboard key to a [[Direction]]; every non-steering key is simply ignored rather than
      * treated as an error, since a key press unrelated to the game is an ordinary, expected event.
      */
    private def directionOf(key: UI.Keyboard): Maybe[Direction] = key match
        case UI.Keyboard.ArrowUp | UI.Keyboard.Char('w') | UI.Keyboard.Char('W')    => Present(Direction.Up)
        case UI.Keyboard.ArrowDown | UI.Keyboard.Char('s') | UI.Keyboard.Char('S')  => Present(Direction.Down)
        case UI.Keyboard.ArrowLeft | UI.Keyboard.Char('a') | UI.Keyboard.Char('A')  => Present(Direction.Left)
        case UI.Keyboard.ArrowRight | UI.Keyboard.Char('d') | UI.Keyboard.Char('D') => Present(Direction.Right)
        case _                                                                      => Absent

    private def vecOf(d: Direction): Three.Vec3 = d match
        case Direction.Up    => Three.Vec3(0, 0, -1)
        case Direction.Down  => Three.Vec3(0, 0, 1)
        case Direction.Left  => Three.Vec3(-1, 0, 0)
        case Direction.Right => Three.Vec3(1, 0, 0)

    /** One tick of game logic: a finished game is frozen, otherwise the head advances, a food hit grows
      * the body and advances the food, and a self-collision (checked against the body cells that remain
      * occupied after this move, so the vacated tail cell is never a false collision) ends the game.
      */
    private def step(dir: Three.Vec3)(g: Game): Game =
        g.status match
            case Status.Won | Status.Lost => g
            case Status.Playing =>
                val head       = g.body.headMaybe.getOrElse(Segment(0, Three.Vec3.zero))
                val newHeadPos = wrap(head.pos + dir)
                val ateFood    = newHeadPos == g.food
                val staying    = if ateFood then g.body else g.body.dropRight(1)
                val selfHit    = staying.exists(_.pos == newHeadPos)
                if selfHit then g.copy(status = Status.Lost)
                else
                    val grown      = Segment(g.nextId, newHeadPos) +: staying
                    val nextIndex  = if ateFood then g.foodIndex + 1 else g.foodIndex
                    val nextFood   = if ateFood then foodSpawnPoints(nextIndex % foodSpawnPoints.size) else g.food
                    val nextStatus = if grown.size >= winLength then Status.Won else Status.Playing
                    Game(grown, nextFood, nextIndex, g.nextId + 1, nextStatus)
                end if
    end step

    /** Wraps a position so the snake re-enters from the opposite edge when it leaves the grid. */
    private def wrap(pos: Three.Vec3): Three.Vec3 =
        def fold(v: Double): Double =
            if v > gridHalf then -gridHalf.toDouble
            else if v < -gridHalf then gridHalf.toDouble
            else v
        Three.Vec3(fold(pos.x), fold(pos.y), fold(pos.z))
    end wrap

end PlayableSnakeScene
