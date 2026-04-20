<!-- @subframe-version 0.15.1-beta -->
<!-- @subframe-managed -->
---
name: onboard
description: Analyze project intelligence files and bootstrap SubFrame's STRUCTURE.json, PROJECT_NOTES.md, and initial sub-tasks from existing codebase context.
disable-model-invocation: false
argument-hint: [--dry-run]
allowed-tools: Bash, Read, Write, Glob, Grep
---

# SubFrame Onboard

Analyze an existing project and bootstrap SubFrame-compatible output files: `.subframe/STRUCTURE.json`, `.subframe/PROJECT_NOTES.md`, and initial sub-tasks.

## Dynamic Context

Root directory listing:
!`ls -la`

Package manifest:
!`cat package.json 2>/dev/null || cat pyproject.toml 2>/dev/null || cat Cargo.toml 2>/dev/null || echo "No package manifest found"`

Project overview:
!`head -100 README.md 2>/dev/null || echo "No README found"`

AI configuration (Codex):
!`head -50 AGENTS.md 2>/dev/null || echo "No AGENTS.md found"`

AI configuration (Gemini):
!`head -50 GEMINI.md 2>/dev/null || echo "No GEMINI.md found"`

Source file survey:
!`find . -maxdepth 2 -name "*.ts" -o -name "*.tsx" -o -name "*.py" -o -name "*.rs" -o -name "*.go" -o -name "*.java" -o -name "*.rb" 2>/dev/null | head -50`

Existing SubFrame state:
!`cat .subframe/STRUCTURE.json 2>/dev/null || echo "No STRUCTURE.json yet"`

## Instructions

**Argument:** \`$ARGUMENTS\`

### Dry-Run Mode

If \`$ARGUMENTS\` contains \`--dry-run\`, **do not write any files**. Instead, show the full output that *would* be written for each file, clearly labeled with the target path. Then stop.

### Step 1: Analyze the Project

Using the gathered dynamic context, determine:

1. **Project type** — What kind of project is this? (web app, CLI tool, library, monorepo, etc.)
2. **Language and framework** — Primary language, framework, and build tooling
3. **Architecture** — Entry points, module structure, process model (single, client-server, microservices, etc.)
4. **Key modules** — Identify the most important source files and their purposes (scan up to 3 directory levels deep)
5. **Existing documentation** — What context already exists in README, AGENTS.md, GEMINI.md, or other docs?
6. **Dependencies** — Key runtime and dev dependencies from the package manifest

### Step 2: Generate STRUCTURE.json

Build a SubFrame-compatible \`STRUCTURE.json\` following this schema:

\`\`\`json
{
  "version": "1.0",
  "description": "<project-name> - Module structure and communication map",
  "lastUpdated": "<YYYY-MM-DD>",
  "architecture": {
    "type": "<project-type>",
    "entryPoint": "<main-entry-file>",
    "notes": "<brief architecture description>"
  },
  "modules": {
    "<module-key>": {
      "file": "<relative-path>",
      "description": "<what this module does>",
      "exports": ["<exported-function-or-class>"],
      "depends": ["<dependency-module-key>"],
      "functions": {
        "<function-name>": {
          "line": 0,
          "params": ["<param>"],
          "purpose": "<what it does>"
        }
      },
      "loc": 0
    }
  },
  "conventions": {
    "naming": "<file/variable naming conventions observed>",
    "patterns": "<architectural patterns used (MVC, hooks, modules, etc.)>"
  }
}
\`\`\`

**Rules:**
- If a \`STRUCTURE.json\` already exists, **merge** new data into it. Do not overwrite user-supplied descriptions or manually curated content. Only fill in empty fields and add newly discovered modules.
- If no \`STRUCTURE.json\` exists, create a fresh one.
- Scan source files to populate the \`modules\` section. For each module, read the first ~50 lines to identify exports and purpose.
- Set \`lastUpdated\` to today's date.

### Step 3: Generate PROJECT_NOTES.md

Build a SubFrame-compatible \`PROJECT_NOTES.md\` following this structure:

\`\`\`markdown
# <Project Name> - Project Documentation

## Project Vision

**Problem:** <What problem does this project solve?>
**Solution:** <Brief description of the solution>
**Target User:** <Who is this for?>

---

## Project Summary

<1-2 paragraph summary of the project, its purpose, and current state.>

---

## Tech Stack

### Core
- **<Technology>** (<version>): <Why it's used>

### Why These Technologies?
- **<Technology>**: <Rationale>

---

## Architecture

<Description of the project's architecture, module layout, and data flow.>

---

## Key Decisions

<Any architecture or technology decisions discoverable from the codebase.>

---

## Session Notes

<Empty section — to be filled during future development sessions.>
\`\`\`

**Rules:**
- If a \`PROJECT_NOTES.md\` already exists, **do not overwrite it**. Instead, show a diff of suggested additions and ask the user before applying changes.
- If no \`PROJECT_NOTES.md\` exists, create a fresh one from the template above.
- Fill in as much detail as the codebase context allows. Leave sections with \`<placeholder>\` text if insufficient information is available.

### Step 4: Suggest Initial Sub-Tasks

Analyze the project's current state and suggest **3 to 5 initial sub-tasks**. Good candidates include:

- Missing documentation that should exist
- Test coverage gaps (if a test framework is configured but few tests exist)
- TODO/FIXME comments found in the source code
- Configuration improvements (linting, formatting, CI)
- Architecture improvements visible from the structure analysis

For each suggested sub-task, show:
- **Title** — concise imperative description
- **Description** — what needs to be done and why
- **Priority** — \`low\`, \`medium\`, or \`high\`
- **Category** — \`feature\`, \`fix\`, \`docs\`, \`refactor\`, \`test\`, \`chore\`

**Ask the user to confirm** which sub-tasks to create before writing any. Then create the approved ones using the task CLI:

\`\`\`bash
node scripts/task.js add --title "<title>" --description "<description>" --priority <priority> --category <category>
\`\`\`

If the task CLI script (\`scripts/task.js\`) does not exist in the target project, skip sub-task creation and inform the user that the SubFrame task CLI is not available.

### Step 5: Ensure Directory Structure

Before writing any files, ensure the \`.subframe/\` directory and its subdirectories exist:

\`\`\`bash
mkdir -p .subframe/tasks
\`\`\`

### Step 6: Write Files

Write the generated content:
1. \`.subframe/STRUCTURE.json\` — the module map
2. \`.subframe/PROJECT_NOTES.md\` — the project documentation

### Step 7: Summary

Show a summary of what was created or updated:

\`\`\`
## Onboard Summary

**Project:** <name> (<type>)
**Language:** <primary language> + <framework>

### Files Written
- \`.subframe/STRUCTURE.json\` — <N> modules mapped
- \`.subframe/PROJECT_NOTES.md\` — project documentation bootstrapped

### Sub-Tasks Created
- [ST-XXX] <title> (priority, category)
- ...

### Next Steps
- Review the generated files and refine descriptions
- Run \`npm run structure\` if available to enrich STRUCTURE.json with line numbers
- Start working on the created sub-tasks
\`\`\`
