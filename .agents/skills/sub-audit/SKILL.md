<!-- @subframe-version 0.15.1-beta -->
<!-- @subframe-managed -->
---
name: sub-audit
description: Run a code review and documentation audit on recent changes. Finds bugs, edge cases, missing docs, and type safety issues.
argument-hint: [scope - e.g., "auth feature", "last 5 commits"]
disable-model-invocation: false
allowed-tools: Bash, Read, Grep, Glob, Agent
---

# SubFrame Audit

Run a thorough audit on recent changes, combining code review and documentation checks.

## Dynamic Context

Recent commits (last 15):
!`git log --oneline --no-decorate -15 2>/dev/null || echo "No git history"`

Files changed vs main:
!`git diff --name-only main...HEAD 2>/dev/null | head -40`

## Instructions

**Argument:** `$ARGUMENTS`

The argument should describe the scope to audit. If empty, audit all changes since the last merge to main.

### Phase 1: Identify Scope

Determine which files to audit:
- If argument specifies a feature/scope, identify the relevant files
- If empty, use `git diff --name-only main...HEAD` to find all changed files
- Group files by layer (e.g., backend, frontend, shared, config, tests)

### Phase 2: Code Review (spawn agent)

Spawn a code review agent (`feature-dev:code-reviewer` subagent type) to review the changed files. The agent should check for:

1. **Critical bugs** — null/undefined access, race conditions, unhandled errors, infinite loops
2. **Type safety** — `as any` casts, missing type imports, loose typing where strict types exist
3. **Platform issues** — Windows path handling, file system edge cases
4. **Security** — command injection, XSS in rendered content, path traversal
5. **Logic errors** — off-by-one, incorrect conditions, missing edge cases

### Phase 3: Documentation Audit (spawn agent)

Spawn an explore agent (`Explore` subagent type) to check documentation completeness:

1. **AGENTS.md** — Are all modules/components listed?
2. **changelog.md** — Does [Unreleased] reflect all new features?
3. **PROJECT_NOTES.md** — Are architecture decisions documented?
4. **STRUCTURE.json** — Is it up to date? (compare module count with actual files)

### Phase 4: Report

Present findings in this format:

```
## Audit Report

### Critical Issues (must fix)
1. [FILE:LINE] Description — severity, impact

### Important Issues (should fix)
1. [FILE:LINE] Description — severity, impact

### Documentation Gaps
1. [FILE] What's missing

### Suggestions (nice to have)
1. Description
```

**Confidence filtering:** Only report issues you are confident about. Skip speculative concerns or style preferences. Each reported issue should include:
- Exact file and line number
- What the problem is
- Why it matters (impact)
- Suggested fix

### Phase 5: Offer Fixes

After presenting the report, ask the user if they want to fix any of the reported issues. If yes, apply fixes starting with Critical → Important → Documentation.
