package kyo.internal

/** Embedded browser drag runtime for the server-push client.
  *
  * Mirrors the DomDragRuntime state machine and reserved data attributes for pages driven over the
  * WebSocket: native HTML5 lifecycle, pointer, touch, and keyboard sensors, midpoint collision along
  * the target orientation, container auto-scroll, one live region per session for announcements, and
  * idempotent teardown. The script exposes exactly one installation function inside the client IIFE;
  * no per-element listeners are emitted.
  */
private[kyo] object DragClientJs:

    /** The runtime installation script. `installDragRuntime(post, lifecycle)` wires document-level
      * capture listeners, posts `ClientMessage.Event` envelopes through `post`, and returns
      * `{resolve, cleanup}`. `lifecycle.onClose` receives the idempotent cleanup so the socket close
      * path, page hide, and reconnect installation all tear the runtime down exactly once.
      */
    def script(basePath: String): String =
        s"""// kyo drag runtime: native lifecycle + pointer/touch/keyboard sensors, collision, auto-scroll,
           |// announcements, teardown, reconnect reset. Installed once per page; no per-element listeners.
           |function installDragRuntime(post,lifecycle){
           |var ACT=6,HOLD=250,SLOP=8,EDGE=24,STEP=16;
           |var phase="Idle",ctx=null,pending=null,holdTimer=null,frame=null,closed=false;
           |function sid(p){try{return p+"-"+crypto.randomUUID();}catch(e){return p+"-"+Date.now().toString(16)+"-"+Math.floor(Math.random()*1e9).toString(16);}}
           |function attr(el,n){return el&&el.getAttribute?el.getAttribute(n):null;}
           |function pathOf(el){var p=attr(el,"data-kyo-path");return p===null?null:(p===""?[]:p.split("."));}
           |function cfg(el,n){var raw=attr(el,n);if(!raw)return null;try{return JSON.parse(raw);}catch(e){return null;}}
           |function closest(el,n){var c=el;while(c&&c!==document.body){if(c.getAttribute&&c.getAttribute(n)!==null)return c;c=c.parentElement;}return null;}
           |function srcAt(el){var e=closest(el,"data-kyo-drag-source-key");if(!e)return null;var c=cfg(e,"data-kyo-drag-source");if(!c)return null;return {el:e,cfg:c,path:pathOf(e),key:c.key};}
           |function tgtAt(el){var c=el;while(c&&c!==document.body){if(attr(c,"data-kyo-drop-target-key")!==null){var t=cfg(c,"data-kyo-drop-target");if(t)return {el:c,cfg:t,path:pathOf(c),key:t.key};}c=c.parentElement;}return null;}
           |function mods(e){return {ctrl:!!e.ctrlKey,alt:!!e.altKey,shift:!!e.shiftKey,meta:!!e.metaKey};}
           |function pt(e){var x=e.clientX,y=e.clientY;if((x===undefined||x===null)&&e.touches&&e.touches.length){x=e.touches[0].clientX;y=e.touches[0].clientY;}return {x:+x||0,y:+y||0};}
           |function ev(kind,path,body){var o={};var inner={path:path||[]};for(var k in body)inner[k]=body[k];o[kind]=inner;post({Event:{value:o}});}
           |function mediaOk(acc,it){if(!acc.mediaTypes||!acc.mediaTypes.length)return true;var reps=it.Text?Object.keys(it.Text.representations):(it.Uri?["text/uri-list"]:(it.File?[it.File.meta.mediaType]:[]));
           |  for(var i=0;i<reps.length;i++){for(var j=0;j<acc.mediaTypes.length;j++){var p=acc.mediaTypes[j];var s=p.indexOf("/");
           |    if(p===reps[i])return true;if(p.slice(s+1)==="*"&&reps[i].slice(0,s)===p.slice(0,s))return true;}}
           |  return it.Directory?!!acc.directories:false;}
           |function accepts(t,items){var a=t.cfg.accepts;if(a.maxItems!==undefined&&a.maxItems!==null&&items.length>a.maxItems)return false;
           |  for(var i=0;i<items.length;i++){if(!mediaOk(a,items[i]))return false;}return true;}
           |function sortable(t){var a=t.cfg.accepts;if(!a.mediaTypes)return false;for(var i=0;i<a.mediaTypes.length;i++){if(a.mediaTypes[i]==="application/x-kyo-sortable")return true;}return false;}
           |function orient(t){var o=t.cfg.orientation||{};return o.Horizontal?"h":(o.Both?"b":"v");}
           |function label(c){return (c.cfg.label!==undefined&&c.cfg.label!==null)?c.cfg.label:c.key;}
           |function live(){var r=document.createElement("div");r.setAttribute("data-kyo-drag-live","true");r.setAttribute("aria-live","assertive");r.setAttribute("role","status");
           |  var s=r.style;s.position="fixed";s.left="-10000px";s.top="-10000px";s.width="1px";s.height="1px";s.overflow="hidden";document.body.appendChild(r);return r;}
           |function say(m){if(ctx&&ctx.region)ctx.region.textContent=m;}
           |function clearDrop(){if(!ctx)return;if(ctx.target)ctx.target.el.removeAttribute("data-kyo-drop-valid");
           |  if(ctx.anchor)ctx.anchor.el.removeAttribute("data-kyo-drop-position");ctx.anchor=null;}
           |function cleanupSession(){if(!ctx)return;if(frame){cancelAnimationFrame(frame);frame=null;}
           |  if(ctx.preview){ctx.preview.remove();ctx.preview=null;}
           |  if(ctx.region){ctx.region.remove();ctx.region=null;}
           |  if(ctx.src)ctx.src.el.removeAttribute("data-kyo-dragging");
           |  clearDrop();
           |  if(ctx.keyboard&&ctx.src){var back=document.querySelector('[data-kyo-drag-source-key="'+ctx.src.key+'"]');if(back&&back.focus)back.focus();}}
           |function finish(cancelled){if(!ctx)return;var c=ctx;cleanupSession();
           |  ev("DragEnd",c.src?c.src.path:[],{event:{sessionId:c.id,operation:c.op,cancelled:cancelled}});
           |  ctx=null;phase="Idle";}
           |function selection(src){var scope=tgtAt(src.el.parentElement);var root=scope?scope.el:document;
           |  var marked=root.querySelectorAll('[data-kyo-drag-source-key][data-kyo-drag-selected="true"]');var keys=[],found=false;
           |  for(var i=0;i<marked.length;i++){var k=attr(marked[i],"data-kyo-drag-source-key");keys.push(k);if(k===src.key)found=true;}
           |  return found&&keys.length?keys:[src.key];}
           |function emitStart(src,at,m){ev("DragStart",src.path,{event:{sessionId:ctx.id,items:src.cfg.items||[],operation:{Move:{}},sourceKey:src.key,point:at,modifiers:m}});}
           |function lift(src,at,keyboard,pointerId){var scope=tgtAt(src.el.parentElement);
           |  ctx={id:sid("drag"),src:src,op:{Move:{}},keyboard:keyboard,pointerId:pointerId,last:at,target:null,anchor:null,after:false,slot:0,
           |       collection:scope?scope.key:null,keys:selection(src),region:live(),preview:null};
           |  src.el.setAttribute("data-kyo-dragging","true");
           |  if(!keyboard){var cl=src.el.cloneNode(true);cl.setAttribute("data-kyo-drag-preview","true");cl.setAttribute("aria-hidden","true");
           |    var st=cl.style;st.position="fixed";st.pointerEvents="none";st.opacity="0.8";document.body.appendChild(cl);ctx.preview=cl;}
           |  if(keyboard&&scope){ctx.target=scope;scope.el.setAttribute("data-kyo-drop-valid","true");
           |    var its=items(scope);var before=0;for(var i=0;i<its.length;i++){var pos=src.el.compareDocumentPosition(its[i].el);if(pos&Node.DOCUMENT_POSITION_PRECEDING)before++;}ctx.slot=before;}
           |  phase="Dragging";say(label(src)+" picked up.");emitStart(src,at,{ctrl:false,alt:false,shift:false,meta:false});}
           |function items(t){var nodes=t.el.querySelectorAll("[data-kyo-drag-source-key]");var out=[];
           |  for(var i=0;i<nodes.length;i++){var k=attr(nodes[i],"data-kyo-drag-source-key");if(ctx.keys.indexOf(k)<0){var c=cfg(nodes[i],"data-kyo-drag-source");if(c)out.push({el:nodes[i],cfg:c,key:k});}}
           |  return out;}
           |function targets(){var nodes=document.querySelectorAll("[data-kyo-drop-target-key]");var out=[];
           |  for(var i=0;i<nodes.length;i++){var t=cfg(nodes[i],"data-kyo-drop-target");if(t)out.push({el:nodes[i],cfg:t,path:pathOf(nodes[i]),key:t.key});}
           |  return out;}
           |function applySlot(t,its){if(ctx.anchor)ctx.anchor.el.removeAttribute("data-kyo-drop-position");ctx.anchor=null;
           |  if(!its.length){ctx.after=true;say("Into "+label(t)+".");return;}
           |  var it,after;if(ctx.slot<=0){it=its[0];after=false;}else{it=its[Math.min(ctx.slot,its.length)-1];after=true;}
           |  ctx.anchor=it;ctx.after=after;it.el.setAttribute("data-kyo-drop-position",after?"after":"before");
           |  say((after?"After ":"Before ")+((it.cfg.label!==undefined&&it.cfg.label!==null)?it.cfg.label:it.key)+".");}
           |function sensorFrame(){frame=null;if(!ctx||phase!=="Dragging")return;var at=ctx.last;
           |  if(ctx.preview){ctx.preview.style.left=(at.x+8)+"px";ctx.preview.style.top=(at.y+8)+"px";}
           |  var de=document.scrollingElement||document.documentElement;
           |  if(at.y<EDGE)de.scrollTop-=STEP;else if(window.innerHeight-at.y<EDGE)de.scrollTop+=STEP;
           |  var stack=document.elementsFromPoint?document.elementsFromPoint(at.x,at.y):[];var t=null,anchorEl=null;
           |  for(var i=0;i<stack.length&&!t;i++){t=tgtAt(stack[i]);}
           |  if(t&&!accepts(t,ctx.src.cfg.items||[]))t=null;
           |  if(t){for(var j=0;j<stack.length&&!anchorEl;j++){var k=attr(stack[j],"data-kyo-drag-source-key");
           |      if(k!==null&&t.el.contains(stack[j])&&stack[j]!==t.el&&ctx.keys.indexOf(k)<0)anchorEl=stack[j];}}
           |  var changed=!ctx.target||!t||ctx.target.el!==t.el;
           |  if(changed)clearDrop();
           |  if(t){ctx.target=t;t.el.setAttribute("data-kyo-drop-valid","true");
           |    if(anchorEl){var r=anchorEl.getBoundingClientRect();var after=orient(t)==="h"?at.x>=r.left+r.width/2:at.y>=r.top+r.height/2;
           |      var c=cfg(anchorEl,"data-kyo-drag-source");ctx.anchor={el:anchorEl,cfg:c||{},key:attr(anchorEl,"data-kyo-drag-source-key")};ctx.after=after;
           |      anchorEl.setAttribute("data-kyo-drop-position",after?"after":"before");}
           |    var body={event:{sessionId:ctx.id,operation:ctx.op,targetKey:t.key,point:at,modifiers:{ctrl:false,alt:false,shift:false,meta:false},position:{Inside:{}}}};
           |    ev(changed?"DragEnter":"DragOver",t.path,body);}
           |  else ctx.target=null;}
           |function drop(){if(!ctx||!ctx.target)return finish(true);var t=ctx.target;
           |  if(sortable(t)){var move={keys:ctx.keys,source:{collection:ctx.collection||t.key},destination:{collection:t.key},
           |      position:(!ctx.anchor||ctx.after)?{After:{}}:{Before:{}},operation:{Move:{}}};
           |    if(ctx.anchor)move.anchor=ctx.anchor.key;
           |    phase="AwaitingDecision";ev("SortMove",t.path,{sessionId:ctx.id,move:move});}
           |  else{phase="AwaitingDecision";
           |    ev("Drop",t.path,{event:{sessionId:ctx.id,operation:ctx.op,targetKey:t.key,point:ctx.last,modifiers:{ctrl:false,alt:false,shift:false,meta:false},position:{Inside:{}}}});}}
           |function onPointerDown(e){if(closed||phase!=="Idle")return;var s=srcAt(e.target);if(!s)return;
           |  var a=s.cfg.activation||{};if(a.Native)return;var at=pt(e);pending={src:s,id:e.pointerId,x:at.x,y:at.y};phase="PendingPointer";}
           |function onPointerMove(e){if(closed)return;var at=pt(e);
           |  if(phase==="PendingPointer"&&pending&&e.pointerId===pending.id){
           |    var dx=at.x-pending.x,dy=at.y-pending.y;
           |    if(Math.sqrt(dx*dx+dy*dy)>=ACT){var s=pending.src;pending=null;lift(s,at,false,e.pointerId);}}
           |  else if(phase==="Dragging"&&ctx&&!ctx.keyboard){ctx.last=at;if(frame===null)frame=requestAnimationFrame(sensorFrame);}}
           |function onPointerUp(e){if(closed)return;
           |  if(phase==="PendingPointer"){pending=null;phase="Idle";}
           |  else if(phase==="Dragging"&&ctx&&!ctx.keyboard){if(frame){cancelAnimationFrame(frame);frame=null;}sensorFrame();if(ctx&&ctx.target)drop();else finish(true);}}
           |function onPointerCancel(e){if(phase==="PendingPointer"){pending=null;phase="Idle";}else if(ctx)finish(true);}
           |function onTouchStart(e){if(closed||phase!=="Idle")return;var s=srcAt(e.target);if(!s)return;
           |  var a=s.cfg.activation||{};if(a.Native)return;var at=pt(e);var st={src:s,x:at.x,y:at.y};pending=st;phase="PendingTouch";
           |  holdTimer=setTimeout(function(){if(phase==="PendingTouch"&&pending===st){pending=null;holdTimer=null;lift(st.src,{x:st.x,y:st.y},false,null);}},HOLD);}
           |function onTouchMove(e){if(closed)return;var at=pt(e);
           |  if(phase==="PendingTouch"&&pending){var dx=at.x-pending.x,dy=at.y-pending.y;
           |    if(Math.sqrt(dx*dx+dy*dy)>SLOP){clearTimeout(holdTimer);holdTimer=null;pending=null;phase="Idle";}}
           |  else if(phase==="Dragging"&&ctx&&!ctx.keyboard){ctx.last=at;if(frame===null)frame=requestAnimationFrame(sensorFrame);}}
           |function onTouchEnd(e){if(closed)return;
           |  if(phase==="PendingTouch"){clearTimeout(holdTimer);holdTimer=null;pending=null;phase="Idle";}
           |  else if(phase==="Dragging"&&ctx&&!ctx.keyboard){if(frame){cancelAnimationFrame(frame);frame=null;}if(ctx.target)drop();else finish(true);}}
           |function onKeyDown(e){if(closed)return;
           |  if(phase==="Idle"&&(e.key==="Enter"||e.key===" ")){var s=srcAt(e.target);if(!s)return;var a=s.cfg.activation||{};if(a.Native)return;
           |    if(attr(e.target,"data-kyo-drag-source-key")===null&&closest(e.target,"data-kyo-drag-source-key")===null)return;
           |    e.preventDefault();lift(s,{x:0,y:0},true,null);return;}
           |  if(phase!=="Dragging"||!ctx||!ctx.keyboard)return;
           |  var o=ctx.target?orient(ctx.target):"v";
           |  var fwd=o==="h"?["ArrowRight"]:(o==="b"?["ArrowDown","ArrowRight"]:["ArrowDown"]);
           |  var bwd=o==="h"?["ArrowLeft"]:(o==="b"?["ArrowUp","ArrowLeft"]:["ArrowUp"]);
           |  function slotTo(n){if(!ctx.target)return;var its=items(ctx.target);ctx.slot=Math.max(0,Math.min(its.length,n));applySlot(ctx.target,its);}
           |  if(fwd.indexOf(e.key)>=0){e.preventDefault();slotTo(ctx.slot+1);}
           |  else if(bwd.indexOf(e.key)>=0){e.preventDefault();slotTo(ctx.slot-1);}
           |  else if(e.key==="Home"){e.preventDefault();slotTo(0);}
           |  else if(e.key==="End"){e.preventDefault();slotTo(1e9);}
           |  else if(e.key==="Tab"){e.preventDefault();var ts=targets();if(!ts.length)return;
           |    var cur=-1;for(var i=0;i<ts.length;i++){if(ctx.target&&ts[i].el===ctx.target.el)cur=i;}
           |    var next=cur<0?0:(e.shiftKey?(cur-1+ts.length)%ts.length:(cur+1)%ts.length);
           |    if(ctx.target)ctx.target.el.removeAttribute("data-kyo-drop-valid");clearDrop();
           |    ctx.target=ts[next];ts[next].el.setAttribute("data-kyo-drop-valid","true");
           |    var its=items(ts[next]);ctx.slot=its.length;applySlot(ts[next],its);}
           |  else if(e.key==="Enter"||e.key===" "){e.preventDefault();if(ctx.target)drop();else finish(true);}
           |  else if(e.key==="Escape"){e.preventDefault();say(label(ctx.src)+" move cancelled.");finish(true);}}
           |// Native HTML5 lifecycle: sessions from draggable elements; drops route through the same accept path.
           |function onDragStart(e){if(closed||phase!=="Idle")return;var s=srcAt(e.target);if(!s)return;
           |  var a=s.cfg.activation||{};if(a.Sensors)return;
           |  if(e.dataTransfer){var its=s.cfg.items||[];for(var i=0;i<its.length;i++){var it=its[i];
           |    if(it.Text){for(var m in it.Text.representations)e.dataTransfer.setData(m,it.Text.representations[m]);}
           |    else if(it.Uri)e.dataTransfer.setData("text/uri-list",it.Uri.value);}}
           |  ctx={id:sid("drag"),src:s,op:{Move:{}},keyboard:false,pointerId:null,last:pt(e),target:null,anchor:null,after:false,slot:0,
           |       collection:null,keys:[s.key],region:null,preview:null};
           |  var scope=tgtAt(s.el.parentElement);ctx.collection=scope?scope.key:null;
           |  phase="Dragging";emitStart(s,pt(e),mods(e));}
           |function onDragOverNative(e){if(phase!=="Dragging"||!ctx)return;var t=tgtAt(e.target);
           |  if(t&&accepts(t,ctx.src.cfg.items||[])){e.preventDefault();var changed=!ctx.target||ctx.target.el!==t.el;ctx.target=t;ctx.last=pt(e);
           |    ev(changed?"DragEnter":"DragOver",t.path,{event:{sessionId:ctx.id,operation:ctx.op,targetKey:t.key,point:pt(e),modifiers:mods(e),position:{Inside:{}}}});}}
           |function onDropNative(e){if(phase!=="Dragging"||!ctx)return;var t=tgtAt(e.target);if(!t)return;e.preventDefault();ctx.target=t;ctx.last=pt(e);drop();}
           |function onDragEndNative(e){if(!ctx)return;if(phase==="Dragging")finish(true);else if(phase==="AwaitingDecision")phase="AwaitingDecisionAfterEnd";}
           |// External native drops: snapshot files behind session tokens and serve lazy reads over the wire.
           |var tokens={},cancelledReads={};
           |function newToken(p){var t=sid(p);return t;}
           |function fileItems(dt){var out=[];if(!dt||!dt.files)return out;
           |  for(var i=0;i<dt.files.length;i++){var f=dt.files[i];var t=newToken("file");tokens[t]=f;
           |    out.push({File:{meta:{token:t,name:f.name||"file",mediaType:f.type||"application/octet-stream",size:f.size,lastModified:new Date(f.lastModified||0).toISOString()}}});}
           |  return out;}
           |function onDropExternal(e){if(phase!=="Idle")return false;var t=tgtAt(e.target);if(!t)return false;
           |  var its=fileItems(e.dataTransfer);if(!its.length)return false;e.preventDefault();
           |  ctx={id:sid("drag"),src:null,op:{Copy:{}},keyboard:false,pointerId:null,last:pt(e),target:t,anchor:null,after:false,slot:0,
           |       collection:null,keys:[],region:null,preview:null};
           |  phase="AwaitingDecisionAfterEnd";
           |  ev("DragStart",[],{event:{sessionId:ctx.id,items:its,operation:{Copy:{}},point:pt(e),modifiers:mods(e)}});
           |  ev("Drop",t.path,{event:{sessionId:ctx.id,operation:{Copy:{}},targetKey:t.key,point:pt(e),modifiers:mods(e),position:{Inside:{}}}});
           |  return true;}
           |function b64(buf){var bytes=new Uint8Array(buf);var bin="";for(var i=0;i<bytes.length;i++)bin+=String.fromCharCode(bytes[i]);return btoa(bin);}
           |function failRead(id,failure){var f={};f[failure]={};post({FileFailure:{requestId:id,failure:f}});}
           |function serveFileRead(op){var id=op.requestId;var f=tokens[op.token];
           |  if(!f||typeof f.slice!=="function")return failRead(id,"InvalidToken");
           |  var offset=op.offset,max=op.maxSize;
           |  if(offset>=f.size)return post({FileReadComplete:{requestId:id}});
           |  var blob=f.slice(offset,Math.min(f.size,offset+max));
           |  blob.arrayBuffer().then(function(buf){
           |    if(cancelledReads[id]){delete cancelledReads[id];return;}
           |    post({FileChunk:{requestId:id,bytesBase64:b64(buf)}});
           |  }).catch(function(){failRead(id,"Io");});}
           |function serveDirectoryRead(op){var id=op.requestId;var entry=tokens[op.token];
           |  if(!entry||!entry.createReader)return failRead(id,"InvalidToken");
           |  var reader=entry.createReader();var out=[];
           |  function page(){reader.readEntries(function(list){
           |    if(cancelledReads[id]){delete cancelledReads[id];return;}
           |    if(!list.length||out.length>=op.maxEntries){
           |      post({FileEntries:{requestId:id,entries:out.slice(0,op.maxEntries),nextCursor:null}});return;}
           |    var i=0;function next(){
           |      if(i>=list.length){page();return;}
           |      var en=list[i++];
           |      if(en.isDirectory){var t=newToken("directory");tokens[t]=en;out.push({Directory:{token:t,name:en.name||"directory"}});next();}
           |      else en.file(function(fl){var t=newToken("file");tokens[t]=fl;
           |        out.push({File:{meta:{token:t,name:fl.name||"file",mediaType:fl.type||"application/octet-stream",size:fl.size,lastModified:new Date(fl.lastModified||0).toISOString()}}});next();},
           |        function(){next();});}
           |    next();
           |  },function(){failRead(id,"PermissionDenied");});}
           |  page();}
           |function onDropAny(e){if(phase==="Idle"){onDropExternal(e);}else{onDropNative(e);}}
           |function serveDropRead(op){
           |  if(op.ReadDropFile)serveFileRead(op.ReadDropFile);
           |  else if(op.ReadDropDirectory)serveDirectoryRead(op.ReadDropDirectory);
           |  else if(op.CancelDropRead)cancelledReads[op.CancelDropRead.requestId]=true;}
           |function resolve(sessionId,decision){if(closed||!ctx||ctx.id!==sessionId)return;
           |  if(decision.Reject){var r=decision.Reject.rejection||{};var reason=r.Application?r.Application.reason:"rejected";say("Move rejected: "+reason);finish(true);return;}
           |  if(phase==="AwaitingDecision"||phase==="AwaitingDecisionAfterEnd"){say(label(ctx.src)+" dropped.");finish(false);}}
           |var caps=[["dragstart",onDragStart],["dragover",onDragOverNative],["drop",onDropAny],["dragend",onDragEndNative],
           |  ["pointerdown",onPointerDown],["pointermove",onPointerMove],["pointerup",onPointerUp],["pointercancel",onPointerCancel],
           |  ["touchstart",onTouchStart],["touchmove",onTouchMove],["touchend",onTouchEnd],["touchcancel",onTouchEnd],
           |  ["keydown",onKeyDown]];
           |for(var ci=0;ci<caps.length;ci++)document.addEventListener(caps[ci][0],caps[ci][1],true);
           |function cleanup(){if(closed)return;closed=true;
           |  if(holdTimer){clearTimeout(holdTimer);holdTimer=null;}
           |  if(ctx)finish(true);
           |  tokens={};cancelledReads={};
           |  for(var ci=0;ci<caps.length;ci++)document.removeEventListener(caps[ci][0],caps[ci][1],true);}
           |window.addEventListener("pagehide",cleanup);
           |if(lifecycle&&lifecycle.onClose)lifecycle.onClose(cleanup);
           |return {resolve:resolve,cleanup:cleanup,serveDropRead:serveDropRead};
           |}""".stripMargin
end DragClientJs
