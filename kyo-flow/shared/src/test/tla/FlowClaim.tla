-------------------------------- MODULE FlowClaim --------------------------------
(***************************************************************************)
(* The claim lifecycle and the readiness rule of the kyo-flow engine: the  *)
(* part of the design where an execution can be lost, duplicated or        *)
(* stranded, and where an argument on paper is not evidence. Comments      *)
(* below use D-A for the readiness rule and D-B for the claim lifecycle    *)
(* and its write authorisation.                                            *)
(*                                                                         *)
(* WHAT THE CONTROLS ARE FOR. Every rule the design states has a control   *)
(* configuration that removes that rule and names the property which must  *)
(* then fail. A control that stays green is a defect in the model rather   *)
(* than a result, so check.sh asserts the polarity of every configuration  *)
(* instead of leaving it to someone reading a log. Two failures in         *)
(* particular are what the controls aim at, because both are invisible to  *)
(* a run without them: a generation token that refuses a SUPERSEDED writer *)
(* but not a LAPSED one nobody replaced, which lets an executor whose      *)
(* lease expired with no competitor write through to a terminal status;    *)
(* and an UNSCOPED cancel arm in readiness, which hands a version-orphaned *)
(* execution to an executor that cannot interpret it, so it parks,         *)
(* releases, and is handed back on the next poll forever.                  *)
(*                                                                         *)
(* FOUR RULES CARRY THE DESIGN, and each has its own control. Suspension   *)
(* is a per-branch recordWait under the held claim plus ONE ending         *)
(* transition. finish is the only maker of an absent claim. Readiness has  *)
(* an active-and-expired arm. Retirement keeps exactly the rows the        *)
(* attempt is still waiting on.                                            *)
(*                                                                         *)
(* RUNNING IT. ./check.sh runs every configuration and prints one line per *)
(* config; ./check.sh <Name> runs one. It fetches tla2tools.jar on first   *)
(* use. Neither the build nor CI runs any of this, so a change to the      *)
(* claim rule has to re-run it by hand.                                    *)
(*                                                                         *)
(* MODELLING DECISIONS.                                                    *)
(*                                                                         *)
(* Every write goes through the store's acceptance rule. The anonymous     *)
(* field write is modelled in flight (issued, then delivered later, so the *)
(* executor's belief can change in between). recordWait, recordProgress    *)
(* and both finish arms are modelled as synchronous store-judged           *)
(* transitions: the store accepts or refuses at the moment of the call,    *)
(* and a refusal ends the executor's belief, which is the ClaimLost path.  *)
(*                                                                         *)
(* An ATTEMPT is: Claim, then any number of recordWait / recordProgress /  *)
(* decide-against steps under the held claim, then ONE ending. The ending  *)
(* is FinishTerminal or FinishSuspended, and those are the only makers of  *)
(* an absent claim. Every other way an attempt can end (a crash, the store *)
(* failing under it, a shutdown interrupting it, a lost claim) writes      *)
(* NOTHING and leaves the claim to expire: Crash and Relinquish stand for  *)
(* all of them, and both are bounded or guarded, because an unbounded      *)
(* supply of attempts that end without finishing is a starvation the       *)
(* design's liveness assumptions already exclude.                          *)
(*                                                                         *)
(* Ground truth is separate from the mechanism. The mechanism is the       *)
(* validity bit the store keeps per token (heldValid, pendValid), updated  *)
(* by the design's rules and by whichever mutation a control switches on.  *)
(* The ground truth is a staleness bit per belief and per issued write     *)
(* (heldStale, pendStale): set by every event that ends the claim instance *)
(* it was formed under (a new claim on the row, a finish), and never read  *)
(* by any guard or touched by any constant. It is the same fact an epoch   *)
(* counter would record, without the counter's state cost. A write is      *)
(* stale when its bit is set or the claim has lapsed.                      *)
(*                                                                         *)
(* The interpreter's live parks (pending) and the store's ledger (waits)   *)
(* are separate variables, and the design's claim is that they agree at    *)
(* every point where anyone reads the ledger, which is when the claim is   *)
(* absent (LedgerBlessed). They diverge legitimately DURING an attempt: a  *)
(* raced branch the composition decides against leaves pending while its   *)
(* row stays in the ledger until the ending retires it (DecideAgainst).    *)
(* That divergence is what makes the wedge and the loser row reachable,    *)
(* and a model without it can check neither.                               *)
(*                                                                         *)
(* Time is abstracted away. A claim may lapse. ExpireAbandoned is FAIR and *)
(* fires only when nobody holds the claim, which is what makes recovery    *)
(* work. ExpireHeld is UNFAIR and bounded, and models the renewal loop     *)
(* failing under a live holder; it is the only way a stale belief becomes  *)
(* reachable, and the Vacuity control checks that it is.                   *)
(*                                                                         *)
(* Signals are environment. A timer always fires (fair). A field may never *)
(* arrive, so Spec puts no fairness on it; SpecSignals adds it, and is the *)
(* explicit assumption under which every execution terminates.             *)
(*                                                                         *)
(* One deployment. Every execution runs the same version; versionServed    *)
(* says whether the engine currently serves it. A redeploy stops serving   *)
(* it and a rollback resumes. There is no wait row for a definition: an    *)
(* execution whose version is not served is simply never returned, which   *)
(* is the readiness rule's third condition.                                *)
(*                                                                         *)
(* NOT MODELLED. Compensations: a cancel finishes as Cancelled, and the    *)
(* model does not check that handlers ran, nor an unwind that itself parks *)
(* (see FinishSuspended). Renewal as an action: its failure is ExpireHeld, *)
(* its success is the absence of that. Multiple deployments serving        *)
(* different versions at once. A wake's CONTENT: a row is a name, so "a    *)
(* re-recorded sleep keeps its original deadline" is modelled as the row's *)
(* satisfaction surviving the re-record, and the OverwriteWake control     *)
(* abstracts a deadline pushed forward on every replay into a row the      *)
(* model's clock can no longer reach.                                      *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS
    Executions,      \* finite set of execution ids
    Executors,       \* finite set of executor ids
    MaxCrashes,      \* how often the environment may crash an executor
    MaxLapses,       \* how often a renewal may fail under a live holder
    MaxSuspends,     \* how many suspension points the flows collectively reach
    MaxRedeploys,    \* how many times the environment may stop serving the version
    ClearsWaits,     \* FALSE reintroduces the missing wait-row clearing
    TokenOnlyWrites, \* the write kinds whose acceptance omits the expiry check
    OwnerWitness,    \* TRUE accepts field writes on owner identity, not the token
    RetireOnRelease, \* FALSE leaves the generation live after an ending
    UnscopedCancel,  \* TRUE lets a cancel request bypass the version gate
    CancelArm,       \* FALSE removes the cancel arm from readiness
    ExpiredArm,      \* FALSE removes readiness's active-and-expired arm (D-A 4d)
    KeepExactly,     \* FALSE makes a suspending ending keep what was recorded
    OverwriteWake,   \* TRUE makes re-recording a wait move it, instead of put-if-absent
    NoOwner,         \* model value: unclaimed, or holding nothing
    NoWrite          \* model value: no write in flight

WriteKinds == {"field", "finish", "recordWait", "progress"}
ASSUME TokenOnlyWrites \subseteq WriteKinds

Terminal  == {"Completed", "Cancelled"}
Lifecycle == {"Running"} \cup Terminal

\* Wait rows are keyed by node, as the ledger is. Two sleeps can coexist on one
\* execution (two sibling branches, or a sleep racing an input), which is the
\* plural case D-A exists for.
Rows     == {"sleepA", "sleepB", "input"}
Timer(r) == r \in {"sleepA", "sleepB"}

\* Machine variables.
VARIABLES
    status,        \* [Executions -> Lifecycle]
    pending,       \* [Executions -> SUBSET Rows]  the interpreter's live parks
    waits,         \* [Executions -> SUBSET Rows]  the store's wait ledger
    satisfied,     \* [Executions -> SUBSET Rows]  rows whose wake condition the environment has met
    fields,        \* [Executions -> SUBSET Rows]  paths that carry a recorded progress field
    moved,         \* [Executions -> SUBSET Rows]  rows whose wake was pushed out of reach
    owner,         \* [Executions -> Executors \cup {NoOwner}]
    expired,       \* [Executions -> BOOLEAN]
    cancelReq,     \* [Executions -> BOOLEAN]
    versionServed, \* BOOLEAN: does the deployment serve the version the executions run
    heldExec,      \* [Executors -> Executions \cup {NoOwner}]  what x believes it holds
    heldValid,     \* [Executors -> BOOLEAN]  is that belief still the live claim (the token)
    heldServed,    \* [Executors -> BOOLEAN]  could x resolve the definition when it claimed
    pendExec,      \* [Executors -> Executions \cup {NoWrite}]  an issued, undelivered field write
    pendValid      \* [Executors -> BOOLEAN]  was it issued under a generation still live (the token)

\* Auxiliary: ground truth read only by properties, and the adversary budgets.
VARIABLES
    heldStale,     \* [Executors -> BOOLEAN]  x's belief was formed under a claim instance the row has left
    pendStale,     \* [Executors -> BOOLEAN]  x's in-flight write was issued under such an instance
    lapsedWrite,   \* a write ground truth refuses was applied
    uselessClaim,  \* the store returned an execution the claimer could do nothing with
    refusedEver,   \* the store refused at least one write (a reachability probe)
    crashes, lapses, suspends, redeploys

vars == <<status, pending, waits, satisfied, fields, moved, owner, expired, cancelReq,
          versionServed, heldExec, heldValid, heldServed, pendExec, pendValid,
          heldStale, pendStale, lapsedWrite, uselessClaim, refusedEver,
          crashes, lapses, suspends, redeploys>>

TypeOK ==
    /\ status        \in [Executions -> Lifecycle]
    /\ pending       \in [Executions -> SUBSET Rows]
    /\ waits         \in [Executions -> SUBSET Rows]
    /\ satisfied     \in [Executions -> SUBSET Rows]
    /\ fields        \in [Executions -> SUBSET Rows]
    /\ moved         \in [Executions -> SUBSET Rows]
    /\ owner         \in [Executions -> Executors \cup {NoOwner}]
    /\ expired       \in [Executions -> BOOLEAN]
    /\ cancelReq     \in [Executions -> BOOLEAN]
    /\ versionServed \in BOOLEAN
    /\ heldExec      \in [Executors -> Executions \cup {NoOwner}]
    /\ heldValid     \in [Executors -> BOOLEAN]
    /\ heldServed    \in [Executors -> BOOLEAN]
    /\ pendExec      \in [Executors -> Executions \cup {NoWrite}]
    /\ pendValid     \in [Executors -> BOOLEAN]
    /\ heldStale     \in [Executors -> BOOLEAN]
    /\ pendStale     \in [Executors -> BOOLEAN]
    /\ lapsedWrite   \in BOOLEAN
    /\ uselessClaim  \in BOOLEAN
    /\ refusedEver   \in BOOLEAN
    /\ crashes       \in 0..MaxCrashes
    /\ lapses        \in 0..MaxLapses
    /\ suspends      \in 0..MaxSuspends
    /\ redeploys     \in 0..MaxRedeploys

Init ==
    /\ status        = [e \in Executions |-> "Running"]
    /\ pending       = [e \in Executions |-> {}]
    /\ waits         = [e \in Executions |-> {}]
    /\ satisfied     = [e \in Executions |-> {}]
    /\ fields        = [e \in Executions |-> {}]
    /\ moved         = [e \in Executions |-> {}]
    /\ owner         = [e \in Executions |-> NoOwner]
    /\ expired       = [e \in Executions |-> FALSE]
    /\ cancelReq     = [e \in Executions |-> FALSE]
    /\ versionServed = TRUE
    /\ heldExec      = [x \in Executors |-> NoOwner]
    /\ heldValid     = [x \in Executors |-> FALSE]
    /\ heldServed    = [x \in Executors |-> FALSE]
    /\ pendExec      = [x \in Executors |-> NoWrite]
    /\ pendValid     = [x \in Executors |-> FALSE]
    /\ heldStale     = [x \in Executors |-> FALSE]
    /\ pendStale     = [x \in Executors |-> FALSE]
    /\ lapsedWrite   = FALSE
    /\ uselessClaim  = FALSE
    /\ refusedEver   = FALSE
    /\ crashes       = 0
    /\ lapses        = 0
    /\ suspends      = 0
    /\ redeploys     = 0

(***************************************************************************)
(* Readiness: D-A's four conditions, evaluated by the store over its own    *)
(* ledger. The version gate is universal (condition 3); the cancel arm and  *)
(* the active-and-expired arm each override every wait row (4a and 4d).     *)
(***************************************************************************)
ClaimAbsent(e) == owner[e] = NoOwner

\* The claim is active and expired: the last attempt DIED mid-flight, because
\* finish is the only maker of an absent claim. Its rows may describe waits the
\* execution is no longer in, and only a new attempt's replay and finish can
\* heal them, so the rows do not gate.
Dead(e) == owner[e] /= NoOwner /\ expired[e]

ClaimFree(e)   == ClaimAbsent(e) \/ expired[e]
VersionGate(e) == versionServed \/ (UnscopedCancel /\ cancelReq[e])
Wake(e) ==
    \/ (CancelArm /\ cancelReq[e])
    \/ waits[e] = {}
    \/ \E r \in waits[e] : r \in satisfied[e]
    \/ (ExpiredArm /\ Dead(e))
Ready(e) ==
    /\ status[e] \notin Terminal
    /\ ClaimFree(e)
    /\ VersionGate(e)
    /\ Wake(e)

\* Ground truth for "the claimer has something to do", stated over the
\* interpreter's state rather than the ledger: it can resolve the definition,
\* and either a cancel is outstanding, or the last attempt died and the replay
\* is the work that heals the ledger, or nothing is parked, or a parked
\* branch's wake condition is met.
\*
\* The Dead arm is what arm (d) buys and what it costs, stated once: the store
\* cannot know whether a dead attempt's rows need healing, so it returns the
\* execution and the replay decides. It cannot spin, because Dead is false
\* immediately after the claim and becomes true again only through a lapse or an
\* abandonment, both of which cost the adversary a budgeted step.
HasWork(e) ==
    /\ versionServed
    /\ \/ cancelReq[e]
       \/ Dead(e)
       \/ pending[e] = {}
       \/ \E r \in pending[e] : r \in satisfied[e]

(***************************************************************************)
(* Write authorisation: D-B's rule, judged by the store. The token is the   *)
(* validity bit; TokenOnlyWrites names the write kinds that drop the expiry *)
(* check, which is defect 1 for whichever kind is named.                    *)
(***************************************************************************)
HeldAuthorised(x) == heldValid[x] /\ ~expired[heldExec[x]]

StoreAccepts(x, kind) ==
    heldValid[x] /\ (kind \in TokenOnlyWrites \/ ~expired[heldExec[x]])

\* Ground truth, independent of the mechanism: the belief was formed under a
\* claim instance the row has since left, or the claim has lapsed.
StaleBelief(x) == heldStale[x] \/ expired[heldExec[x]]

Drop(x) ==
    /\ heldExec'  = [heldExec  EXCEPT ![x] = NoOwner]
    /\ heldValid' = [heldValid EXCEPT ![x] = FALSE]

\* The row's claim instance ends (a finish by x): x's own in-flight write, if
\* any, is now stale in ground truth. Any other executor's write on the row was
\* already stale, since x held the instance.
EndInstance(x) ==
    pendStale' = [pendStale EXCEPT ![x] = TRUE]

(***************************************************************************)
(* Actions                                                                 *)
(***************************************************************************)

\* The store grants a claim: a fresh generation on the row, which invalidates
\* every belief and every in-flight write that referred to the previous one.
\* Records whether the claimer can resolve the definition, and whether the
\* store has just returned an execution the claimer can do nothing with.
\*
\* heldServed is TRUE at every claim under the design, because Ready already
\* requires the version to be served; it is FALSE only under UnscopedCancel. The
\* guards on it in the attempt's verbs therefore never block in Designed.cfg,
\* and a coverage reader should expect that: they are the statement of what an
\* executor that cannot resolve the definition cannot do, and the control is
\* where they bite.
Claim(x, e) ==
    /\ heldExec[x] = NoOwner
    /\ Ready(e)
    /\ owner'        = [owner    EXCEPT ![e] = x]
    /\ expired'      = [expired  EXCEPT ![e] = FALSE]
    /\ heldExec'     = [heldExec   EXCEPT ![x] = e]
    /\ heldServed'   = [heldServed EXCEPT ![x] = versionServed]
    /\ heldValid'    = [y \in Executors |->
                           IF y = x THEN TRUE
                           ELSE IF heldExec[y] = e THEN FALSE
                           ELSE heldValid[y]]
    /\ heldStale'    = [y \in Executors |->
                           IF y = x THEN FALSE
                           ELSE IF heldExec[y] = e THEN TRUE
                           ELSE heldStale[y]]
    /\ pendValid'    = [y \in Executors |->
                           IF pendExec[y] = e THEN FALSE ELSE pendValid[y]]
    /\ pendStale'    = [y \in Executors |->
                           IF pendExec[y] = e THEN TRUE ELSE pendStale[y]]
    /\ uselessClaim' = (uselessClaim \/ ~HasWork(e))
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, cancelReq,
                   versionServed, pendExec, lapsedWrite, refusedEver,
                   crashes, lapses, suspends, redeploys>>

\* A lease whose holder is GONE always lapses. Fair: this is what makes recovery
\* work, and modelling it as optional lets a crashed executor's claim starve a
\* row forever.
ExpireAbandoned(e) ==
    /\ owner[e] /= NoOwner
    /\ \A x \in Executors : heldExec[x] /= e
    /\ ~expired[e]
    /\ expired' = [expired EXCEPT ![e] = TRUE]
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, owner, cancelReq,
                   versionServed, heldExec, heldValid, heldServed, pendExec, pendValid,
                   heldStale, pendStale, lapsedWrite, uselessClaim, refusedEver,
                   crashes, lapses, suspends, redeploys>>

\* A lease lapses UNDER a live holder: the renewal loop failed. Unfair and
\* bounded, because it is adversary behaviour rather than protocol behaviour.
\* It is the only way a stale belief becomes reachable; without it every refusal
\* path is dead and every safety result is worthless. Vacuity.cfg checks that.
ExpireHeld(e) ==
    /\ owner[e] /= NoOwner
    /\ \E x \in Executors : heldExec[x] = e
    /\ ~expired[e]
    /\ lapses < MaxLapses
    /\ lapses'  = lapses + 1
    /\ expired' = [expired EXCEPT ![e] = TRUE]
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, owner, cancelReq,
                   versionServed, heldExec, heldValid, heldServed, pendExec, pendValid,
                   heldStale, pendStale, lapsedWrite, uselessClaim, refusedEver,
                   crashes, suspends, redeploys>>

\* A field write is issued under whatever the executor believes. No guard on the
\* row's status: a stale believer does not know it, and the store's job is to
\* refuse it.
IssueWrite(x, e) ==
    /\ heldExec[x] = e
    /\ pendExec[x] = NoWrite
    /\ pendExec'  = [pendExec  EXCEPT ![x] = e]
    /\ pendValid' = [pendValid EXCEPT ![x] = heldValid[x]]
    /\ pendStale' = [pendStale EXCEPT ![x] = heldStale[x]]
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, owner, expired,
                   cancelReq, versionServed, heldExec, heldValid, heldServed, heldStale,
                   lapsedWrite, uselessClaim, refusedEver, crashes, lapses, suspends, redeploys>>

\* The store judges the field write at DELIVERY. The mechanism is the token
\* (pendValid) plus the expiry check; OwnerWitness swaps the token for owner
\* identity, which is the witness D-B rejects, and the ABA trace (claim, issue,
\* lose the claim, reclaim, deliver) is what tells them apart.
DeliverWrite(x) ==
    /\ pendExec[x] /= NoWrite
    /\ LET e       == pendExec[x]
           token   == IF OwnerWitness THEN owner[e] = x ELSE pendValid[x]
           accepts == token /\ ("field" \in TokenOnlyWrites \/ ~expired[e])
           stale   == pendStale[x] \/ expired[e]
       IN /\ lapsedWrite' = (lapsedWrite \/ (accepts /\ stale))
          /\ refusedEver' = (refusedEver \/ ~accepts)
    /\ pendExec'  = [pendExec  EXCEPT ![x] = NoWrite]
    /\ pendValid' = [pendValid EXCEPT ![x] = FALSE]
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, owner, expired,
                   cancelReq, versionServed, heldExec, heldValid, heldServed, heldStale,
                   pendStale, uselessClaim, crashes, lapses, suspends, redeploys>>

\* pending is the EXECUTION's parked set, the durable fact a replay reconstructs,
\* not any one executor's memory, so the two interpreter-side actions below are
\* guarded on the actor genuinely holding the live claim. A stale executor keeps
\* running until its renewal is refused (D-E.8) and its own parks are real to
\* it, but nothing it does lands: every store write it attempts is refused by
\* the fence. Without this guard a stale executor parks a branch of an execution
\* somebody else has already finished, and the model reports a divergence
\* between ledger and interpreter that no store could ever see. TLC found that
\* first, and it is a model artifact, not a defect.
\*
\* A branch of the flow parks. Interpreter-side only: the store hears nothing
\* until the branch's recordWait lands, and the gap between the two is the
\* window an engine shut down between two parking branches dies in, where the
\* sleep's row was never written and the durable timeout is silently gone. In
\* the real system the park IS the recordWait call, which is why RecordWait is
\* fair: the two halves are one call, and they are separate actions here only so
\* that the state a death between them leaves is reachable at all.
\*
\* Write-once (D-B): a path that already carries a progress field never parks
\* again, because a node's wait is discharged once.
Park(x, e, r) ==
    /\ heldExec[x] = e
    /\ heldServed[x]
    /\ HeldAuthorised(x)
    /\ r \notin pending[e]
    /\ r \notin fields[e]
    /\ suspends < MaxSuspends
    /\ suspends' = suspends + 1
    /\ pending'  = [pending EXCEPT ![e] = @ \cup {r}]
    /\ UNCHANGED <<status, waits, satisfied, fields, moved, owner, expired, cancelReq,
                   versionServed, heldExec, heldValid, heldServed, pendExec, pendValid,
                   heldStale, pendStale, lapsedWrite, uselessClaim, refusedEver,
                   crashes, lapses, redeploys>>

\* One parking branch records its own row, under the claim, and does NOT
\* release. This is the split D-B made: a rule that released here would make the
\* second branch's row unwritable and hand the design F2's pathology back.
RecordWait(x, e, r) ==
    /\ heldExec[x] = e
    /\ heldServed[x]
    /\ r \in pending[e]
    /\ r \notin waits[e]
    /\ IF StoreAccepts(x, "recordWait")
         THEN /\ lapsedWrite' = (lapsedWrite \/ StaleBelief(x))
              /\ refusedEver' = refusedEver
              /\ waits'       = [waits EXCEPT ![e] = @ \cup {r}]
              /\ UNCHANGED <<heldExec, heldValid>>
         ELSE /\ refusedEver' = TRUE
              /\ Drop(x)
              /\ UNCHANGED <<lapsedWrite, waits>>
    /\ UNCHANGED <<status, pending, satisfied, fields, moved, owner, expired, cancelReq,
                   versionServed, heldServed, pendExec, pendValid, heldStale, pendStale,
                   uselessClaim, crashes, lapses, suspends, redeploys>>

\* A replay re-parks a branch whose row the ledger already has. recordWait is
\* put-if-absent on the WAKE: the row keeps what it was created with, which is
\* what "a sleep re-recorded on a later attempt keeps its original deadline"
\* means. OverwriteWake is the store that moves it instead, and the model's
\* stand-in for a deadline pushed forward on every replay is a row the clock can
\* no longer reach.
ReRecordWait(x, e, r) ==
    /\ heldExec[x] = e
    /\ heldServed[x]
    /\ r \in pending[e]
    /\ r \in waits[e]
    /\ IF StoreAccepts(x, "recordWait")
         THEN /\ lapsedWrite' = (lapsedWrite \/ StaleBelief(x))
              /\ refusedEver' = refusedEver
              /\ IF OverwriteWake
                   THEN /\ satisfied' = [satisfied EXCEPT ![e] = @ \ {r}]
                        /\ moved'     = [moved     EXCEPT ![e] = @ \cup {r}]
                   ELSE UNCHANGED <<satisfied, moved>>
              /\ UNCHANGED <<heldExec, heldValid>>
         ELSE /\ refusedEver' = TRUE
              /\ Drop(x)
              /\ UNCHANGED <<lapsedWrite, satisfied, moved>>
    /\ UNCHANGED <<status, pending, waits, fields, owner, expired, cancelReq, versionServed,
                   heldServed, pendExec, pendValid, heldStale, pendStale, uselessClaim,
                   crashes, lapses, suspends, redeploys>>

\* The environment meets a row's wake condition: a timer fires, or a field
\* arrives. A row whose wake was moved out of reach is never met again.
SatisfyWait(e, r) ==
    /\ r \in waits[e]
    /\ r \notin satisfied[e]
    /\ r \notin moved[e]
    /\ satisfied' = [satisfied EXCEPT ![e] = @ \cup {r}]
    /\ UNCHANGED <<status, pending, waits, fields, moved, owner, expired, cancelReq,
                   versionServed, heldExec, heldValid, heldServed, pendExec, pendValid,
                   heldStale, pendStale, lapsedWrite, uselessClaim, refusedEver,
                   crashes, lapses, suspends, redeploys>>

\* recordProgress: ONE transition writes the node's field and clears the node's
\* wait row, so no interleaving observes the field present with the row still
\* outstanding. That is the leaf "a step's field and its completion event are
\* written together or not at all" at this model's grain, and ProgressAtomic is
\* the property; ClearsWaits = FALSE is the store that writes the field and
\* leaves the row.
RecordProgress(x, e, r) ==
    /\ heldExec[x] = e
    /\ heldServed[x]
    /\ r \in pending[e]
    /\ r \in satisfied[e]
    /\ r \notin fields[e]
    /\ IF StoreAccepts(x, "progress")
         THEN /\ lapsedWrite' = (lapsedWrite \/ StaleBelief(x))
              /\ refusedEver' = refusedEver
              /\ fields'      = [fields  EXCEPT ![e] = @ \cup {r}]
              /\ pending'     = [pending EXCEPT ![e] = @ \ {r}]
              /\ IF ClearsWaits
                   THEN /\ waits'     = [waits     EXCEPT ![e] = @ \ {r}]
                        /\ satisfied' = [satisfied EXCEPT ![e] = @ \ {r}]
                   ELSE UNCHANGED <<waits, satisfied>>
              /\ UNCHANGED <<heldExec, heldValid>>
         ELSE /\ refusedEver' = TRUE
              /\ Drop(x)
              /\ UNCHANGED <<lapsedWrite, fields, pending, waits, satisfied>>
    /\ UNCHANGED <<status, moved, owner, expired, cancelReq, versionServed, heldServed,
                   pendExec, pendValid, heldStale, pendStale, uselessClaim,
                   crashes, lapses, suspends, redeploys>>

\* A sibling resolved the composition, so a parked branch is abandoned: a race's
\* loser, or a branch the unwind drops. Interpreter-side only, no store write.
\* The row it wrote stays in the ledger until the attempt's ending retires it,
\* and that gap is the loser row, the state the whole of D-A's arm (d) and D-B's
\* keep-exactly retirement are about. Bounded without a budget of its own: only
\* Park puts a branch into pending, and that is what MaxSuspends bounds.
DecideAgainst(x, e, r) ==
    /\ heldExec[x] = e
    /\ heldServed[x]
    /\ HeldAuthorised(x)
    /\ r \in pending[e]
    /\ pending' = [pending EXCEPT ![e] = @ \ {r}]
    /\ UNCHANGED <<status, waits, satisfied, fields, moved, owner, expired, cancelReq,
                   versionServed, heldExec, heldValid, heldServed, pendExec, pendValid,
                   heldStale, pendStale, lapsedWrite, uselessClaim, refusedEver,
                   crashes, lapses, suspends, redeploys>>

\* finish(Terminal): the terminal write, judged by the store like any other.
\* Allowed when nothing is parked, or when a cancel is outstanding: the cancel
\* overrides the rows and the executor unwinds without whatever they waited for.
\* Consumes the claim and retires every row, because a terminal execution's
\* waits are dead.
FinishTerminal(x, e) ==
    /\ heldExec[x] = e
    /\ heldServed[x]
    /\ pending[e] = {} \/ cancelReq[e]
    /\ IF StoreAccepts(x, "finish")
         THEN /\ lapsedWrite' = (lapsedWrite \/ StaleBelief(x))
              /\ refusedEver' = refusedEver
              /\ status'      = [status    EXCEPT ![e] = IF cancelReq[e] THEN "Cancelled" ELSE "Completed"]
              /\ pending'     = [pending   EXCEPT ![e] = {}]
              /\ waits'       = [waits     EXCEPT ![e] = {}]
              /\ satisfied'   = [satisfied EXCEPT ![e] = {}]
              /\ fields'      = [fields    EXCEPT ![e] = {}]
              /\ moved'       = [moved     EXCEPT ![e] = {}]
              /\ owner'       = [owner     EXCEPT ![e] = NoOwner]
              /\ pendValid'   = [pendValid EXCEPT ![x] = IF RetireOnRelease THEN FALSE ELSE @]
              /\ EndInstance(x)
         ELSE /\ refusedEver' = TRUE
              /\ UNCHANGED <<lapsedWrite, status, pending, waits, satisfied, fields, moved,
                             owner, pendValid, pendStale>>
    /\ Drop(x)
    /\ UNCHANGED <<expired, cancelReq, versionServed, heldServed, pendExec,
                   heldStale, uselessClaim, crashes, lapses, suspends, redeploys>>

\* finish(Suspended(waitingOn)): the attempt ends parked. It writes no status,
\* keeps EXACTLY the rows it is still waiting on, retires the rest, and makes
\* the claim absent, all in one transition.
\*
\* waitingOn is the interpreter's live parks at the ending, which is what D-B
\* means by "the attempt's end already knows its live parks, because they are
\* what ended it". It is NOT derived from the rows the episode recorded: those
\* are `waits`, and deriving from them is precisely KeepExactly = FALSE, the
\* broken variant this action's control switches on. The two differ exactly when
\* a branch was decided against or a discharge left its row behind.
\*
\* Four guards, all statements about the interpreter rather than restrictions on
\* the environment. An attempt with nothing parked did not end suspended; it
\* completed, failed, or died. An attempt does not end leaving a live park whose
\* wake condition is already met, because the interpreter would have resumed
\* that branch: the store state that interleaving reaches is reachable anyway by
\* the field arriving one step later. Every live park has its row, which is
\* D-E's obligation on the interpreter and the reason the ending can state
\* waitingOn at all; an attempt whose recordWait was refused ends ClaimLost and
\* never reaches this verb. And an attempt that finds a cancel request
\* outstanding unwinds and finishes rather than parking again, which is D-C's
\* rule for the cancel path: the model does not represent a compensation that
\* itself parks, and without this guard nothing bounds an execution that is
\* claimed and re-suspended forever without ever unwinding, which is a false
\* CancelTerminates violation rather than a defect. TLC found that too.
FinishSuspended(x, e) ==
    /\ heldExec[x] = e
    /\ heldServed[x]
    /\ ~cancelReq[e]
    /\ pending[e] /= {}
    /\ ~\E r \in pending[e] : r \in satisfied[e]
    /\ pending[e] \subseteq waits[e]
    /\ IF StoreAccepts(x, "finish")
         THEN LET keep == IF KeepExactly THEN pending[e] ELSE waits[e]
              IN /\ lapsedWrite' = (lapsedWrite \/ StaleBelief(x))
                 /\ refusedEver' = refusedEver
                 /\ waits'       = [waits     EXCEPT ![e] = keep]
                 /\ satisfied'   = [satisfied EXCEPT ![e] = @ \cap keep]
                 /\ moved'       = [moved     EXCEPT ![e] = @ \cap keep]
                 /\ owner'       = [owner     EXCEPT ![e] = NoOwner]
                 /\ pendValid'   = [pendValid EXCEPT ![x] = IF RetireOnRelease THEN FALSE ELSE @]
                 /\ EndInstance(x)
         ELSE /\ refusedEver' = TRUE
              /\ UNCHANGED <<lapsedWrite, waits, satisfied, moved, owner, pendValid, pendStale>>
    /\ Drop(x)
    /\ UNCHANGED <<status, pending, fields, expired, cancelReq, versionServed, heldServed,
                   pendExec, heldStale, uselessClaim, crashes, lapses, suspends, redeploys>>

RequestCancel(e) ==
    /\ ~cancelReq[e]
    /\ status[e] \notin Terminal
    /\ cancelReq' = [cancelReq EXCEPT ![e] = TRUE]
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, owner, expired,
                   versionServed, heldExec, heldValid, heldServed, pendExec, pendValid,
                   heldStale, pendStale, lapsedWrite, uselessClaim, refusedEver,
                   crashes, lapses, suspends, redeploys>>

\* The ClaimLost path through renewal: an executor learns its claim is gone and
\* gives up. Nothing happens store-side (finish is the only verb the store hears
\* a release through), and the in-flight write is NOT drained: it is exactly
\* what the store must refuse.
Relinquish(x) ==
    /\ heldExec[x] /= NoOwner
    /\ ~HeldAuthorised(x)
    /\ Drop(x)
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, owner, expired,
                   cancelReq, versionServed, heldServed, pendExec, pendValid, heldStale,
                   pendStale, lapsedWrite, uselessClaim, refusedEver,
                   crashes, lapses, suspends, redeploys>>

\* An attempt ends without a verdict and writes NOTHING: the claim is left to
\* expire. This is a crash, and it is also Infra, Interrupted and an engine
\* shutdown, which have the same transition (D1 measured that a scope close
\* interrupts the supervisor with the attempt, so no ending transition of any
\* kind runs). Its in-flight write stays in flight, and the claim it held stays
\* ACTIVE until the lease lapses, which is what makes the row's own state the
\* fact that says "the last word was never said".
\*
\* Bounded, and the bound is the honest one: an unbounded supply of attempts
\* that end without finishing is a starvation, not a fence question.
Crash(x) ==
    /\ heldExec[x] /= NoOwner
    /\ crashes < MaxCrashes
    /\ crashes' = crashes + 1
    /\ Drop(x)
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, owner, expired,
                   cancelReq, versionServed, heldServed, pendExec, pendValid, heldStale,
                   pendStale, lapsedWrite, uselessClaim, refusedEver, lapses, suspends, redeploys>>

\* A redeployment stops serving the version every execution runs. Bounded, and
\* the bound is an environment bound: unbounded, the served flag flickers forever
\* and Claim, Finish and SatisfyWait are enabled infinitely often but never
\* continuously, so each needs STRONG fairness, which TLC did not finish in 25
\* minutes with three families at once. The price is stated in the report: with
\* the flicker finite, weak fairness on Claim no longer distinguishes a poll that
\* keeps running from one that eventually runs.
Unregister ==
    /\ versionServed
    /\ redeploys < MaxRedeploys
    /\ redeploys'     = redeploys + 1
    /\ versionServed' = FALSE
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, owner, expired,
                   cancelReq, heldExec, heldValid, heldServed, pendExec, pendValid,
                   heldStale, pendStale, lapsedWrite, uselessClaim, refusedEver,
                   crashes, lapses, suspends>>

\* Rolling back. Fair, and that fairness is a claim about the OPERATOR rather than
\* the engine: the engine cannot promise a version-orphaned execution ever
\* finishes.
Register ==
    /\ ~versionServed
    /\ versionServed' = TRUE
    /\ UNCHANGED <<status, pending, waits, satisfied, fields, moved, owner, expired,
                   cancelReq, heldExec, heldValid, heldServed, pendExec, pendValid,
                   heldStale, pendStale, lapsedWrite, uselessClaim, refusedEver,
                   crashes, lapses, suspends, redeploys>>

Next ==
    \/ \E x \in Executors, e \in Executions : Claim(x, e)
    \/ \E e \in Executions : ExpireAbandoned(e)
    \/ \E e \in Executions : ExpireHeld(e)
    \/ \E x \in Executors, e \in Executions : IssueWrite(x, e)
    \/ \E x \in Executors : DeliverWrite(x)
    \/ \E x \in Executors, e \in Executions, r \in Rows : Park(x, e, r)
    \/ \E x \in Executors, e \in Executions, r \in Rows : RecordWait(x, e, r)
    \/ \E x \in Executors, e \in Executions, r \in Rows : ReRecordWait(x, e, r)
    \/ \E e \in Executions, r \in Rows : SatisfyWait(e, r)
    \/ \E x \in Executors, e \in Executions, r \in Rows : RecordProgress(x, e, r)
    \/ \E x \in Executors, e \in Executions, r \in Rows : DecideAgainst(x, e, r)
    \/ \E x \in Executors, e \in Executions : FinishTerminal(x, e)
    \/ \E x \in Executors, e \in Executions : FinishSuspended(x, e)
    \/ \E e \in Executions : RequestCancel(e)
    \/ \E x \in Executors : Relinquish(x)
    \/ \E x \in Executors : Crash(x)
    \/ Unregister
    \/ Register

\* WEAK fairness throughout, sound because every source of flicker is bounded.
\* Timers are fair; fields are not (Spec), or are (SpecSignals). Park,
\* ReRecordWait, DecideAgainst, IssueWrite and RequestCancel are the flow's own
\* choices and unfair; Crash, ExpireHeld and Unregister are the adversary's and
\* unfair. RecordWait is fair because a parked branch's row write is the same
\* call as the park. Both finish arms are fair, which is what says an attempt
\* ends.
Fairness ==
    /\ \A e \in Executions : WF_vars(ExpireAbandoned(e))
    /\ WF_vars(Register)
    /\ \A x \in Executors, e \in Executions : WF_vars(Claim(x, e))
    /\ \A x \in Executors, e \in Executions : WF_vars(FinishTerminal(x, e))
    /\ \A x \in Executors, e \in Executions : WF_vars(FinishSuspended(x, e))
    /\ \A x \in Executors, e \in Executions, r \in Rows : WF_vars(RecordWait(x, e, r))
    /\ \A x \in Executors, e \in Executions, r \in Rows : WF_vars(RecordProgress(x, e, r))
    /\ \A x \in Executors : WF_vars(DeliverWrite(x))
    /\ \A x \in Executors : WF_vars(Relinquish(x))
    /\ \A e \in Executions, r \in Rows : Timer(r) => WF_vars(SatisfyWait(e, r))

SignalFairness == \A e \in Executions : WF_vars(SatisfyWait(e, "input"))

Spec        == Init /\ [][Next]_vars /\ Fairness
SpecSignals == Spec /\ SignalFairness

(***************************************************************************)
(* Properties                                                              *)
(***************************************************************************)

\* I1: two executors never both hold an authorised claim on one execution.
Exclusivity ==
    \A e \in Executions :
        \A x1, x2 \in Executors :
            (/\ heldExec[x1] = e /\ HeldAuthorised(x1)
             /\ heldExec[x2] = e /\ HeldAuthorised(x2)) => x1 = x2

\* D-B: no write ground truth refuses was ever applied, on any write kind.
\* Red under TokenOnly (field), TokenOnlyFinish (terminal), OwnerWitness (ABA)
\* and ReleaseGap (an ending that leaves the generation live).
NoLapsedWrite == ~lapsedWrite

\* D-A: the store never returns an execution the claimer can do nothing with.
\* Red under UnscopedCancel (cannot resolve the definition).
NoUselessClaim == ~uselessClaim

\* D-A: an absent claim means the rows are a finished attempt's blessed
\* statement of what the execution waits for. This is the sentence the whole
\* readiness rule rests on: it is why the rows may gate wake-up exactly when the
\* claim is absent. Red under LoserRow, where a suspending ending keeps what was
\* recorded instead of exactly what it is still waiting on.
LedgerBlessed == \A e \in Executions : ClaimAbsent(e) => waits[e] = pending[e]

\* D-B: recordProgress writes the field and clears the node's rows in one
\* transition, so no state has the field present with the row still outstanding.
\* Red under NoWaitClearing.
ProgressAtomic == \A e \in Executions : fields[e] \cap waits[e] = {}

\* D-A's arm (d): an execution nothing can rescue. Its last attempt died (the
\* claim is active and expired, so no write can land and only a new claim can
\* move it), the ledger MISDESCRIBES what it is waiting on (a row for a branch
\* the composition decided against, or a park whose row the death arrived
\* before), the version is served so this is not the version gate's doing, no
\* cancel is outstanding to override the rows, every outstanding row waits on a
\* signal nothing promises to deliver, and readiness refuses it. Nothing in the
\* design's environment can change any of those: the state is permanent, and
\* only a claim could heal it.
\*
\* The misdescription conjunct is what keeps this a wedge rather than a correct
\* execution waiting for a field that never comes. Without it the predicate
\* fires on a died attempt whose rows are a true statement of its live parks,
\* which the design does not call a defect and which no arm of readiness is
\* meant to rescue. TLC found exactly that state first, and the conjunct is the
\* answer to it.
\* Red under Wedge, which removes the arm.
Wedged(e) ==
    /\ status[e] \notin Terminal
    /\ Dead(e)
    /\ waits[e] /= pending[e]
    /\ versionServed
    /\ ~cancelReq[e]
    /\ \A r \in waits[e] : ~Timer(r)
    /\ ~Ready(e)

NoWedge == \A e \in Executions : ~Wedged(e)

\* I5: a terminal status is never revisited.
TerminalStable ==
    [][\A e \in Executions : status[e] \in Terminal => status'[e] = status[e]]_vars

\* A cancel request is eventually honoured, with no assumption that any field
\* ever arrives. Red under NoCancelArm. Written <>[] over stable predicates
\* (cancelReq and terminal are both stable) so it stays in TLC's efficient
\* fragment; it is equivalent to cancelReq[e] ~> terminal.
CancelTerminates == \A e \in Executions : <>[](cancelReq[e] => status[e] \in Terminal)

\* Every execution terminates, under SpecSignals: every timer fires, every field
\* arrives, the operator rolls back, and crashes, lapses, suspensions and
\* redeployments are finite. Red under MovedWake, where a re-recorded sleep's
\* deadline moves out of reach and its execution never wakes again.
EventuallyTerminal == \A e \in Executions : <>[](status[e] \in Terminal)

(***************************************************************************)
(* Reachability probes, kept permanently. Each must FAIL.                   *)
(***************************************************************************)

\* If this HOLDS, no executor can ever hold a stale belief, every refusal path is
\* unreachable, and every safety result above is worthless. It held on the first
\* version of this spec across 287,480 states.
BelieverAlwaysRight ==
    \A x \in Executors :
        heldExec[x] /= NoOwner =>
            /\ owner[heldExec[x]] = x
            /\ heldValid[x]
            /\ ~expired[heldExec[x]]

\* If this HOLDS, the store never refused anything, and NoLapsedWrite is true
\* because there was nothing to refuse.
NeverRefused == ~refusedEver

==================================================================================
