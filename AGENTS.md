<!-- @subframe-version 0.15.1-beta -->
<!-- @subframe-managed -->
# hermes-android - SubFrame Project

This project is managed with **SubFrame**. AI assistants should follow the rules below to keep documentation up to date.

> **Note:** This file is named `AGENTS.md` to be AI-tool agnostic. CLAUDE.md and GEMINI.md contain a reference to this file.

---

## Core Working Principle

**Only do what the user asks.** Do not go beyond the scope of the request.

- Implement exactly what the user requested — nothing more, nothing less.
- Do not change business logic, flow, or architecture unless the user explicitly asks for it.
- If a user asks for a design change, only change the design. Do not refactor, restructure, or modify functionality alongside it.
- If you have additional suggestions or improvements, **present them as suggestions** to the user. Never implement them without approval.
- The user's request must be completed first. Additional ideas come after, as proposals.

---

## Relationship to Native AI Tools

SubFrame **enhances** native AI coding tools — it does not replace them.

**Claude Code** works exactly as normal. Built-in features (`/init`, `/commit`, `/review-pr`, `/compact`, `/memory`, CLAUDE.md) are fully supported. CLAUDE.md is Claude Code's native instruction file — users can add their own tool-specific instructions freely. SubFrame adds a small backlink reference pointing to this AGENTS.md file using HTML comment markers (`<!-- SUBFRAME:BEGIN -->` / `<!-- SUBFRAME:END -->`). SubFrame will never overwrite user content in CLAUDE.md.

**Gemini CLI** works exactly as normal. Built-in features (`/init`, `/model`, `/memory`, `/compress`, `/settings`, GEMINI.md) are fully supported. GEMINI.md is Gemini CLI's native instruction file — same backlink approach as CLAUDE.md. Users can add their own instructions freely and SubFrame won't overwrite them.

**Codex CLI** gets SubFrame context via a wrapper script at `.subframe/bin/codex` that injects AGENTS.md as an initial prompt.

**This file (AGENTS.md)** contains SubFrame-specific rules that apply across all tools:
- Sub-Task management (`.subframe/tasks/*.md`, index at `.subframe/tasks.json`)
- Codebase mapping (`.subframe/STRUCTURE.json`)
- Context preservation (`.subframe/PROJECT_NOTES.md`)
- Internal docs and changelog (`.subframe/docs-internal/`)
- Session notes and decision tracking

---

## Session Start

**Read these files at the start of each session:**

1. **`.subframe/STRUCTURE.json`** — Module map, file locations, architecture notes
2. **`.subframe/PROJECT_NOTES.md`** — Project vision, past decisions, session notes
3. **`.subframe/tasks.json`** — Sub-task index (pending, in-progress, completed)

This gives you full project context before making any changes. The session-start hook (if configured) automatically injects pending/in-progress sub-tasks into your context, but you should still read these files for deeper understanding.

### Concurrent Work & Worktrees

Before making changes, check whether other AI sessions or agent teams are already working on this repository. Signs of concurrent work include:
- In-progress sub-tasks you didn't start (check `.subframe/tasks.json`)
- Recent uncommitted changes in `git status` that aren't yours
- Lock files or active worktrees (`git worktree list`)

**If concurrent work is detected**, ask the user: "Another session appears to be working on this project. Should I use a git worktree to avoid conflicts?"

**Git worktrees** create an isolated copy of the repo on a separate branch, allowing parallel work without merge conflicts:
- Each worktree has its own working directory and branch
- Changes in one worktree don't affect others until merged
- Use worktrees when multiple agents or sessions work on different features simultaneously

**When to suggest a worktree:**
- Agent teams spawning multiple workers on the same repo
- User asks to work on a feature while another is in progress
- The session-start hook flags concurrent sessions

**When worktrees are NOT needed:**
- Single-session work with no concurrent agents
- Read-only exploration or research tasks
- Quick fixes that won't conflict with in-progress work

---

## Hooks (Automatic Awareness)

SubFrame can configure project-level hooks that automate sub-task awareness. These hooks fire automatically — no manual intervention needed.

| Hook | When it fires | What it does |
|------|---------------|--------------|
| **SessionStart** | Startup, resume, after compaction | Injects pending/in-progress sub-tasks into context |
| **UserPromptSubmit** | Each user prompt | Fuzzy-matches prompt against pending sub-tasks, suggests starting a match |
| **Stop** | When AI finishes responding | Reminds about in-progress sub-tasks; flags untracked work if source files changed |
| **PreToolUse** | Before tool execution | Project-specific guardrails (if configured) |
| **PostToolUse** | After tool execution | Project-specific follow-ups (if configured) |

These hooks ensure sub-task awareness even after context compaction. Hook configuration lives in `.claude/settings.json`.

---

## Skills (Slash Commands)

SubFrame provides optional slash commands for AI coding tools that support them (e.g., Claude Code):

