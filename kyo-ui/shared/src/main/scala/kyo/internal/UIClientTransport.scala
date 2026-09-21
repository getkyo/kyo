package kyo.internal

/** How the page's client runtime reaches its session, as the JavaScript that does it.
  *
  * The runtime in [[HtmlRenderer.clientJs]] applies ops and raises events and has no idea what carries them. Everything
  * that does is here, behind one small contract, because a kyo-ui view mounted in an MCP Apps sandbox reaches its
  * session in a way a page served over HTTP never does: the sandbox's default policy is `connect-src 'none'`, so the
  * view has no network at all and speaks to the host through `postMessage`, and only a view that declares the server's
  * origin may open a socket to it.
  *
  * A transport is an object with four methods, and it calls three functions the runtime defines:
  *
  *   - `open()` starts it, `write(text)` takes one encoded frame and answers whether it went out now (the runtime
  *     buffers it when not), `suspend()` stops it reconnecting while the page is being torn down, and `resume()` starts
  *     it again for a page restored from the back/forward cache.
  *   - `kyoArrive(text)` for each frame that arrives, which is also what proves a session is reading; `kyoAttached()`
  *     when the link becomes usable; `kyoDetached()` when it is gone.
  *
  * The split is the one the runtime already drew: the session announces itself with a frame before it renders, so
  * "live" means a frame has arrived and never that a link opened, and that holds for every transport here.
  */
