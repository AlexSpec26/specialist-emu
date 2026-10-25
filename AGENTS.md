# Shared AI Development Protocol

Version: 1.0 — 2026-10-25

## Purpose

This repository is developed by multiple AI coding agents (Claude Code, OpenAI Codex/ChatGPT, and optionally Grok Build) in a **user-controlled relay**. The human project owner primarily works from a mobile phone. GitHub is the durable source of truth; no agent may assume access to another agent's chat history.

**Primary objective:** deliver the user's intended product, preserving not only implementation details but the motivation, trade-offs, requests, preferences, and unfinished ideas behind them.

## Operating rules

1. **Manual handoff only.** Never initiate a transfer to another AI or assume an agent is currently working unless the user explicitly requests it.
2. **One active implementation owner at a time.** Avoid concurrent edits to overlapping files. Check the current branch, open PRs, and latest commits before writing.
3. **Repository state wins over chat summaries.** Verify actual files and test results. Treat `HANDOFF.md` as a guide, not as proof that a change exists.
4. **Preserve user intent.** Never silently remove, downgrade, reinterpret, or close a user request. Record changes and reasons, and ask for approval for meaningful scope changes.
5. **Make small, coherent commits.** Commit and push progress frequently enough that a sudden usage-limit cutoff does not destroy the handoff context.
6. **No invented verification.** Distinguish tests actually run from tests not run; note environment limitations.
7. **No secrets in Git.** Do not commit API keys, tokens, passwords, `.env` files, or private personal information. Use `.gitignore`, environment secrets, and least-privilege access.
8. **Do not make destructive or costly changes without explicit approval.** This includes force-push, deleting data, database migrations with data loss, deployment to production, changing billing, or broad dependency upgrades.
9. **Use the user's language for conversational updates** (Russian by default unless the user asks otherwise). Code, filenames, and technical documentation may be in English.
10. **Mobile-first communication.** Summarize each working session concisely: what changed, what was verified, what needs the user's decision, and the next step. Link PRs/commits when available.

## Required project memory

Maintain these files in the repository root (create missing files with clear placeholders; do not invent product requirements):

| File | Responsibility |
| --- | --- |
| `VISION.md` | Product purpose, target users, goals, non-goals, success criteria, constraints |
| `BACKLOG.md` | Living register of features, bugs, wishes, user feedback, ideas, rejected/deferred requests |
| `DECISIONS.md` | Architectural and product decisions, rationale, alternatives, reversibility |
| `ARCHITECTURE.md` | Components, data flows, dependencies, environments, interfaces |
| `HANDOFF.md` | Current verified state, active task, branch/commit, blockers, next action |
| `CHANGELOG.md` | User-facing completed changes, not a replacement for Git history |
| `README.md` | Setup, run, test, development workflow |

GitHub Issues may be used for discussion and mobile-friendly task capture, but **the repository files remain the portable shared memory**. Each Issue should link to a stable backlog ID. When Issue status changes, sync `BACKLOG.md` and vice versa; avoid two contradictory task lists.

### `BACKLOG.md` entry format

Each request gets a stable ID such as `FEAT-001`, `BUG-002`, `UX-003`, or `IDEA-004`:

```markdown
### FEAT-001 — Short title
- Status: Inbox | Clarify | Ready | In progress | Blocked | Review | Done | Deferred | Rejected
- Priority: P0 | P1 | P2 | P3 (proposed or user-confirmed)
- Origin: user message / GitHub Issue link / date
- Original user wording: "..." (preserve verbatim when available)
- Why / user problem: ...
- Desired outcome: ...
- Acceptance criteria: measurable checklist
- Constraints and preferences: ...
- Dependencies: ...
- Decisions and rejected alternatives: links to `DECISIONS.md`
- Implementation: branch / PR / commit links
- Validation: test evidence or 'not yet tested'
- Open questions: ...
- Last updated: YYYY-MM-DD, agent name
```

**Backlog discipline:**
- Capture every new wish, change request, complaint, or idea before implementing it, including requests that are not immediately actionable.
- Do not confuse an agent suggestion with an approved user requirement; mark its origin clearly.
- Preserve the reason behind the request and any rejected approaches.
- Split large tasks into actionable subtasks while retaining a link to the parent intent.
- Never mark `Done` merely because code was written: acceptance criteria and validation must be satisfied, or exceptions explicitly accepted by the user.
- Prioritize user-confirmed priorities; otherwise propose an order and mark it provisional.
- Update the backlog **during** work, not only at the end of a long session.

### `DECISIONS.md` entry format

```markdown
### ADR-001 — Decision title (YYYY-MM-DD)
- Status: Proposed | Accepted | Superseded
- Context / user need: ...
- Options considered: ...
- Decision: ...
- Why: ...
- Consequences / risks: ...
- Revisit if: ...
- Related backlog IDs: ...
```

Do not rewrite history when a decision changes: append a superseding entry and link both records.

### `HANDOFF.md` required sections

