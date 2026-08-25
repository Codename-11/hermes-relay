const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import {
  getGitRepos,
  getGitStatus,
  getGitBranches,
  getGitDiff,
  getGitFile,
  gitStage,
  gitUnstage,
  gitDiscard,
  gitCommit,
  gitCommitMessage,
  gitCommitMessageSelected,
  gitStashCheckout,
  gitFetch,
  gitPull,
  gitPush,
  gitCheckout,
} from "../lib/api.js";
import {
  normalizeMutationResult,
  normalizeCommitMessage,
  normalizeStashCheckout,
  hasCommitSuggestion,
  requiresConfirmation,
  confirmationFor,
} from "../lib/git-state.mjs";
import {
  Alert,
  AlertTitle,
  AlertDescription,
  CardDescription,
  Button,
  Badge,
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "../lib/ui-shims.jsx";

const {
  Card,
  CardHeader,
  CardTitle,
  CardContent,
  Label,
} = SDK.components;

function TruncationNotice({ truncated }) {
  if (!truncated) return null;
  return (
    <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-600">
      Results truncated — showing the first entries only.
    </div>
  );
}

function StatusRow({ status }) {
  if (!status) return null;
  const counts = status.counts || {};
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        <Badge variant="outline" className="text-xs">
          {counts.staged || 0} staged
        </Badge>
        <Badge variant="outline" className="text-xs">
          {counts.modified || 0} modified
        </Badge>
        <Badge variant="outline" className="text-xs">
          {counts.untracked || 0} untracked
        </Badge>
      </div>
      <TruncationNotice truncated={status.truncated} />
      {["staged", "modified", "untracked"].map((group) => {
        const items = status[group] || [];
        if (items.length === 0) return null;
        return (
          <div key={group}>
            <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {group}
            </div>
            <ul className="mt-1 space-y-0.5">
              {items.map((item) => (
                <li key={`${group}-${item.path}`} className="font-mono text-xs">
                  {item.path}
                </li>
              ))}
            </ul>
          </div>
        );
      })}
    </div>
  );
}

function BranchesRow({ branches }) {
  if (!branches || branches.length === 0) return null;
  return (
    <div className="space-y-1">
      {branches.map((b) => (
        <div key={b.name} className="flex items-center gap-2 text-xs">
          <span className="font-mono">{b.name}</span>
          {b.is_current ? <Badge className="text-xs">current</Badge> : null}
          {b.upstream ? (
            <span className="text-muted-foreground">
              → {b.upstream}
              {b.ahead > 0 || b.behind > 0
                ? ` (ahead ${b.ahead}, behind ${b.behind})`
                : ""}
            </span>
          ) : null}
        </div>
      ))}
    </div>
  );
}

/**
 * Write controls for the GitState tab. Every mutation is gated by the
 * plugin.api.write grant (the tab is only reachable after the user grants it)
 * and destructive ops (discard/push/dirty-checkout) are confirmed via the
 * per-use confirmation-string mechanics before the POST is sent.
 */