| Skill | Purpose |
|-------|---------|
| `/sub-tasks` | Interactive sub-task management — list, start, complete, add, archive |
| `/sub-docs` | Sync all SubFrame documentation after feature work (changelog, CLAUDE.md, PROJECT_NOTES, STRUCTURE) |
| `/sub-audit` | Code review + documentation audit on recent changes |
| `/onboard` | Bootstrap SubFrame files from existing codebase context |

Skills are deployed to `.claude/skills/` and enhance the workflow — but direct file editing always works as a fallback. If your AI tool doesn't support skills, follow the manual instructions in each section below.

---

## Sub-Task Management

> **Terminology:** "Sub-Tasks" are SubFrame's project task tracking system. The name plays on "Sub" from SubFrame and disambiguates from Claude Code's internal todo tools. When the user says "sub-task", they mean this system.

### Sub-Task File Format

Each sub-task lives in its own markdown file at `.subframe/tasks/<id>.md` with YAML frontmatter:

```yaml
---
id: task-abc12345
title: Short and clear title (max 60 characters)
status: pending | in_progress | completed
priority: high | medium | low
category: feature | fix | refactor | docs | test | chore
description: AI's detailed explanation — what, how, which files affected
userRequest: User's original prompt/request — copy exactly
acceptanceCriteria: When is this task done? Concrete testable criteria
blockedBy: []          # task IDs this depends on
blocks: []             # task IDs that depend on this
createdAt: ISO timestamp
updatedAt: ISO timestamp
completedAt: ISO timestamp | null
---

## Notes

[YYYY-MM-DD] Session notes, alternatives considered, dependencies.

## Steps

- [x] Completed step
- [ ] Pending step
```

A generated index is kept at `.subframe/tasks.json` for hooks and quick lookups. After creating or modifying task `.md` files, regenerate the index by reading all `.subframe/tasks/*.md` files (excluding `archive/`) and building the JSON with tasks grouped by status.

### Sub-Task Recognition Rules

**These ARE SUB-TASKS:**
- When the user requests a feature or change
- Decisions like "Let's do this", "Let's add this", "Improve this"
- Deferred work: "We'll do this later", "Let's leave it for now"
- Gaps or improvement opportunities discovered while coding
- Situations requiring bug fixes

**These are NOT SUB-TASKS:**
- Error messages and debugging sessions
- Questions, explanations, information exchange
- Temporary experiments and tests
- Work already completed and closed
- Instant fixes (like typo fixes)

### Sub-Task Creation Flow

1. Detect sub-task patterns during conversation
2. **Check existing sub-tasks first** — read `.subframe/tasks.json` to avoid duplicates
3. Ask the user: "I identified these sub-tasks from our conversation, should I add them?"
4. If approved, create `.subframe/tasks/<id>.md` with all required frontmatter fields
5. Regenerate the `.subframe/tasks.json` index

### Sub-Task Content Rules

**title:** Short, action-oriented
- OK: "Add tasks button to terminal toolbar"
- Bad: "Tasks"

**description:** AI's detailed technical explanation
- What will be done, how, which files affected
- Minimum 2-3 sentences

**userRequest:** User's original words — copy verbatim for context preservation

**acceptanceCriteria:** Concrete, testable completion criteria

### Sub-Task Status Updates

**Before starting any work**, check `.subframe/tasks.json` for an existing sub-task that matches. If found, set it to `in_progress` — do not create a duplicate.

- `pending` → `in_progress` — immediately when you begin working (update `updatedAt`)
- `in_progress` → `completed` — when done and verified (set `completedAt`, update `updatedAt`)
- `completed` → `pending` — when reopening, add a note explaining why
- After commit: check and update the status of all related sub-tasks
- **Incomplete work:** If partially done at session end, leave as `in_progress` and add a notes entry

### Sub-Task Lifecycle

- If a sub-task grows beyond its original scope, split it — create new sub-tasks and reference the parent ID in notes
- Cross-reference relevant commit hashes or PR numbers in notes
- Update the description if the approach changes significantly

### Priority Guidelines

- **high** — Blocking other work or explicitly flagged as urgent by the user
- **medium** — Normal feature work and standard bug fixes
- **low** — Nice-to-have improvements, deferred items, minor polish

---

## .subframe/PROJECT_NOTES.md Rules

### When to Update?
- When an important architectural decision is made
- When a technology choice is made
- When an important problem is solved and the solution method is noteworthy
- When an approach is determined together with the user

### Format
Free format. Date + title is sufficient:
```markdown
### [YYYY-MM-DD] Topic title
Conversation/decision as is, with its context...
```

### Update Flow
- Update immediately after a decision is made
- You can add without asking the user (for important decisions)
- You can accumulate small decisions and add them in bulk

### Organization Rules
- Keep **"Project Vision"** at the top, then **"Session Notes"** in chronological order
- Notes should capture the **why** (decisions, trade-offs, alternatives rejected), not the **what** (code structure belongs in STRUCTURE.json)
- When the same topic spans multiple sessions, consolidate related notes under the original heading rather than creating duplicates
- When notes grow beyond ~500 lines, consider archiving older session notes or grouping by month

