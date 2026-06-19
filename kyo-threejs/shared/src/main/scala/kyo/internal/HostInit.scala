package kyo.internal

import kyo.*

/** The reconstituted client-side boot value for one server-push 3D host: the initial scene graph,
  * the camera, and the frame mode the island mounts on page load. It is built by
  * [[kyo.ThreeMount.readHostInit]] from the inline `<script type="application/json"
  * data-kyo-host-init>` data island the SSR page emitted (a serializable [[HostPayload]] boot
  * payload), so the island materializes the initial scene with no WebSocket round-trip.
  *
  * It is the RECONSTITUTED form (carrying a live [[Three]] AST with closures stripped at flatten
  * time), not the wire form: the wire form that crosses the page is the `Schema`-serializable
  * [[HostPayload]]; this value is the kyo-threejs reconstruction of that payload, so it lives in
  * kyo-threejs (it references `Three`, `Three.Ast.Camera`, and `ThreeFrames`) and carries no
  * `Schema` of its own.
  */
final private[kyo] case class HostInit(
    scene: Three,
    camera: Three.Ast.Camera,
    frames: ThreeFrames
)