```markdown
# Current handoff
- Updated at (UTC): ...
- Outgoing agent: Claude / ChatGPT-Codex / Grok
- Branch: ...
- Last pushed commit: ...
- Open PR: ...
- Active backlog IDs: ...
- User-approved goal for this session: ...

## What changed and why
...
## Verified results
- Commands executed: ...
- Passed: ...
- Failed: ...
- Not run / why: ...
## Known problems and risks
...
## Decisions made or proposed
...
## Exact next steps (ordered)
1. ...
## Questions requiring the user's approval
...
```

Keep the top of `HANDOFF.md` short and current. Older handoffs may be archived under `docs/handoffs/YYYY-MM-DD-agent.md` if needed. Do not erase unresolved blockers.

## Session startup protocol (all agents)

1. Confirm the user's requested task and that this agent is now the active worker.
2. Fetch/pull the latest remote state; inspect branch, working tree, recent commits, and open PRs. Do not overwrite uncommitted work.
3. Read `AGENTS.md`, `VISION.md`, `BACKLOG.md`, `DECISIONS.md`, `ARCHITECTURE.md`, `HANDOFF.md`, and `README.md` (if present).
4. Compare the handoff claims with actual code and tests. Report discrepancies.
5. Identify the highest-priority approved actionable task, its motivation, and acceptance criteria. Ask one concise question if an essential decision is missing.
6. State a brief plan; implement in manageable increments, with tests and documentation.
7. Keep project memory updated as requirements or decisions change.

## Development workflow

- Use a dedicated branch for each agent or task, e.g. `claude/feat-001`, `codex/bug-002`, `grok/review-003`. Do not assume these branches exist.
- Prefer pull requests with CI checks before merging into `main`; never force-push shared branches.
- If taking over an unfinished branch, first verify its latest pushed commit and explicitly state whether continuing it or creating a successor branch.
- Include backlog IDs in commit/PR descriptions. Explain **why** a change exists, not only which files changed.
- Run focused tests, linting, type checks, and build commands appropriate to the project. Report exact results.
- For significant changes, review the previous agent's assumptions and look for regressions; do not rewrite working code solely to express style preferences.
- Push code and documentation together so the next agent sees a coherent state.
- When tools lack repository write access, prepare a patch/PR-ready diff and say clearly that it was **not** pushed. Never claim a commit or merge without confirmation.

## Relay / end-of-session protocol

At the user's request to stop, or whenever a usage limit seems near:

1. Finish the smallest safe increment; if impossible, leave the code in a clearly described partial state.
2. Run the relevant checks if possible.
3. Update `BACKLOG.md`, `DECISIONS.md`, `CHANGELOG.md`, and architecture docs as applicable.
4. Update `HANDOFF.md` with the **exact next command/task** and evidence of verification.
5. Commit and push if authorized and technically possible. Record the pushed SHA and PR URL.
6. Give a mobile-friendly summary with four fields: **Done / Tests / Blockers / Next**.
7. Stop. The user explicitly chooses the next agent; never silently trigger another one.

If the current agent hits a hard limit before handoff, the incoming agent must reconstruct state from Git history, PRs, Issues, CI and project memory; it must not assume the last chat message is available.

## Role guidance

- **Claude Code:** regular implementation agent; follow all shared rules and maintain the same memory files.
- **ChatGPT / Codex:** regular implementation agent; follow all shared rules. A plain chat without repository write tools must not pretend to have edited GitHub.
- **Grok Build (optional):** initially use for independent review, isolated bug fixes, or overflow tasks. It may become a full relay participant after a successful trial with tests and PRs. Same rules and file formats apply.

## Ready-to-use user commands

**Take over:**
> Подхвати разработку из GitHub. Сначала прочитай AGENTS.md и всю память проекта, проверь актуальные commits/PR и тесты. Продолжи самую приоритетную согласованную задачу, учитывая её исходную причину и критерии готовности. Документируй решения, регулярно сохраняй изменения и сообщи, если нужно моё решение.

**New wish:**
> Добавь в backlog моё пожелание: [текст]. Сохрани исходную формулировку, объясни зачем это нужно, предложи критерии готовности и приоритет. Пока не реализуй, если я явно не попросил.

**Handoff:**
> Подготовь передачу следующему AI: закончи безопасный этап, проверь тесты, обнови backlog, решения и HANDOFF.md, сделай commit/push или PR и коротко сообщи, что сделано и что дальше.

**Review only:**
> Проверь последние изменения другого AI без переписывания кода. Найди ошибки, непроверенные предположения и расхождения с требованиями. Зафиксируй результаты со ссылками на файлы и тесты.

## First-run bootstrap

If this is the first session in a new repository:
1. Inspect the existing project and preserve all existing code and documentation.
2. Create only missing memory files from the templates above.
3. Ask the user for a concise product vision and first priorities **only where the repository lacks them**.
4. Open a small documentation-only PR (or commit with permission) for the protocol.
5. Do not begin speculative feature implementation before the user selects the first task.
