<!-- @subframe-version 0.15.1-beta -->
<!-- @subframe-managed -->
---
name: sub-tasks
description: View and manage SubFrame Sub-Tasks. Use when starting work, completing tasks, checking what's pending, or creating new tasks from conversation.
disable-model-invocation: false
argument-hint: [list|start|complete|add|get|archive]
allowed-tools: Bash, Read, Write, Edit, Glob
---

# SubFrame Sub-Tasks

Manage the project's Sub-Task system. Sub-Tasks are SubFrame's project task tracking stored as individual markdown files in `.subframe/tasks/`.

## Dynamic Context

Current task index:
!`cat .subframe/tasks.json 2>/dev/null || echo "No tasks.json found"`

## Instructions

**Argument:** `$ARGUMENTS`

### Task File Format

Each task is a markdown file in `.subframe/tasks/` with YAML frontmatter:

```markdown
---
id: task-abc123
title: My task title
status: pending
priority: medium
category: feature
description: What needs to be done
userRequest: The user's original words
acceptanceCriteria: How to verify completion
blockedBy: []
blocks: []
createdAt: 2024-01-01T00:00:00.000Z
updatedAt: 2024-01-01T00:00:00.000Z
completedAt: null
---

## Notes

Session notes go here.

## Steps

- [ ] Step one
- [x] Step two (completed)
```

### Operations

#### List tasks
Read `.subframe/tasks.json` for the index overview, or glob `.subframe/tasks/*.md` and read frontmatter.

#### Get task details
Read the specific `.subframe/tasks/<id>.md` file.

#### Start a task (pending → in_progress)
Edit the task's frontmatter: set `status: in_progress` and update `updatedAt`.

#### Complete a task
Edit the task's frontmatter: set `status: completed`, set `completedAt` to current ISO timestamp, update `updatedAt`.

#### Add a new task
Create a new `.subframe/tasks/<id>.md` file with:
- Generate id: `task-` + 8 random alphanumeric chars
- Set `status: pending`, `createdAt` and `updatedAt` to current ISO timestamp
- `completedAt: null`
- Include all required fields in frontmatter

#### Update a task
Edit the frontmatter fields as needed. Always update `updatedAt`.

#### Archive completed tasks
Move completed `.md` files to `.subframe/tasks/archive/YYYY/` (create directory if needed).

### After Any Write Operation

Regenerate the `.subframe/tasks.json` index by reading all `.subframe/tasks/*.md` files (excluding archive/) and building the JSON structure with tasks grouped by status (pending, inProgress, completed).

### If invoked without arguments

Show the current task list and ask the user what they'd like to do:
1. Start a pending task
2. Complete an in-progress task
3. Create a new task
4. Archive completed tasks

### If invoked with a task ID

Show full details for that task by reading its .md file.

### Creating tasks from conversation

When the user says things like "let's do this later", "add a task for...", or "we should...":
1. Capture the user's exact words as `userRequest`
2. Write a detailed `description` explaining what, how, and which files
3. Set appropriate `priority` and `category`
4. Create the .md file
5. Regenerate the index
6. Confirm the task was created