function WriteControls({
  status,
  selected,
  commitMessage,
  onCommitMessageChange,
  generatingMessage,
  onGenerateMessage,
  commitNotice,
  newBranch,
  onNewBranchChange,
  branchRef,
  onBranchRefChange,
  mutating,
  pushAfterCommit,
  onPushAfterCommitChange,
  onStageAll,
  onStage,
  onUnstage,
  onDiscard,
  onCommit,
  onFetch,
  onPull,
  onPush,
  onCheckout,
  onStashCheckout,
}) {
  const staged = (status && status.staged || []).map((e) => e.path);
  const modified = (status && status.modified || []).map((e) => e.path);
  return (
    <div className="space-y-3 rounded-md border p-3">
      <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        Write controls
      </div>

      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="outline" disabled={mutating || modified.length === 0} onClick={() => modified.forEach(onStage)}>
          Stage modified
        </Button>
        <Button size="sm" variant="outline" disabled={mutating || staged.length === 0} onClick={() => staged.forEach(onUnstage)}>
          Unstage staged
        </Button>
        <Button size="sm" variant="outline" disabled={mutating || staged.length === 0} onClick={() => staged.forEach(onDiscard)}>
          Discard staged
        </Button>
      </div>

      <div className="space-y-2">
        <div className="flex items-center gap-2">
          <input
            type="text"
            value={commitMessage}
            onChange={(e) => onCommitMessageChange(e.target.value)}
            placeholder="Commit message"
            className="w-full rounded-md border px-2 py-1 text-xs"
          />
          <Button
            size="sm"
            variant="outline"
            disabled={mutating || generatingMessage || staged.length === 0}
            onClick={onGenerateMessage}
            title="Generate a commit message from the staged diff"
          >
            {generatingMessage ? "Generating…" : "Generate"}
          </Button>
        </div>
        {commitNotice ? (
          <div className="text-xs text-amber-600">{commitNotice}</div>
        ) : null}
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-2 text-xs">
            <input
              type="checkbox"
              checked={pushAfterCommit}
              onChange={(e) => onPushAfterCommitChange(e.target.checked)}
            />
            Push after commit
          </label>
          <Button size="sm" disabled={mutating || !commitMessage.trim()} onClick={onCommit}>
            Commit
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <input
          type="text"
          value={branchRef}
          onChange={(e) => onBranchRefChange(e.target.value)}
          placeholder="Branch to switch to"
          className="w-40 rounded-md border px-2 py-1 text-xs"
        />
        <input
          type="text"
          value={newBranch}
          onChange={(e) => onNewBranchChange(e.target.value)}
          placeholder="New branch name"
          className="w-40 rounded-md border px-2 py-1 text-xs"
        />
        <Button size="sm" variant="outline" disabled={mutating || !branchRef.trim()} onClick={onCheckout}>
          Checkout
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={mutating || !branchRef.trim()}
          onClick={onStashCheckout}
          title="Switch branches, auto-stashing a dirty tree first"
        >
          Stash-checkout
        </Button>
      </div>

      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="outline" disabled={mutating} onClick={onFetch}>
          Fetch
        </Button>
        <Button size="sm" variant="outline" disabled={mutating} onClick={onPull}>
          Pull
        </Button>
        <Button size="sm" variant="destructive" disabled={mutating} onClick={onPush}>
          Push
        </Button>
      </div>
    </div>
  );
}

