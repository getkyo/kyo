---
name: contributing-edit
description: Apply a structured action list (FIX-CITATION / REMOVE-INVENTED / RELABEL / ADD-MISSING / structural critique directives) to a module CONTRIBUTING.md. Mechanical only; no diagnosis, no new claims.
argument-hint: <doc-path> <action-list>
---

# contributing-edit

Apply the action list passed in `$ARGUMENTS` to the `CONTRIBUTING.md` at the given
path. This skill is mechanical. It does not diagnose, does not verify, and does not
introduce any factual claim that is not already in the action list. If an action
asks you to add content, the content (and its citation) is in the action; you place
it, you do not source it yourself.

## Action vocabulary

From `contributing-verify`:

- **FIX-CITATION** `<claim> -> <correct file:line>`: replace the wrong anchor with
  the correct one given in the action. Do not change the claim text unless the
  action says to.
- **REMOVE-INVENTED** `<claim>`: delete the unsupported sentence. If removing it
  leaves a dangling reference, remove the reference too. Do not replace it with a
  guess.
- **RELABEL** `<sentence> -> fact|target`: when a target convention is dressed as
  fact, add the explicit "this is the convention; the current code does not yet
  follow it everywhere, see <cite>" framing the action supplies; when a real
  behavior is hedged, state it as fact. Use the exact framing in the action.
- **ADD-MISSING** `<item> [<citation>]`: insert the missing KEEP item, with its
  citation, in the section the action names. The item text and anchor come from the
  action; you place them well.

From `contributing-critique` (structural directives): deepen a section, convert a
descriptive paragraph into a decision list, collapse a duplicated-from-root section
into a pointer, move a recipe for navigability. Apply these structurally as
written. A structural directive that cannot be applied without sourcing new facts
is out of scope: stop and report it rather than invent.

## Rules

- Change only what the action list names. Do not drive-by edit prose you were not
  asked to touch.
- Preserve every citation you are not explicitly told to change.
- No em-dashes or en-dashes. If an edit would introduce one, rewrite the sentence;
  never substitute punctuation for a dash.
- Do not add a factual claim that is not in an action. If you believe a claim is
  missing, that is the verify gate's call, not yours; report it, do not add it.
- After applying, do not self-verify or re-grade. The supervisor re-runs the verify
  gate.

## Output

Apply the edits to the file in place. Then report a short summary: one line per
action applied (action type plus the location), and any action you could not apply
mechanically (with the reason), so the supervisor can route it back to the
diagnosing stage rather than have you guess.

## Execution

This sub-skill runs as a single Sonnet agent dispatched by the supervisor. You do
NOT dispatch further sub-agents. Read the doc, apply each action with `Edit`, and
emit the summary. Mechanical application is the whole job; the moment an action
would require you to source a new fact about the code, stop and report it.