---

## Context Preservation (Automatic Note Taking)

SubFrame's core purpose is to prevent context loss. Capture important moments and ask the user.

### When to Ask?

Ask the user: **"Should I add this to .subframe/PROJECT_NOTES.md?"** when:

- A sub-task is successfully completed
- An important architectural/technical decision is made
- A bug is fixed and the solution method is noteworthy
- "Let's do this later" is said (also add as a sub-task)
- A new pattern or best practice is discovered

### Importance Threshold

**Would it take more than 5 minutes to re-derive or re-explain in a future session?** If yes, capture it.

**Always capture:** Architecture decisions, technology choices, approach changes, user preferences discovered during work.

**Never capture:** Routine debugging steps, simple config changes, typo fixes.

**Note failed approaches too** — a brief "We tried X, it didn't work because Y" prevents future re-exploration of dead ends.

### Completion Detection

Pay attention to these signals:
- User approval: "okay", "done", "it worked", "nice", "fixed", "yes"
- Moving from one topic to another
- User continuing after build/run succeeds

### How to Add?

1. **DON'T write a summary** — Add the conversation as is, with its context
2. **Add date** — In `### [YYYY-MM-DD] Title` format
3. **Add to Session Notes section** — At the end of PROJECT_NOTES.md

### When NOT to Ask

- For every small change (it becomes spam)
- Typo fixes, simple corrections
- If the user already said "no" or "not needed", don't ask again for that topic

### If User Says "No"

No problem, continue. The user can also say what they consider important themselves: "add this to notes"

---

## .subframe/STRUCTURE.json Rules

**This file is the map of the codebase.**

### When to Update?
- When a new file/folder is created
- When a file/folder is deleted or moved
- When module dependencies change
- When an IPC channel is added or changed
- When an important architectural pattern is discovered (architectureNotes)

### Full Schema

```json
{
  "modules": {
    "main/moduleName": {
      "file": "src/main/moduleName.ts",
      "description": "What this module does",
      "exports": ["init", "loadData"],
      "depends": ["fs", "path", "shared/ipcChannels"],
      "functions": {
        "init": { "line": 15 },
        "loadData": { "line": 42 }
      }
    }
  },
  "ipcChannels": {
    "CHANNEL_NAME": {
      "direction": "renderer → main",
      "handler": "main/moduleName"
    }
  },
  "architectureNotes": {
    "topicName": {
      "issue": "Description of the pattern or concern",
      "solution": "How it was resolved"
    }
  }
}
```

### Update Rules
- The pre-commit hook (if configured) auto-updates STRUCTURE.json when source files in `src/` are committed
- When deleting files, remove their entries from `modules` and update any `depends` arrays that referenced them
- When adding IPC channels, also add them to the `ipcChannels` section with `direction` and `handler`
- `architectureNotes` is for **structural patterns** (e.g., circular dependency workarounds, init ordering). Use PROJECT_NOTES.md for **decisions and session context**
- If function line numbers drift significantly after edits, re-run the pre-commit hook or update manually

---

## .subframe/docs-internal/ Directory

This directory holds project documentation that doesn't belong in the root:

| File | Purpose |
|------|---------|
| `changelog.md` | Track changes under `## [Unreleased]`, grouped by Added/Changed/Fixed/Removed |
| `*.md` (ADRs) | Architecture Decision Records for significant design choices |

**What goes here:** Changelog entries, architecture decision records, internal reference docs.

**What does NOT go here:** User-facing docs (those go in `docs/` or project root), task files (those go in `.subframe/tasks/`).

---

## .subframe/QUICKSTART.md Rules

### When to Update?
- When installation steps change
- When new requirements are added
- When important commands change

---

## Before Ending Work

After significant work (code changes, architecture decisions), verify SubFrame files are in sync:

1. **Sub-Tasks** — Was this work tracked? Check `.subframe/tasks.json` → create/complete as needed
2. **PROJECT_NOTES.md** — Any decisions worth preserving? Ask the user
3. **Changelog** — Does `.subframe/docs-internal/changelog.md` reflect the changes?
4. **STRUCTURE.json** — Source files changed? The pre-commit hook handles this automatically if configured; otherwise update manually

The stop hook (if configured) will flag untracked work automatically.

---

## General Rules

1. **Language:** Write documentation in English (except code examples)
2. **Date Format:** ISO 8601 (YYYY-MM-DDTHH:mm:ssZ)
3. **After Commit:** Check sub-tasks (`.subframe/tasks/*.md`) and `.subframe/STRUCTURE.json`
4. **Session Start:** Read STRUCTURE.json, PROJECT_NOTES.md, and tasks.json before making changes
5. **Don't Duplicate:** Always check existing sub-tasks before creating new ones

---

*This file was automatically created by SubFrame.*
*Creation date: 2026-04-14*

<!-- subframe-template-version: 1 -->
