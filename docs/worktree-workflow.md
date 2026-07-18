# Worktree Workflow

> The day-to-day "how do I move fast without stepping on myself" guide.
> For the *branching/release* contract (when to merge, when to tag), see
> [RELEASE.md](../RELEASE.md) "Branching policy" and `docs/decisions.md` §23.
> This doc is only about **worktrees** — the parallel-folder layer that sits
> underneath that contract.

## The one-paragraph mental model

A **git worktree is a second working folder backed by the same `.git`**. It's a
full checkout on its own branch that shares the object store and refs with your
main checkout — no clone, no re-download, instant. You alt-tab between folders
instead of running `git checkout` back and forth. That matters because switching
branches *in one folder* invalidates the Gradle build cache and forces the IDE to
re-index every time; separate worktrees keep each branch's caches warm in
parallel. This is the entire reason per-feature worktrees feel fast.

```
main    ──●──────────────────●─────────   released only; tags cut HERE
           \                /
dev     ──●──●──●──●──●──●──●───────────   integration; [Unreleased] accumulates
          /    /    /
feat/a ──●        worktree A  ┐
feat/b ────●      worktree B  ├─ one worktree = one branch = one unit of work
feat/c ──────●    worktree C  ┘
```

## Four rules cover everything

1. **One worktree = one branch = one feature/fix**, in its own folder.
2. **Branch off `dev`, PR back to `dev`.** CI green → merge `--no-ff`.
3. **`main` only receives `dev`→`main` release merges.** Tag from `main`.
4. **Worktrees are disposable** — remove them once the PR merges.

## In Orca (the normal path here)

This repo is developed inside Orca, which has its own worktree manager. **Let Orca
create and tear down the per-feature worktree** — that's how features ship in
parallel without index contention. Use the `orca-cli` skill / Orca worktree
commands rather than raw `git worktree`, so Orca tracks the worktree's state and
comment. Your job is just to keep each worktree to *one* unit of work and follow
the four rules above; gitflow is the merge discipline layered on top.

> **Why this matters for agents specifically:** multiple Claude/agent sessions
> sharing a *single* checkout collide on the git index (the "shared worktree,
> concurrent sessions" failure mode — pathspec-commit workarounds, half-staged
> trees). One worktree per session removes the collision entirely. If you ever do
> run two sessions in one folder, commit with explicit pathspecs
> (`git commit -- <paths>`) and check `git status` before every commit.

## Raw `git worktree` (fallback, outside Orca)

When you're not driving through Orca:

```bash
# Create a worktree for a new feature branch off dev
git worktree add ../hermes-feat-bridge-scroll -b feature/bridge-scroll dev

# ...work in that folder, commit, push, open a PR into dev...

# After the PR merges, remove the worktree and prune the branch
git worktree remove ../hermes-feat-bridge-scroll
git worktree prune
```

Useful checks:

```bash
git worktree list          # every worktree + its branch + HEAD
git worktree remove <path> # delete a worktree (must be clean, or pass --force)
```

### Gotchas

- **A branch can be checked out in only one worktree at a time.** Trying to check
  out `dev` in two worktrees errors — that's intentional. Keep `dev`/`main` in the
  main checkout and feature branches in their own worktrees.
- **Worktrees share the same `.git`**, so a `git fetch`/`git gc` in any worktree
  affects all of them. Refs and stashes are shared; the *working tree* and
  per-worktree `HEAD` are not.
- **Don't nest a worktree inside the repo** — put it in a sibling dir
  (`../hermes-feat-x`), not under the repo root, or it gets swept into globs and
  IDE indexing.
- **Build outputs are per-folder.** That's the point (warm caches), but it also
  means three worktrees ≈ three `build/` trees on disk. Prune merged worktrees so
  they don't accumulate.

## How this maps to releasing

Worktrees change *nothing* about the release contract. Feature worktrees merge to
`dev`; releases are still cut by merging `dev` → `main` with `--no-ff` and tagging
from `main` (Android `android-v*`, server `server-v*`, desktop `desktop-v*`). Version bumps
happen on `dev` at release-prep, never on a feature branch — see RELEASE.md.