export default function GitState({ autoRefresh }) {
  const [repos, setRepos] = useState(null);
  const [selected, setSelected] = useState(null);
  const [status, setStatus] = useState(null);
  const [branches, setBranches] = useState(null);
  const [diff, setDiff] = useState(null);
  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);

  const loadRepos = useCallback(async () => {
    setError(null);
    try {
      const data = await getGitRepos();
      const list = (data && data.repos) || [];
      setRepos(list);
      setNotice((data && data.notice) || null);
      if (selected && !list.some((r) => r.id === selected)) {
        setSelected(null);
        setStatus(null);
        setBranches(null);
        setDiff(null);
        setFile(null);
      }
    } catch (err) {
      setError(err && err.message ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [selected]);

  useEffect(() => {
    loadRepos();
  }, [loadRepos]);

  useEffect(() => {
    if (!autoRefresh) return undefined;
    const id = setInterval(loadRepos, 15000);
    return () => clearInterval(id);
  }, [autoRefresh, loadRepos]);

  const selectRepo = useCallback(async (repoId) => {
    setSelected(repoId);
    setStatus(null);
    setBranches(null);
    setDiff(null);
    setFile(null);
    setError(null);
    try {
      const [st, br] = await Promise.all([
        getGitStatus(repoId),
        getGitBranches(repoId),
      ]);
      setStatus(st);
      setBranches(br && br.branches);
    } catch (err) {
      setError(err && err.message ? err.message : String(err));
    }
  }, []);

  const showDiff = useCallback(async (path, kind) => {
    if (!selected) return;
    setFile(null);
    setError(null);
    try {
      setDiff(await getGitDiff(selected, path, kind));
    } catch (err) {
      setError(err && err.message ? err.message : String(err));
    }
  }, [selected]);

  const showFile = useCallback(async (path) => {
    if (!selected) return;
    setDiff(null);
    setError(null);
    try {
      setFile(await getGitFile(selected, path));
    } catch (err) {
      setError(err && err.message ? err.message : String(err));
    }
  }, [selected]);

  // ── Write controls (gated by plugin.api.write + confirmations) ───────────
  const [commitMessage, setCommitMessage] = useState("");
  const [newBranch, setNewBranch] = useState("");
  const [branchRef, setBranchRef] = useState("");
  const [mutating, setMutating] = useState(false);
  const [mutationError, setMutationError] = useState(null);
  const [generatingMessage, setGeneratingMessage] = useState(false);
  const [commitNotice, setCommitNotice] = useState(null);
  const [pushAfterCommit, setPushAfterCommit] = useState(false);

  const refreshDetail = useCallback(async (repoId) => {
    const [st, br] = await Promise.all([
      getGitStatus(repoId),
      getGitBranches(repoId),
    ]);
    setStatus(st);
    setBranches(br && br.branches);
  }, []);

  const applyMutation = useCallback(
    async (op, paths, opts) => {
      if (!selected) return;
      setMutationError(null);
      setMutating(true);
      try {
        if (op === "stage") await gitStage(selected, paths);
        else if (op === "unstage") await gitUnstage(selected, paths);
        else if (op === "fetch") await gitFetch(selected, opts?.remote || "origin");
        else if (op === "pull") await gitPull(selected, opts?.remote || "origin", opts?.branch || "");
        else if (op === "commit") await gitCommit(selected, opts?.message);
        else if (op === "commitSelected") await gitCommitSelected(selected, opts?.message, paths);
        else if (op === "discard") await gitDiscard(selected, paths, opts?.confirmation, opts?.deleteUntracked);
        else if (op === "push") await gitPush(selected, opts?.confirmation, opts?.remote || "origin", opts?.branch || "");
        else if (op === "dirty-checkout") {
          await gitCheckout(selected, opts.ref, {
            confirmation: opts.confirmation,
            newBranch: opts.newBranch,
            track: opts.track,
          });
        }
        await refreshDetail(selected);
      } catch (err) {
        setMutationError(err && err.message ? err.message : String(err));
      } finally {
        setMutating(false);
      }
    },
    [selected, refreshDetail],
  );

  /**
   * Generate a commit-message suggestion from the staged diff (AI). Empty
   * staged diff / model-unavailable degrade to a notice, never an error.
   */
  const generateMessage = useCallback(async () => {
    if (!selected) return;
    setGeneratingMessage(true);
    setCommitNotice(null);
    try {
      const stagedPaths = (status && status.staged || []).map((e) => e.path);
      const data = stagedPaths.length > 0
        ? await gitCommitMessageSelected(selected, stagedPaths)
        : await gitCommitMessage(selected);
      const result = normalizeCommitMessage(data);
      if (hasCommitSuggestion(result)) {
        setCommitMessage(result.message);
        setCommitNotice(result.notice || null);
      } else {
        setCommitNotice(result.notice || "Nothing staged to generate a message from.");
      }
    } catch (err) {
      setCommitNotice(err && err.message ? err.message : String(err));
    } finally {
      setGeneratingMessage(false);
    }
  }, [selected, status]);

  /**
   * Stash-checkout: switch branches, auto-stashing a dirty tree first. No
   * confirmation is needed because a stash is recoverable (git stash pop).
   * On success, surface the stash message so the user can pop it later.
   */
  const doStashCheckout = useCallback(async () => {
    const ref = branchRef.trim();
    if (!ref || !selected) return;
    setMutationError(null);
    setCommitNotice(null);
    setMutating(true);
    try {
      const data = await gitStashCheckout(selected, ref, {
        newBranch: newBranch.trim(),
        track: false,
      });
      const result = normalizeStashCheckout(data);
      if (result.stashed) {
        setCommitNotice(
          `Stashed changes on ${ref} as “${result.stashMessage}”. Use “git stash pop” to restore them.`,
        );
      }
      await refreshDetail(selected);
    } catch (err) {
      setMutationError(err && err.message ? err.message : String(err));
    } finally {
      setMutating(false);
      setBranchRef("");
      setNewBranch("");
    }
  }, [selected, branchRef, newBranch, refreshDetail]);

  /**
   * Destructive ops (discard, push, dirty-checkout) gate on a per-use
   * confirmation echoed back to the server. Matches the dashboard's existing
   * confirm-before-destructive pattern (see RelayManagement onRevoke) and the
   * plugin's confirmation-string mechanics.
   */
  const requestMutation = useCallback((op, opts) => {
    if (requiresConfirmation(op)) {
      const description =
        op === "discard"
          ? "Discard local changes? This cannot be undone."
          : op === "push"
          ? "Push local commits to the remote repository?"
          : "Working tree has uncommitted changes. Switch branches anyway?";
      if (!window.confirm(description)) return;
      const token = confirmationFor(op);
      if (op === "discard") applyMutation("discard", opts?.paths, { confirmation: token, deleteUntracked: opts?.deleteUntracked });
      else if (op === "push") applyMutation("push", [], { confirmation: token, remote: opts?.remote, branch: opts?.branch });
      else if (op === "dirty-checkout") applyMutation("dirty-checkout", [], { ...opts, confirmation: token });
      return;
    }
    applyMutation(op, opts?.paths, opts);
  }, [applyMutation]);

  const doCommit = useCallback(async () => {
    const message = commitMessage.trim();
    if (!message) {
      setMutationError("Commit message must not be empty.");
      return;
    }
    const stagedPaths = (status && status.staged || []).map((e) => e.path);
    if (stagedPaths.length > 0) {
      await applyMutation("commitSelected", stagedPaths, { message });
    } else {
      await applyMutation("commit", [], { message });
    }
    setCommitMessage("");
    // Push-after-commit: when the toggle is ON, immediately start the existing
    // push confirmation flow. Confirmation is still required (never bypassed);
    // the toggle only auto-starts it after a successful commit.
    if (pushAfterCommit) {
      requestMutation("push", {});
    }
  }, [commitMessage, status, applyMutation, pushAfterCommit, requestMutation]);

  const doCheckout = useCallback(async () => {
    const ref = branchRef.trim();
    if (!ref) return;
    const dirty = status && (status.counts.modified + status.counts.staged + status.counts.untracked) > 0;
    const opts = { ref, newBranch: newBranch.trim(), track: false };
    if (dirty && !opts.newBranch) {
      requestMutation("dirty-checkout", opts);
      setBranchRef("");
      setNewBranch("");
      return;
    }
    await applyMutation("checkout", [], opts);
    setBranchRef("");
    setNewBranch("");
  }, [branchRef, newBranch, status, applyMutation, requestMutation]);

  if (loading && repos === null) {
    return <div className="text-sm text-muted-foreground">Loading repositories…</div>;
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Git state unavailable</AlertTitle>
        <AlertDescription>
          <pre className="whitespace-pre-wrap text-xs">{error}</pre>
          <Button className="mt-2" size="sm" variant="outline" onClick={loadRepos}>
            Retry
          </Button>
        </AlertDescription>
      </Alert>
    );
  }

  const list = repos || [];

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Repositories</CardTitle>
          <CardDescription>
            Repositories scanned from the configured Git base path.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {notice ? (
            <div className="mb-3 rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-600">
              {notice}
            </div>
          ) : null}
          {list.length === 0 ? (
            <div className="text-sm text-muted-foreground">
              No repositories found.
            </div>
          ) : (
            <div className="flex flex-wrap gap-2">
              {list.map((repo) => (
                <Button
                  key={repo.id}
                  size="sm"
                  variant={selected === repo.id ? "default" : "outline"}
                  onClick={() => selectRepo(repo.id)}
                >
                  {repo.name}
                  {repo.dirty ? " •" : ""}
                </Button>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {selected ? (
        <Card>
          <CardHeader>
            <CardTitle>{selected}</CardTitle>
            <CardDescription>
              Working tree and branches. Tap a changed file to view its diff or
              content.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {mutationError ? (
              <Alert variant="destructive">
                <AlertTitle>Git mutation failed</AlertTitle>
                <AlertDescription>
                  <pre className="whitespace-pre-wrap text-xs">{mutationError}</pre>
                </AlertDescription>
              </Alert>
            ) : null}
            <StatusRow status={status} />
            <BranchesRow branches={branches} />
            <WriteControls
              status={status}
              branches={branches}
              selected={selected}
              commitMessage={commitMessage}
              onCommitMessageChange={setCommitMessage}
              generatingMessage={generatingMessage}
              onGenerateMessage={generateMessage}
              commitNotice={commitNotice}
              newBranch={newBranch}
              onNewBranchChange={setNewBranch}
              branchRef={branchRef}
              onBranchRefChange={setBranchRef}
              mutating={mutating}
              pushAfterCommit={pushAfterCommit}
              onPushAfterCommitChange={setPushAfterCommit}
              onStageAll={() => requestMutation("stage", { paths: (status && status.modified || []).map((e) => e.path) })}
              onStage={(path) => requestMutation("stage", { paths: [path] })}
              onUnstage={(path) => requestMutation("unstage", { paths: [path] })}
              onDiscard={(path) => requestMutation("discard", { paths: [path], deleteUntracked: false })}
              onCommit={doCommit}
              onFetch={() => requestMutation("fetch", {})}
              onPull={() => requestMutation("pull", {})}
              onPush={() => requestMutation("push", {})}
              onCheckout={doCheckout}
              onStashCheckout={doStashCheckout}
            />

            {status && (status.staged || []).length > 0 ? (
              <div>
                <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Staged diffs
                </div>
                <ul className="mt-1 space-y-0.5">
                  {(status.staged || []).map((item) => (
                    <li key={`sd-${item.path}`}>
                      <button
                        type="button"
                        className="font-mono text-xs text-primary underline"
                        onClick={() => showDiff(item.path, "staged")}
                      >
                        {item.path}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            {status && (status.modified || []).length > 0 ? (
              <div>
                <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Unstaged diffs
                </div>
                <ul className="mt-1 space-y-0.5">
                  {(status.modified || []).map((item) => (
                    <li key={`ud-${item.path}`}>
                      <button
                        type="button"
                        className="font-mono text-xs text-primary underline"
                        onClick={() => showDiff(item.path, "unstaged")}
                      >
                        {item.path}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            {status && (status.untracked || []).length > 0 ? (
              <div>
                <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Untracked files
                </div>
                <ul className="mt-1 space-y-0.5">
                  {(status.untracked || []).map((item) => (
                    <li key={`uf-${item.path}`}>
                      <button
                        type="button"
                        className="font-mono text-xs text-primary underline"
                        onClick={() => showFile(item.path)}
                      >
                        {item.path}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            {diff ? (
              <div>
                <div className="flex items-center justify-between">
                  <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    Diff — {diff.path} ({diff.kind})
                  </div>
                  <TruncationNotice truncated={diff.truncated} />
                </div>
                <pre className="mt-1 max-h-96 overflow-auto whitespace-pre-wrap rounded-md bg-muted/40 p-2 font-mono text-xs">
                  {diff.diff || "(no changes)"}
                </pre>
              </div>
            ) : null}

            {file ? (
              <div>
                <div className="flex items-center justify-between">
                  <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    File — {file.path}
                  </div>
                  <TruncationNotice truncated={file.truncated} />
                </div>
                <pre className="mt-1 max-h-96 overflow-auto whitespace-pre-wrap rounded-md bg-muted/40 p-2 font-mono text-xs">
                  {file.content}
                </pre>
              </div>
            ) : null}
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}
