<!-- @subframe-version 0.15.1-beta -->
<!-- @subframe-managed -->
---
name: sub-docs
description: Sync all SubFrame documentation after feature work. Updates AGENTS.md lists, changelog, PROJECT_NOTES decisions, and STRUCTURE.json.
argument-hint: [summary of what changed]
disable-model-invocation: false
allowed-tools: Bash, Read, Edit, Write, Grep, Glob
---

# SubFrame Documentation Sync

After significant feature work, synchronize all SubFrame documentation references. This skill automates the "Before Ending Work" checklist.

## Dynamic Context

Current version:
!`node -e "console.log(require('./package.json').version)" 2>/dev/null || echo "unknown"`

Recent commits (last 10):
!`git log --oneline --no-decorate -10 2>/dev/null || echo "No git history"`

Files changed (unstaged + staged):
!`git diff --name-only HEAD 2>/dev/null | head -30`

## Instructions

**Argument:** `$ARGUMENTS`

The argument should describe what feature/changes were made. If empty, infer from recent git changes.

### Step 1: Identify What Changed

Read the recent changes (git diff, argument context) and categorize:
- **New source modules** → update AGENTS.md module lists (if applicable)
- **New components** → update AGENTS.md component lists (if applicable)
- **Architecture decisions** → add to `.subframe/PROJECT_NOTES.md` Session Notes
- **User-facing features** → add to `.subframe/docs-internal/changelog.md` under [Unreleased]

### Step 2: Update AGENTS.md

Read `AGENTS.md` and update only the sections that need changes. If AGENTS.md has module/component lists, add new entries. Preserve existing formatting and ordering.

**Rules:**
- Only add genuinely new entries — don't duplicate
- Keep formatting consistent with existing entries
- Don't modify user-written content outside SubFrame-managed sections

### Step 3: Update Changelog

Read `.subframe/docs-internal/changelog.md` and add entries under `## [Unreleased]`.

**Format:** Follow the existing changelog style:
- Group under `### Added`, `### Changed`, `### Fixed`, `### Removed`
- Bold feature name, em-dash, brief description
- Sub-bullets for implementation details

### Step 4: Update PROJECT_NOTES (if architecture decision)

If the work involved an architecture decision worth preserving, add a session note to `.subframe/PROJECT_NOTES.md` under `## Session Notes`.

**Format:**
```markdown
### [YYYY-MM-DD] Title

**Context:** Why this decision was needed.

**Decision:** What was chosen.

**Key architectural choices:**
- Point 1
- Point 2

**Files:** list of key files
```

**Skip this step** for routine changes (bug fixes, minor UI tweaks, config changes).

### Step 5: Regenerate STRUCTURE.json

Run: `npm run structure`

This picks up any new/renamed/deleted source files.

### Step 6: Summary

Present a checklist of what was updated:
- [ ] AGENTS.md — what was added/changed
- [ ] changelog.md — entries added
- [ ] PROJECT_NOTES.md — decision added (or skipped)
- [ ] STRUCTURE.json — regenerated
