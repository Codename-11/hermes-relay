# Git workspace

Hermes-Relay Android can review and operate a host repository through a native
Git workspace. The Android interface is first-party Compose UI; the optional
Hermes-Relay plugin supplies the authenticated, repository-scoped Git API.

![Native Git workspace](/git-workspace.png)

## Requirements

- The Hermes-Relay plugin must be installed and enabled on the selected Hermes
  connection and profile.
- The Dashboard must expose the plugin's `git/*` API routes.
- Repositories must be below the host's configured Git discovery root.
- Write actions require the plugin's **Allow changes** grant.

If Android discovers the Git contribution but the live route is unavailable,
the workspace shows a retryable availability message instead of a raw server
response.

## Open the workspace

Use any of these entry points:

- Tap the branch indicator or change rail above the Chat composer.
- Open **Settings → Git workspace**.
- Open **Settings → Plugins → Hermes-Relay → Git**.

When the active chat session reports an exact repository root or working
directory, Android selects the matching repository. If several repositories
are possible, choose one from the repository picker.

## Chat controls

The compact Chat rail shows the current branch, unique changed-file count, and
tracked line additions/deletions. Open **Settings → Chat** and turn off **Show
Git workspace in Chat** to hide the Chat indicator and rail. This setting does
not disable the full Git workspace.

## Review and change files

The workspace groups staged, modified, and untracked files and provides filters,
tracked-file content, and staged or unstaged diffs. Binary and untracked files
do not expose arbitrary working-tree content through the preview route.

With **Allow changes** enabled, you can:

- stage modified or untracked files and unstage staged files;
- discard modified content or delete selected untracked files after confirmation;
- create and switch branches, including a recoverable stash-and-switch path;
- generate or enter a commit message and optionally push after committing;
- fetch, fast-forward pull, and push.

Discard, push, and dirty checkout each require a fresh confirmation. Requests
remain bound to the selected connection, profile, repository, and repository
generation so switching context cannot redirect an already-reviewed action.
