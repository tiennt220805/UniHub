---
name: spec-driven-development
description: Creates specifications collaboratively — frames the problem, explores 2-4 solution alternatives with a trade-off analysis, and stops at a Human Decision Gate for the engineer to choose before any SPEC.md gets written. Use when starting a new project, feature, or architecturally significant change; when more than one credible approach exists; when requirements are unclear or only exist as a vague idea; or when the user asks you to compare approaches, weigh trade-offs, or decide on an architecture before implementing. Do not use this skill to silently pick an approach on the user's behalf — architecture decisions belong to the human, not the model.
---

# Spec-Driven Development

## Overview

You are an architectural co-pilot, not a spec-writing machine. Your job is to make the solution space *visible* — the credible approaches, their trade-offs, what each one costs — and then hand the decision to the human. You do not get to converge on one solution and present it as a fait accompli, even if you're confident which one is best. A spec built on a decision the human never actually made is a spec they don't own, and won't defend when it's questioned later.

The spec itself is still the shared source of truth once written — it defines what we're building, why, and how we'll know it's done. Code without a spec is guessing. But the spec is only as trustworthy as the decision behind it, and that decision must be visibly the human's.

## When to Use

- Starting a new project or feature
- Requirements are ambiguous or incomplete
- The change touches multiple files or modules
- You're about to make an architectural decision
- The task would take more than 30 minutes to implement
- The user asks you to compare approaches, weigh trade-offs, or "figure out the best way" to build something

**When NOT to use:** Single-line fixes, typo corrections, or changes where requirements are unambiguous and self-contained. Also skip the full gate if you genuinely cannot name a second credible approach — say so, explain why the one approach is the only reasonable one, and confirm that read with the human before writing a lightweight spec. That confirmation is still a decision gate; it's just a short one.

## The Gated Workflow

```
FRAME ──→ EXPLORE ──→ ⛔ DECIDE ⛔ ──→ SPECIFY ──→ PLAN ──→ TASKS ──→ IMPLEMENT
 (P1)       (P2)      (P3, human)      (P4)         └── unchanged, deferred to other
                                                          skills — see Phase 5 below ──┘
```

**Do not collapse Phase 1 through Phase 4 into a single turn.** Phase 3 is a hard stop: it exists because the choice among architecturally distinct options is the one part of this workflow that isn't yours to make. Ending your turn there isn't a formality — it's the only point where the human can catch a wrong assumption before it's baked into a document that downstream work will treat as settled. If you draft SPEC.md in the same response where you first present alternatives, you've asked and answered your own question, and the "decision" in the ADR will be your reasoning wearing the human's name.

### Phase 1: Problem & Constraints Framing

Before any solution talk, pin down what's actually being solved:

