const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import {
  getGitRepos,
  getGitStatus,
  getGitBranches,
  getGitDiff,
  getGitFile,
} from "../lib/api.js";
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
            <StatusRow status={status} />
            <BranchesRow branches={branches} />

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