private[kyo] object UIClientTransport:

    /** The WebSocket at the page's own origin, which is what `UI.runHandlers` serves.
      *
      * A session is one socket, and reconnecting is not optional bookkeeping: `post` buffers whenever the session is not
      * live and the buffer has one drain point, so a page that never reconnects silently stops delivering every later
      * interaction while still looking healthy. The refusal that forced this is a connect denied for want of buffer
      * space (Windows WSAENOBUFS), which is transient, so backing off and retrying is what rides it out.
      *
      * Resetting the backoff on a frame rather than on the open is what makes it real: a server can accept and end the
      * session at once, during a restart window, and a counter reset on open would redial forever at the shortest
      * interval. Each handler serves one socket and stands down once a newer one has replaced it, closing itself on the
      * way out, so a superseded connection is torn down rather than left with a server session attached.
      */
    val socketJs: String =
        """function kyoSocketTransport(url){
          |  var ws=null,retries=0,stopped=false;
          |  // Read-only test hook on the current socket; it follows each reconnect.
          |  Object.defineProperty(window,"__kyoWs",{get:function(){return ws;},configurable:true});
          |  function connect(){
          |    if(stopped)return;
          |    // Never dial while one is already dialing or open. Two paths can call in at once: a page becomes eligible
          |    // for the back/forward cache precisely when its socket is down, which is precisely when a retry is
          |    // pending, so a restore races the resumed timer. The loser would be superseded and never closed.
          |    if(ws&&ws.readyState<2)return;
          |    kyoDetached();
          |    var sock=new WebSocket(url);
          |    ws=sock;
          |    sock.onopen=function(){if(sock!==ws){sock.close();return;}kyoCarrier("socket");kyoAttached();};
          |    sock.onmessage=function(e){if(sock!==ws){sock.close();return;}retries=0;kyoArrive(e.data);};
          |    // A connect that never completes reports here and then closes, so recovery is driven from onclose alone;
          |    // this handler exists so the failure does not surface as an unhandled error.
          |    sock.onerror=function(){};
          |    sock.onclose=function(){
          |      if(sock!==ws)return;
          |      kyoDetached();
          |      if(stopped)return;
          |      // Capped exponential backoff: quick enough that a transient refusal recovers within a page's useful
          |      // lifetime, slow enough not to hammer a server that is genuinely down. Jittered to 50-100% of each step
          |      // so pages dropped together by a server restart do not all redial at the same instant.
          |      var wait=Math.min(250*Math.pow(2,retries),5000);
          |      retries++;
          |      setTimeout(connect,wait*(0.5+Math.random()*0.5));
          |    };
          |  }
          |  return{
          |    open:function(){connect();},
          |    write:function(m){if(ws&&ws.readyState===1){ws.send(m);return true;}return false;},
          |    suspend:function(){stopped=true;},
          |    resume:function(){stopped=false;retries=0;connect();},
          |    close:function(){stopped=true;if(ws){var sock=ws;ws=null;sock.close();}}
          |  };
          |}""".stripMargin

    /** The expression that builds the transport a page served by `UI.runHandlers` uses. */
    val sameOriginSocket: String =
        """kyoSocketTransport((location.protocol==="https:"?"wss:":"ws:")+"//"+location.host+base+"/_kyo/ws")"""

    /** The MCP Apps transport: the host's bridge, and the server's socket when the view is allowed to open one.
      *
      * The bridge is JSON-RPC over `postMessage` to `window.parent`, which for a view is the host's sandbox page. The
      * handshake is `ui/initialize` answered with the host's capabilities and context, then
      * `ui/notifications/initialized`, which is the only thing the host waits for before it pushes the tool call's
      * input and, when the call answers, its result. That result is where the session handle arrives: the host forwards
      * the server's `CallToolResult` whole, `structuredContent` included, so the handle rides there and the `ui://`
      * resource stays generic and cacheable.
      *
      * From then on the view reaches its session with two tool calls the host relays: one carrying an event, whose
      * answer is what that event produced, and one carrying nothing but a cursor, which the server holds open until
      * there is something to send. Both answer frames, and both acknowledge what the view already has, so the session
      * forgets what it no longer needs to replay. Every event is numbered by the view, so a call the host retries is
      * applied once.
      *
      * The socket, when the handle names one, carries the same session from the same cursor: the view presents a
      * short-lived token in its first frame, since an iframe in a sandbox has no credentials to send. While it is live
      * the polling stops, and it starts again the moment the socket goes, from the cursor the socket reached. That is
      * the whole of the switch, in both directions, and it loses nothing because neither transport owns the frames.
      *
      * Polling pauses while the document is hidden. The bridge has no offscreen signal in either direction at the
      * revision this was written against, and a view is a real document, so the Page Visibility API is what answers.
      */
    val appsJs: String =
        """function kyoAppsTransport(cfg){
          |  var parent=window.parent,nextId=1,pending={},ready=false,stopped=false;
          |  var handle=null,cursor=0,acked=0,clientSeq=0,polling=false,sock=null,sockLive=false,backoff=0,down=false;
          |  var dialed=false;
          |  function send(m){m.jsonrpc="2.0";parent.postMessage(m,"*");}
          |  function request(method,params){
          |    var id=nextId++;
          |    return new Promise(function(resolve,reject){
          |      pending[id]={resolve:resolve,reject:reject};
          |      send({id:id,method:method,params:params});
          |    });
          |  }
          |  function answer(id,result){send({id:id,result:result});}
          |  // The host's context carries the theme and a closed set of CSS custom properties. A view that ignores them
          |  // renders in its own colors inside a host that is not, so they go on the document element, where a page's
          |  // own CSS can read them, and are reapplied whenever the host says one changed.
          |  function applyContext(ctx){
          |    if(!ctx)return;
          |    var root=document.documentElement;
          |    if(ctx.theme){root.setAttribute("data-kyo-theme",ctx.theme);root.style.colorScheme=ctx.theme;}
          |    var vars=ctx.styles&&ctx.styles.variables;
          |    if(vars)for(var k in vars)if(Object.prototype.hasOwnProperty.call(vars,k)&&vars[k]!==undefined)root.style.setProperty(k,vars[k]);
          |  }
          |  // The handle the tool call minted, read from the result the host forwards whole.
          |  function adopt(result){
          |    if(handle||!result)return;
          |    var found=result.structuredContent&&result.structuredContent.kyoUi;
          |    if(!found||!found.session)return;
          |    handle=found;
          |    kyoCarrier("host");
          |    kyoAttached();
          |    pump();
          |  }
          |  function frames(result){
          |    var body=result&&result.structuredContent&&result.structuredContent.kyoUi;
          |    if(!body)return;
          |    var list=body.frames||[];
          |    for(var i=0;i<list.length;i++){cursor=list[i].seq;kyoApply(list[i].op);}
          |    if(body.ended||body.restart)done(body.ended||"the view has to be mounted again");
          |  }
          |  function done(reason){
          |    if(stopped)return;
          |    stopped=true;kyoDetached();closeSocket();
          |    // The host decides whether to unmount; this is the view saying it has nothing more to show.
          |    send({method:"ui/notifications/request-teardown",params:{}});
          |    if(window.console&&console.info)console.info("kyo-ui: the session ended: "+reason);
          |  }
          |  function call(name,args){
          |    args.session=handle.session;args.cursor=cursor;args.ack=cursor;
          |    acked=cursor;
          |    return request("tools/call",{name:name,arguments:args});
          |  }
          |  // One poll at a time, and none while the socket carries the session or the document is hidden. The server
          |  // holds a poll open until there is a frame, so this is a wait rather than a spin; a call that fails backs
          |  // off, because a host that cannot reach the server now may reach it shortly.
          |  //
          |  // A refusal is reported as a lost link the way the socket's close is. A host stops relaying for reasons
          |  // that have nothing to do with this server: a mode that does not permit a view's calls, a policy, a server
          |  // it cannot reach just now. Retrying silently would leave the view showing what it last rendered with
          |  // nothing to say it is no longer current, and would go on firing events at a host that is refusing them.
          |  function pump(){
          |    if(stopped||polling||sockLive||!handle)return;
          |    if(document.hidden)return;
          |    polling=true;
          |    call(cfg.poll,{}).then(function(result){
          |      polling=false;backoff=0;
          |      if(down){down=false;kyoAttached();}
          |      // A socket that went live while this was in flight owns the session now. Applying these frames on top
          |      // would be two transports writing one document from two cursors, which is the state the switch exists
          |      // to avoid.
          |      if(sockLive)return;
          |      // One op that cannot be applied must not take the session with it. Re-arming is unconditional: a view
          |      // that stopped polling is a view that is quietly dead and still showing what it last rendered, which
          |      // is worse than a frame that did not render and said so.
          |      try{frames(result);}catch(error){kyoClientError(error);}
          |      // Dialed only once the view has what the session has sent so far, so the socket resumes from a real
          |      // cursor. Dialing at the same moment polling starts sends a hello carrying nothing, and the session is
          |      // then drained down both at once: the document is mounted twice, the range map is rebuilt under ops
          |      // already in flight, and every op addressed against the old map throws into the dispatcher's catch and
          |      // is swallowed. The board stops rendering while the socket is still perfectly healthy.
          |      if(handle.socket&&!dialed){dialed=true;upgrade();}
          |      pump();
          |    },function(){
          |      polling=false;
          |      if(!down){down=true;kyoDetached();}
          |      var wait=Math.min(250*Math.pow(2,backoff),5000);backoff++;
          |      setTimeout(pump,wait);
          |    });
          |  }
          |  // An event is numbered by the view, so the server applies the ones it gets in the order they were raised
          |  // and applies a retried one once. Its answer carries what it produced, which is what makes an interaction
          |  // one round trip rather than an interaction and then a poll.
          |  function raise(text){deliver(++clientSeq,text,0);}
          |  // The number is drawn once and every attempt carries it. Drawing a fresh one per attempt would leave the
          |  // server waiting for the number that was refused, holding everything raised after it, and past its bound
          |  // ending the session: one refusal would cost the whole of it.
          |  function deliver(seq,text,tries){
          |    if(stopped)return;
          |    if(sockLive&&sock&&sock.readyState===1){sock.send(JSON.stringify({t:"e",seq:seq,event:JSON.parse(text)}));return;}
          |    call(cfg.events,{seq:seq,event:JSON.parse(text)}).then(function(result){
          |      try{frames(result);}catch(error){kyoClientError(error);}
          |      pump();
          |    },function(){
          |      var wait=Math.min(250*Math.pow(2,tries),5000);
          |      setTimeout(function(){deliver(seq,text,tries+1);},wait);
          |    });
          |  }
          |  function upgrade(){
          |    if(stopped||sock||!handle||!handle.socket)return;
          |    var s=new WebSocket(handle.socket);
          |    sock=s;
          |    s.onopen=function(){
          |      if(s!==sock)return;
          |      s.send(JSON.stringify({t:"hello",token:handle.token,session:handle.session,cursor:cursor}));
          |    };
          |    s.onmessage=function(e){
          |      if(s!==sock)return;
          |      var m=JSON.parse(e.data);
          |      // `end` is the session saying there will be no more of it, wherever it is read. `restart` on a socket is
          |      // not that: the only thing that sends it here is a refused upgrade, and a refusal says nothing about the
          |      // session, which the tool calls are still carrying. Treating the two alike stopped a working view dead
          |      // over an expired token, which is a minute old by default.
          |      if(m.end){done(m.end);return;}
          |      if(m.restart){sock=null;sockLive=false;kyoCarrier("host");s.close();pump();return;}
          |      if(m.seq===undefined)return;
          |      // The socket is live once it has carried a frame, which is the rule every kyo-ui transport follows.
          |      if(!sockLive)kyoCarrier("socket");
          |      sockLive=true;cursor=m.seq;
          |      // Guarded for the reason the poll loop is: an op that cannot be applied costs that op. Unguarded it
          |      // throws out of the handler, the socket goes on delivering and advancing the cursor, and nothing
          |      // renders again, which reads as a frozen board on a healthy link.
          |      try{kyoApply(m.op);}catch(error){kyoClientError(error);}
          |      if(cursor-acked>16){acked=cursor;s.send(JSON.stringify({t:"a",ack:cursor}));}
          |    };
          |    s.onerror=function(){};
          |    // Falling back is not a failure mode, it is the other half of the transport: the session is untouched and
          |    // the poll loop picks it up from the cursor the socket reached.
          |    s.onclose=function(){if(s!==sock)return;sock=null;sockLive=false;kyoCarrier("host");pump();};
          |  }
          |  function closeSocket(){if(sock){var s=sock;sock=null;sockLive=false;s.close();}}
          |  window.addEventListener("message",function(event){
          |    if(event.source!==parent)return;
          |    var data=event.data;
          |    if(!data||data.jsonrpc!=="2.0")return;
          |    if(data.id!==undefined&&(data.result!==undefined||data.error!==undefined)){
          |      var waiter=pending[data.id];
          |      if(!waiter)return;
          |      delete pending[data.id];
          |      if(data.error)waiter.reject(new Error(data.error.message||"the request failed"));
          |      else waiter.resolve(data.result);
          |      return;
          |    }
          |    var params=data.params||{};
          |    if(data.method==="ping"){answer(data.id,{});}
          |    else if(data.method==="ui/resource-teardown"){done("the host is unmounting the view");answer(data.id,{});}
          |    else if(data.method==="ui/notifications/tool-result"){adopt(params);}
          |    else if(data.method==="ui/notifications/host-context-changed"){applyContext(params);}
          |  });
          |  // The view tells the host how tall it is, so the host can size the frame it is in. Sent on every change of
          |  // the document's own height, which every reactive update may produce.
          |  function measure(){
          |    var el=document.documentElement;
          |    send({method:"ui/notifications/size-changed",params:{width:el.scrollWidth,height:el.scrollHeight}});
          |  }
          |  document.addEventListener("visibilitychange",function(){if(!document.hidden)pump();});
          |  return{
          |    open:function(){
          |      if(typeof ResizeObserver==="function")new ResizeObserver(measure).observe(document.documentElement);
          |      request("ui/initialize",{
          |        appInfo:{name:"kyo-ui",version:"1"},
          |        appCapabilities:{},
          |        protocolVersion:cfg.protocolVersion
          |      }).then(function(result){
          |        ready=true;
          |        applyContext(result&&result.hostContext);
          |        send({method:"ui/notifications/initialized",params:{}});
          |        measure();
          |      },function(e){
          |        if(window.console&&console.error)console.error("kyo-ui: the app handshake failed: "+e.message);
          |      });
          |    },
          |    // Always taken: the event is numbered, so delivering it is this transport's own business and a caller
          |    // that buffered it instead would have to number it too.
          |    write:function(m){if(stopped||!ready||!handle)return false;raise(m);return true;},
          |    suspend:function(){stopped=true;closeSocket();},
          |    // A restored page dials again, and for the same reason it does not dial here: the poll that comes back
          |    // first is what gives the socket a cursor to resume from.
          |    resume:function(){if(!handle)return;stopped=false;backoff=0;dialed=false;pump();},
          |    close:function(){stopped=true;closeSocket();}
          |  };
          |}""".stripMargin

    /** The expression that builds the transport a view mounted on MCP Apps uses. */
    def appsBridge(events: String, poll: String, protocolVersion: String): String =
        s"""kyoAppsTransport({events:"$events",poll:"$poll",protocolVersion:"$protocolVersion"})"""

end UIClientTransport