1. **Problem / Goal** — what's the core problem, in one or two sentences?
2. **Requirements** — functional and non-functional (e.g. consistency guarantees, offline behavior, RBAC model).
3. **Constraints** — technology and infrastructure boundaries already decided elsewhere (e.g. this project's `CLAUDE.md` fixes the stack as Java/Spring Boot, PostgreSQL, Redis, RabbitMQ, self-hosted K8s — treat constraints like that as fixed, not as options to reopen in Phase 2).
4. **Expected Scale** — load shape: RPS, concurrent users, data volume, spike behavior (e.g. "12k concurrent users at workshop registration open").
5. **Non-goals** — what's explicitly out of scope for this piece of work.
6. **Acceptance Criteria** — how we'll know this is done, stated as conditions that can be checked, not adjectives.

Pull as much of this as you can from existing project docs (CLAUDE.md, requirement/architecture docs, prior technical-design files) before asking the human anything — only surface genuine gaps. Ask short, targeted questions rather than a six-part intake form in one message; if all six items are already clear from context, say so and move straight into Phase 2 in the same turn.

**Surface assumptions you're still making**, even after framing:

```
ASSUMPTIONS I'M MAKING:
1. This is a web application (not native mobile)
2. Authentication uses session-based cookies (not JWT)
3. We're targeting modern browsers only (no IE11)
→ Correct me now or I'll proceed with these.
```

### Phase 2: Explore Solution Space

Propose **2 to 4 feasible approaches**. Don't stop at the first workable idea, and don't pad the list with a strawman just to hit a count — every option here should be something a competent engineer could actually ship.

For each approach, cover:

| Dimension | What to answer |
|---|---|
| **Architecture Shape** | The processing flow and components involved — a short description or sketch, not a paragraph of prose |
| **Pros & Cons** | Real trade-offs as they'd actually play out here, not generic textbook pros/cons |
| **Complexity** | Low / Medium / High — build and maintenance effort |
| **Scalability & Reliability** | Behavior under the load from Phase 1, and under partial failure |
| **Operational Cost** | RAM, CPU, IOPS, network round-trips, infra pieces added |
| **Assumptions & Risks** | What has to be true for this to work; what could go wrong |

If an option conflicts with a fixed constraint from Phase 1 (e.g. it would require a cloud service `CLAUDE.md` explicitly scopes out), don't quietly drop it from consideration — name it, mark it non-viable, and say why. A silently-omitted option denies the human the chance to say "actually, that constraint is negotiable here."

Close with a labeled recommendation — not a decision:

```
AI RECOMMENDATION: Option B
Reasoning: [why, in terms of the dimensions above]
This is a recommendation, not a decision — I'm not proceeding until you choose.
```

**End your turn here.** Do not move into Phase 3's question-asking and Phase 4's drafting in the same response.

### Phase 3: Human Decision Gate & ADR — hard stop

Ask the human directly:

> Which option do you want to go with, and why?

Wait for their reply before doing anything else. If the reply names an option but skips the "why," ask for it — the reasoning is what makes the ADR meaningful later, not just the choice. A reply like "sounds good" or "sure, go with your recommendation" still counts as a decision, but only if it's clear which option and why; if it's ambiguous, ask once more rather than guessing.

Once you have both, write it up as an ADR Mini:

```
### ADR Mini: [decision title]

**Context:** [the forces at play — the constraint or requirement driving this choice]
**Decision:** [the option chosen, stated as a firm commitment]
**Consequences:** [what becomes easier or possible as a result]
**Trade-offs accepted:** [what we're knowingly giving up — the human's own reasoning, captured close to verbatim]
```

For decisions with system-wide blast radius or that are expensive to reverse, also offer (don't force) to record a full ADR via the `documentation-and-adrs` skill under `docs/decisions/` — the ADR Mini above is meant to travel with SPEC.md, not to replace a standalone record for decisions that deserve their own permanent one.

### Phase 4: Formalized Specification (SPEC.md)

Only write SPEC.md after Phase 3 has produced an ADR Mini. It must reflect the option that was actually chosen — not a synthesis, not "the best parts of both."

**Pick the spec shape that fits what's being spec'd, and say which one you're using:**

- **Feature/codebase spec** — for work that touches runnable code. Six core areas: Objective, Tech Stack, Commands, Project Structure, Code Style, Testing Strategy, Boundaries, Success Criteria, Open Questions. (Full template below.)
- **Design-artifact mini-spec** — for scoping a single documentation/design deliverable in a docs-first or pre-implementation stage (e.g. this project's current design phase, per `CLAUDE.md`). Sections: Objective, Decisions (the ADR Mini from Phase 3), Scope, Document Style, Verification Strategy, Boundaries, Open Questions, Assumptions Flagged. (See this repo's own `SPEC.md` for a worked example — Commands/Tech Stack/Project Structure/Code Style don't apply when there's no application code yet, so this shape drops them rather than forcing empty sections.)

**Feature/codebase spec template:**

```markdown
# Spec: [Project/Feature Name]

## Objective
[What we're building and why. User stories or acceptance criteria.]

## Decisions (ADR Mini)
[Carried forward from Phase 3 — Context / Decision / Consequences / Trade-offs accepted]

## Tech Stack
[Framework, language, key dependencies with versions]

## Commands
[Build, test, lint, dev — full commands]

## Project Structure
[Directory layout with descriptions]

## Code Style
[Example snippet + key conventions]

## Testing Strategy
[Framework, test locations, coverage requirements, test levels]

## Boundaries
- Always: [...]
- Ask first: [...]
- Never: [...]

## Success Criteria
[How we'll know this is done — specific, testable conditions]

## Open Questions
[Anything unresolved that needs human input]
```

**Design-artifact mini-spec template:**

```markdown
# Spec: `[path/to/deliverable.md]`

## Objective
[What this deliverable must let a future implementer do, and why it's needed now]

## Decisions (made with the user before drafting)
[The ADR Mini(s) from Phase 3]

## Scope — Sections to Design
[Numbered list of what the deliverable must cover, and explicitly what's out of scope]

## Document Style
[Format conventions the deliverable should follow]

## Verification Strategy
[Checklist-style: concrete, checkable statements — not "the doc should be good"]

## Boundaries
- Always: [...]
- Ask first: [...]
- Never: [...]

## Open Questions
[Anything genuinely unresolved]

## Assumptions flagged (not yet asked)
[Assumptions you're making that weren't worth blocking on, listed so they're visible]
```

**Write the verification checklist as pass/fail conditions, not adjectives** — "p95 latency < 300ms at 12k concurrent users," not "fast." The `code-review-and-quality` skill's Correctness axis reads this section directly to judge whether an implementation matches the spec; a vague checklist gives it nothing to check against.

**Boundaries often follow directly from the Trade-offs accepted in the ADR Mini** — if the human accepted a limitation to get this option's benefits, encode it as a Never or Ask-first boundary so a future pass doesn't "fix" it as if it were an oversight (see `CLAUDE.md`'s own instruction to treat documented trade-offs as deliberate).

Save the spec as `SPEC.md` in the project root — `/plan` and `/review` both expect it there — and confirm with the human before proceeding.

### Phase 5: Plan → Tasks → Implement (unchanged, deferred)

Everything downstream of a written SPEC.md is unchanged from before:

- **Plan** — generate a technical implementation plan from the validated spec: major components and dependencies, build order, risks, what can run in parallel, verification checkpoints between phases. Follow `planning-and-task-breakdown` as the canonical source for the dependency-graph and vertical-slicing mechanics. **Output convention:** save the plan to `tasks/plan.md` and the task list to `tasks/todo.md`, creating `tasks/` if it doesn't exist — downstream commands (`/build`, etc.) expect these paths.
- **Tasks** — break the plan into discrete, single-session tasks, each with explicit acceptance criteria, a verification step, and no more than ~5 files touched, ordered by dependency. Follow `planning-and-task-breakdown` for the full sizing/ordering mechanics.
  ```markdown
  - [ ] Task: [Description]
    - Acceptance: [What must be true when done]
    - Verify: [How to confirm — test command, build, manual check]
    - Files: [Which files will be touched]
  ```
- **Implement** — execute tasks one at a time via `incremental-implementation` and `test-driven-development`. Use `context-engineering` to load the right spec sections and source files at each step rather than flooding the agent with the entire spec.

## Keeping the Spec Alive

The spec is a living document, not a one-time artifact:

- **Update when decisions change** — If a later constraint invalidates the chosen option, that's a new pass through Phase 2/3 (re-explore, re-decide), not a silent edit to SPEC.md's Decisions section.
- **Update when scope changes** — Features added or cut should be reflected in the spec.
- **Commit the spec** — The spec belongs in version control alongside the code.
- **Reference the spec in PRs** — Link back to the spec section, and the ADR Mini, that each PR implements.

## See Also

- `planning-and-task-breakdown` — canonical source for Phase 5's Plan/Tasks mechanics.
- `incremental-implementation`, `test-driven-development`, `context-engineering` — Phase 5's Implement step.
- `documentation-and-adrs` — for decisions from Phase 3 consequential enough to warrant a standalone record under `docs/decisions/`, beyond the ADR Mini embedded in SPEC.md.
- `code-review-and-quality` (via `/review`) — reads SPEC.md's Verification Checklist / Success Criteria and Decisions/ADR section as its Correctness-axis anchor. Keep those sections precise and testable so review has something real to check against.

## Common Rationalizations

| Rationalization | Reality |
|---|---|
| "This is simple, I don't need a spec" | Simple tasks don't need *long* specs, but they still need acceptance criteria. A two-line spec is fine. |
| "I'll write the spec after I code it" | That's documentation, not specification. The spec's value is in forcing clarity *before* code. |
| "The spec will slow us down" | A 15-minute spec prevents hours of rework. Waterfall in 15 minutes beats debugging in 15 hours. |
| "Requirements will change anyway" | That's why the spec is a living document. An outdated spec is still better than no spec. |
| "The user knows what they want" | Even clear requests have implicit assumptions. The spec surfaces those assumptions. |
| "I'm confident which option is best, I'll just pick it to save them time" | Confidence isn't the same as authorization. Present it as a recommendation and stop — the time you save by skipping the gate is borrowed from the debugging session when the human finds out an architectural call was made without them. |
| "They're busy, I'll draft SPEC.md now so they just have to skim and approve" | A pre-written spec anchors the human toward rubber-stamping your choice instead of actually deciding. The gate only works if it happens before the document exists. |
| "Their reply was short, but it's obviously an approval" | A short reply can still be a real decision — but only if it names the option. If it doesn't, ask; don't infer the option from a vague "sounds fine." |

## Red Flags

- Starting to write code without any written requirements
- Asking "should I just start building?" before clarifying what "done" means
- Implementing features not mentioned in any spec or task list
- Making architectural decisions without documenting them
- Skipping the spec because "it's obvious what to build"
- Generating SPEC.md without a preceding Phase 3 reply from the human
- Presenting Phase 2's alternatives and Phase 3's question in the same turn as a drafted SPEC.md
- Phrasing the ADR's "Decision" as your own reasoning rather than what the human actually said

## Verification

Before proceeding to implementation, confirm:

- [ ] Phase 1 framing (problem, requirements, constraints, scale, non-goals, acceptance criteria) was captured or confirmed with the human
- [ ] At least 2 alternatives were explored with the trade-off matrix (or the single-approach exception was explicitly confirmed with the human)
- [ ] Phase 3's question was asked and the human's reply — naming an option and a reason — was received *before* SPEC.md was written
- [ ] An ADR Mini exists capturing Context / Decision / Consequences / Trade-offs accepted
- [ ] The spec covers all areas of whichever template shape was chosen, and states which shape that is
- [ ] Success Criteria / Verification Checklist items are specific and testable, not adjectives
- [ ] Boundaries (Always/Ask First/Never) are defined
- [ ] The spec is saved to `SPEC.md` in the repository root
