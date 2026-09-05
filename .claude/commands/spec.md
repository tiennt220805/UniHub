---
description: Start spec-driven development — write a structured specification before writing code
---

Invoke the agent-skills:spec-driven-development skill.

Work through its phases **one phase per turn** — do not collapse Frame → Explore → Decide → Specify into a single response:

1. **Frame** — extract or ask for the problem/goal, requirements, constraints, expected scale, non-goals, and acceptance criteria. Pull what you can from existing project docs (e.g. `CLAUDE.md`, requirement/architecture docs) before asking; only ask about genuine gaps.
2. **Explore** — propose 2-4 solution alternatives with a trade-off comparison (architecture shape, pros/cons, complexity, scalability & reliability, operational cost, assumptions & risks) and a labeled AI Recommendation. End your turn here.
3. **Decide — hard stop.** Ask the human which option they want and why. Do **not** write SPEC.md until they reply. Capture their answer as an ADR Mini (Context / Decision / Consequences / Trade-offs accepted).
4. **Specify** — only after the human has decided, write SPEC.md reflecting the chosen option: pick the feature/codebase shape or the design-artifact shape (whichever fits), with a concrete/testable verification checklist and Always/Ask first/Never boundaries.

Save the spec as SPEC.md in the project root and confirm with the user before proceeding.
