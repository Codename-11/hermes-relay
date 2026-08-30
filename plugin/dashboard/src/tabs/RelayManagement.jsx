const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import {
  getAgentContext,
  getBridgeActivity,
  getOverview,
  getPhoneConfig,
  getRemoteAccessStatus,
  getSessions,
  getUpdateCheck,
  putEnvSetting,
  revokeSession,
} from "../lib/api.js";
import { relativeTime, ttlCountdown, uptime, shortToken } from "../lib/formatters.js";
import { formatSessionExpiry } from "../lib/session-expiry.mjs";
import { supervisedSessionDisplay } from "../lib/supervised-session.mjs";
import PairDialog from "../components/PairDialog.jsx";
import {
  Alert,
  AlertTitle,
  AlertDescription,
  CardDescription,
  Button,
  Badge,
  Switch,
} from "../lib/ui-shims.jsx";

const {
  Card,
  CardHeader,
  CardTitle,
  CardContent,
  Input,
  Label,
  ConfirmDialog,
  Toast,
} = SDK.components;

const useToast = SDK.hooks.useToast;

const AGENT_CONTEXT_MASTER_KEY = "RELAY_AGENT_CONTEXT_ENABLED";
const AGENT_CONTEXT_MEDIA_KEY = "RELAY_CONTEXT_MEDIA_SENSITIVITY";

// Mirror plugin/config.py strict_bool: recognized true tokens → true, anything
// else → false, and UNSET (undefined/null) → the default. These gates default
// ON, so an absent env var must read as enabled (not a stale "off").
const AGENT_CONTEXT_TRUE_TOKENS = new Set(["1", "true", "yes", "on"]);
function coerceAgentContextFlag(value, dflt) {
  if (value === undefined || value === null) return dflt;
  return AGENT_CONTEXT_TRUE_TOKENS.has(String(value).trim().toLowerCase());
}

function valueText(value) {
  if (value === null || value === undefined) return "";
  if (Array.isArray(value)) return value.join(" ");
  if (typeof value === "object") return Object.keys(value).join(" ");
  return String(value);
}

const GRANT_ORDER = {
  chat: 0,
  bridge: 10,
  terminal: 20,
  tui: 30,
  "voice:config": 40,
  "voice:stt": 41,
  "voice:tts": 42,
};

function grantSortKey(name) {
  const normalized = String(name || "").toLowerCase();
  return Object.prototype.hasOwnProperty.call(GRANT_ORDER, normalized)
    ? GRANT_ORDER[normalized]
    : 100;
}

function formatGrantName(name) {
  const normalized = String(name || "").toLowerCase();
  switch (normalized) {
    case "chat":
      return "Chat";
    case "bridge":
      return "Bridge";
    case "terminal":
      return "Terminal";
    case "tui":
      return "TUI";
    case "voice:config":
      return "Voice config";
    case "voice:stt":
      return "Voice STT";
    case "voice:tts":
      return "Voice TTS";
    default:
      return String(name || "");
  }
}

function sortGrants(grants) {
  return grants.sort((left, right) => {
    const byKnownOrder = grantSortKey(left.name) - grantSortKey(right.name);
    return byKnownOrder || String(left.name).localeCompare(String(right.name));
  });
}

function extractGrants(session) {
  const raw = session && session.grants;
  if (Array.isArray(raw)) {
    const grants = raw
      .map((entry) => {
        if (typeof entry === "string") return { name: entry, detail: "" };
        if (!entry || typeof entry !== "object") return null;
        const name = entry.name || entry.channel || entry.grant || entry.scope;
        if (!name) return null;
        return {
          name: String(name),
          detail: entry.expires_at
            ? ttlCountdown(entry.expires_at)
            : formatGrantValue(entry.ttl_seconds ?? entry.ttl ?? entry.seconds),
        };
      })
      .filter(Boolean);
    return sortGrants(grants);
  }
  if (raw && typeof raw === "object") {
    return sortGrants(Object.entries(raw).map(([name, value]) => ({
      name,
      detail:
        value && typeof value === "object"
          ? value.expires_at || value.expiresAt || value.until
            ? ttlCountdown(value.expires_at || value.expiresAt || value.until)
            : formatGrantValue(value.ttl_seconds ?? value.ttl ?? value.seconds)
          : formatGrantValue(value),
    })));
  }
  return [];
}

function formatGrantValue(value) {
  if (value === null || value === undefined || value === "" || value === true) return "";
  const seconds = Number(value);
  if (!Number.isFinite(seconds) || seconds <= 0) return "";
  return seconds > 1e9 ? ttlCountdown(seconds) : formatDuration(seconds);
}

function formatDuration(value) {
  if (value === null || value === undefined || value === "" || value === true) return "";
  const seconds = Number(value);
  if (!Number.isFinite(seconds) || seconds <= 0) return "";
  if (seconds < 60) return `${Math.floor(seconds)}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const remMinutes = minutes % 60;
  if (hours < 24) return remMinutes ? `${hours}h ${remMinutes}m` : `${hours}h`;
  const days = Math.floor(hours / 24);
  const remHours = hours % 24;
  return remHours ? `${days}d ${remHours}h` : `${days}d`;
}

function classifySession(session, grants) {
  const haystack = [
    session.device_type,
    session.client_type,
    session.platform,
    session.device_platform,
    session.device_model,
    session.client_surface,
    session.device_form_factor,
    session.device_name,
    session.device_label,
    session.client_name,
    session.label,
    session.transport,
    session.transport_hint,
    session.channel,
    valueText(session.capabilities),
    grants.map((g) => g.name).join(" "),
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();

  if (/\bandroid\b|\bmobile\b|\bphone\b|hermes-relay-android/.test(haystack)) {
    return "Android";
  }
  if (/\btui\b|terminal-ui|textual/.test(haystack)) {
    return "Desktop TUI";
  }
  if (/\bcli\b|terminal|shell|desktop|tool|powershell|bash|cmd\.exe/.test(haystack)) {
    return "Desktop CLI";
  }
  if (/\bweb\b|\bbrowser\b|\bdashboard\b/.test(haystack)) {
    return "Dashboard";
  }
  return "Client";
}

function sessionTransport(session) {
  return (
    session.transport_hint ||
    session.transport ||
    session.channel ||
    session.connection ||
    session.protocol ||
    ""
  );
}

function sessionTokenPrefix(session) {
  const raw = session.token || session.session_token || "";
  return (
    session.token_prefix ||
    session.prefix ||
    session.tokenPrefix ||
    session.session_prefix ||
    (raw ? String(raw).slice(0, 12) : "")
  );
}

function StatCard({ label, value, hint }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl">{value}</CardTitle>
      </CardHeader>
      {hint ? (
        <CardContent className="pt-0 text-xs text-muted-foreground">{hint}</CardContent>
      ) : null}
    </Card>
  );
}

function ToggleRow({ id, title, description, checked, disabled, onChange }) {
  return (
    <div className="flex items-start justify-between gap-3 rounded-md border border-border/70 p-3">
      <div className="space-y-1">
        <Label htmlFor={id} className="text-sm font-medium">
          {title}
        </Label>
        {description ? (
          <div className="text-xs text-muted-foreground">{description}</div>
        ) : null}
      </div>
      <Switch
        id={id}
        checked={checked}
        disabled={disabled}
        onCheckedChange={onChange}
      />
    </div>
  );
}

function AgentContextCard({ data, saving, onToggle }) {
  const settings = (data && data.settings) || {};
  const injected = (data && data.injected) || {};
  const blocks = Array.isArray(injected.blocks) ? injected.blocks : [];
  const masterEnabled = coerceAgentContextFlag(settings[AGENT_CONTEXT_MASTER_KEY], true);
  const mediaEnabled = coerceAgentContextFlag(settings[AGENT_CONTEXT_MEDIA_KEY], true);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Agent context</CardTitle>
        <CardDescription>
          On by default for Hermes-Relay installs — injects an instruction into the agent's system
          prompt (server-side) so it can mark sensitive media. Turn off to opt out; removable
          by uninstalling the relay plugin.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <ToggleRow
          id="relay-agent-context-enabled"
          title="Enable Agent context injection"
          description="Master toggle for Hermes-Relay-owned server-side prompt blocks."
          checked={masterEnabled}
          disabled={saving === AGENT_CONTEXT_MASTER_KEY}
          onChange={(value) => onToggle(AGENT_CONTEXT_MASTER_KEY, value)}
        />
        <ToggleRow
          id="relay-context-media-sensitivity"
          title="Media sensitivity block"
          description="Ask the agent to mark private, NSFW, or spoiler media with client-visible sensitivity markers."
          checked={mediaEnabled}
          disabled={saving === AGENT_CONTEXT_MEDIA_KEY}
          onChange={(value) => onToggle(AGENT_CONTEXT_MEDIA_KEY, value)}
        />
        <details className="hr-audit-details">
          <summary>
            <span>
              <span className="text-sm font-medium">Server-side audit</span>
              <span className="block text-xs text-muted-foreground">
                {injected.enabled ? "Context injection enabled." : "Context injection disabled."}
              </span>
            </span>
            <Badge variant={injected.enabled ? "success" : "outline"} className="text-xs">
              {blocks.length} {blocks.length === 1 ? "block" : "blocks"} active
            </Badge>
          </summary>
          {blocks.length === 0 ? (
            <div className="hr-audit-empty text-xs text-muted-foreground">
              No blocks would be injected on the next turn.
            </div>
          ) : (
            <div className="hr-audit-blocks space-y-2">
              {blocks.map((block) => (
                <div key={block.name} className="rounded-md bg-muted/40 p-2">
                  <div className="text-xs font-medium">{block.name}</div>
                  <pre className="mt-1 whitespace-pre-wrap text-xs text-muted-foreground">
                    {block.text}
                  </pre>
                </div>
              ))}
            </div>
          )}
        </details>
      </CardContent>
    </Card>
  );
}

function HomeChannelCard({ config, onSaved }) {
  // Lazy-init the draft from the loaded name. The card is keyed by the loaded
  // name at the call site, so it remounts (re-seeding the draft) only when the
  // server value actually changes — autorefresh won't clobber active typing.
  const [draft, setDraft] = useState((config && config.home_channel_name) || "Phone");
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState(null);
  const [error, setError] = useState(null);

  const chatId = (config && config.home_channel_id) || "phone";
  const envKey = (config && config.name_env_key) || "PHONE_HOME_CHANNEL_NAME";

  const save = useCallback(async () => {
    const name = draft.trim();
    if (!name) {
      setError("Display name cannot be empty.");
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await putEnvSetting(envKey, name);
      setSavedAt(Date.now());
      if (onSaved) await onSaved();
    } catch (err) {
      setError(err && err.message ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  }, [draft, envKey, onSaved]);

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle>Home channel</CardTitle>
          <Badge variant="warning" className="text-xs">Restart required</Badge>
        </div>
        <CardDescription>
          Where Hermes delivers proactive pushes, cron results, and
          cross-platform messages when no specific Thread is named. The phone is
          a single paired device, so this is auto-configured — you only set a
          display name.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-1">
          <Label htmlFor="phone-home-name">Display name</Label>
          <Input
            id="phone-home-name"
            value={draft}
            placeholder="Phone"
            onChange={(e) => setDraft(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            Notification title and Thread label. Applies after the next gateway
            restart.
          </p>
        </div>

        <div className="text-xs text-muted-foreground">
          Channel id <code className="font-mono">{chatId}</code> — fixed;
          changing it would orphan existing Threads.
        </div>

        {error ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 p-2 text-xs text-destructive">
            {error}
          </div>
        ) : null}

        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={save} disabled={saving || !draft.trim()}>
            {saving ? "Saving…" : "Save"}
          </Button>
          {savedAt ? (
            <span className="text-xs text-muted-foreground">
              Saved {relativeTime(savedAt)}
            </span>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

function UpdateCheckCard({ info, onRefresh }) {
  const [refreshing, setRefreshing] = useState(false);
  const [copied, setCopied] = useState(false);

  const doRefresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await onRefresh(true);
    } finally {
      setRefreshing(false);
    }
  }, [onRefresh]);

  const cmd = info && info.update_command;
  const copyCmd = useCallback(async () => {
    if (!cmd) return;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(cmd);
      } else {
        window.prompt("Copy update command", cmd);
      }
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch (_err) {
      window.prompt("Copy update command", cmd);
    }
  }, [cmd]);

  if (!info) return null;
  const current = info.current || "—";
  const available = !!info.update_available;
  const description = available
    ? `Update available — you're on ${current}.`
    : info.error
    ? `On ${current}. Couldn't reach GitHub to check.`
    : `On ${current}${info.latest ? ` — latest is ${info.latest}.` : "."}`;

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <div>
          <CardTitle>Plugin version</CardTitle>
          <CardDescription>{description}</CardDescription>
        </div>
        <Button size="sm" variant="outline" onClick={doRefresh} disabled={refreshing}>
          {refreshing ? "Checking…" : "Check"}
        </Button>
      </CardHeader>
      {available ? (
        <CardContent className="space-y-2">
          <Badge variant="secondary" className="w-fit text-xs">
            {current} → {info.latest}
          </Badge>
          <div className="flex items-center justify-between gap-2 rounded-md border border-border/70 bg-muted/30 p-2 font-mono text-xs">
            <span className="truncate">{cmd}</span>
            <Button size="sm" variant="ghost" onClick={copyCmd}>
              {copied ? "Copied" : "Copy"}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            Run it on your Hermes host, then restart the gateway to load the new plugin.
          </p>
        </CardContent>
      ) : info.error ? (
        <CardContent className="pt-0 text-xs text-muted-foreground">{info.error}</CardContent>
      ) : null}
    </Card>
  );
}

function sessionList(data) {
  return Array.isArray(data) ? data : (data && data.sessions) || [];
}

function errorMessage(error) {
  return error && error.message ? error.message : String(error);
}

function SessionCard({ session, compact = false, copied, revoking, onCopy, onRevoke }) {
  const tokenPrefix = sessionTokenPrefix(session);
  const label =
    session.device_name ||
    session.device_label ||
    session.client_name ||
    session.label ||
    shortToken(tokenPrefix) ||
    "Unnamed device";
  const lastSeen =
    session.last_seen ||
    session.last_activity ||
    session.last_seen_at ||
    session.last_activity_at ||
    session.updated_at ||
    session.paired_at;
  const expiresAt = session.expires_at ?? session.expiresAt ?? session.expires;
  const expiry = formatSessionExpiry(expiresAt);
  const grants = extractGrants(session);
  const type = classifySession(session, grants);
  const transport = sessionTransport(session);
  const supervised = supervisedSessionDisplay(session);
  const deviceDetail = [session.device_model, session.device_platform]
    .filter((value) => value && value !== "unknown")
    .join(" · ");

  return (
    <div className={`hr-device-card ${compact ? "hr-device-card-compact" : ""}`}>
      <div className="hr-device-main">
        <div className="hr-device-title-row">
          <span className="hr-status-dot hr-status-dot-success" aria-label="Paired" />
          <span className="font-medium">{label}</span>
          <Badge variant="outline" className="text-xs">{type}</Badge>
          {supervised ? <Badge variant="secondary" className="text-xs">Supervised</Badge> : null}
        </div>
        <div className="hr-device-meta text-xs text-muted-foreground">
          {deviceDetail ? <span>{deviceDetail}</span> : null}
          {transport ? <span>{transport}</span> : null}
          <span>Seen {relativeTime(lastSeen)}</span>
          <Badge
            variant={expiry.expired ? "destructive" : "secondary"}
            className="text-xs"
            title={expiry.exact ? `Expires ${expiry.exact}` : undefined}
          >
            {expiry.label}
          </Badge>
        </div>
        {supervised && supervised.profileLabel ? (
          <div className="text-xs text-muted-foreground">
            Pinned profile: {supervised.profileLabel}
          </div>
        ) : null}
        {!compact ? (
          <div className="hr-grant-list">
            {grants.length === 0 ? (
              <span className="text-xs text-muted-foreground">No grants reported</span>
            ) : (
              grants.map((grant) => (
                <Badge key={`${grant.name}:${grant.detail}`} variant="secondary" className="text-xs">
                  {grant.detail
                    ? `${formatGrantName(grant.name)} ${grant.detail}`
                    : formatGrantName(grant.name)}
                </Badge>
              ))
            )}
          </div>
        ) : null}
      </div>
      {!compact ? (
        <div className="hr-device-actions">
          <Button
            size="sm"
            variant="outline"
            disabled={!tokenPrefix}
            onClick={() => onCopy(tokenPrefix)}
          >
            {copied === tokenPrefix ? "Copied" : "Copy prefix"}
          </Button>
          <Button
            size="sm"
            variant="destructive"
            disabled={revoking === tokenPrefix || !tokenPrefix}
            onClick={() => onRevoke({ prefix: tokenPrefix, label })}
          >
            {revoking === tokenPrefix ? "Revoking…" : "Revoke"}
          </Button>
        </div>
      ) : null}
    </div>
  );
}

function primaryRemoteRoute(status) {
  if (!status) return { value: "—", hint: "Checking routes" };
  const ports = (status.tailscale && status.tailscale.serve_ports) || [];
  if (ports.includes(8767)) return { value: "Tailscale", hint: "Private remote route active" };
  if (status.secure_link && status.secure_link.enabled) {
    return { value: "Secure Link", hint: "Pinned TLS route active" };
  }
  if (status.public && status.public.url) return { value: "Public URL", hint: "Pinned public route" };
  return { value: "LAN only", hint: "No remote route configured" };
}

function decisionVariant(decision) {
  const normalized = String(decision || "pending").toLowerCase();
  if (normalized === "executed" || normalized === "confirmed") return "success";
  if (normalized === "blocked" || normalized === "error") return "destructive";
  if (normalized === "timeout") return "warning";
  return "outline";
}

function RecentActivity({ rows, error, onOpenActivity }) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <div>
          <CardTitle>Recent Bridge activity</CardTitle>
          <CardDescription>Latest commands routed through Hermes-Relay.</CardDescription>
        </div>
        <Button size="sm" variant="ghost" onClick={onOpenActivity}>View all</Button>
      </CardHeader>
      <CardContent>
        {error && rows.length === 0 ? (
          <div className="text-xs text-destructive">{error}</div>
        ) : rows.length === 0 ? (
          <div className="text-sm text-muted-foreground">No Bridge activity recorded yet.</div>
        ) : (
          <div className="hr-activity-list">
            {rows.slice(0, 4).map((row, index) => (
              <div className="hr-activity-row" key={row.request_id || `${row.sent_at}-${index}`}>
                <div className="min-w-0">
                  <div className="font-mono text-xs hr-activity-method">
                    {row.method || "COMMAND"} {row.path || ""}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    {relativeTime(row.sent_at)}
                  </div>
                </div>
                <Badge variant={decisionVariant(row.decision)} className="text-xs capitalize">
                  {row.decision || "pending"}
                </Badge>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export function RelayOverview({ autoRefresh, onConnectMobile, onNavigate }) {
  const [overview, setOverview] = useState(null);
  const [overviewError, setOverviewError] = useState(null);
  const [sessions, setSessions] = useState([]);
  const [sessionsError, setSessionsError] = useState(null);
  const [remoteStatus, setRemoteStatus] = useState(null);
  const [remoteError, setRemoteError] = useState(null);
  const [activity, setActivity] = useState([]);
  const [activityError, setActivityError] = useState(null);
  const [updateInfo, setUpdateInfo] = useState(null);
  const [pairOpen, setPairOpen] = useState(false);

  const loadOverview = useCallback(async () => {
    try {
      setOverview(await getOverview());
      setOverviewError(null);
    } catch (error) {
      setOverviewError(errorMessage(error));
    }
  }, []);

  const loadSessions = useCallback(async () => {
    try {
      setSessions(sessionList(await getSessions()));
      setSessionsError(null);
    } catch (error) {
      setSessionsError(errorMessage(error));
    }
  }, []);

  const loadRemote = useCallback(async () => {
    try {
      setRemoteStatus(await getRemoteAccessStatus());
      setRemoteError(null);
    } catch (error) {
      setRemoteError(errorMessage(error));
    }
  }, []);

  const loadActivity = useCallback(async () => {
    try {
      const data = await getBridgeActivity(5);
      setActivity(Array.isArray(data) ? data : (data && data.activity) || []);
      setActivityError(null);
    } catch (error) {
      setActivityError(errorMessage(error));
    }
  }, []);

  const loadUpdate = useCallback(async (refresh = false) => {
    try {
      setUpdateInfo(await getUpdateCheck({ refresh }));
    } catch (error) {
      setUpdateInfo({ error: errorMessage(error) });
    }
  }, []);

  const loadAll = useCallback(() => {
    loadOverview();
    loadSessions();
    loadRemote();
    loadActivity();
  }, [loadOverview, loadSessions, loadRemote, loadActivity]);

  useEffect(() => {
    loadAll();
    loadUpdate(false);
  }, [loadAll, loadUpdate]);

  useEffect(() => {
    if (!autoRefresh) return undefined;
    const id = setInterval(loadAll, 10000);
    return () => clearInterval(id);
  }, [autoRefresh, loadAll]);

  const ov = overview || {};
  const route = primaryRemoteRoute(remoteStatus);
  const lastActivity = activity[0];
  const healthLabel = overviewError
    ? overview ? "Stale" : "Unavailable"
    : overview ? ov.health || "Healthy" : "Checking";
  const healthVariant = overviewError ? "warning" : overview ? "success" : "outline";

  return (
    <div className="space-y-4">
      <Card className="hr-service-card">
        <CardHeader className="hr-service-header">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <CardTitle>Service status</CardTitle>
              <Badge variant={healthVariant} className="text-xs">
                {healthLabel}
              </Badge>
            </div>
            <CardDescription>
              Hermes-Relay service health and installed plugin version.
            </CardDescription>
          </div>
          <Button size="sm" variant="outline" onClick={() => loadUpdate(true)}>
            Check for updates
          </Button>
        </CardHeader>
        <CardContent className="hr-service-details">
          <div><span>Version</span><strong>{ov.version || "—"}</strong></div>
          <div><span>Uptime</span><strong>{uptime(ov.uptime_seconds)}</strong></div>
          <div><span>Refresh</span><strong>{autoRefresh ? "Live" : "Paused"}</strong></div>
          {updateInfo && updateInfo.update_available ? (
            <Badge variant="warning" className="text-xs">
              {updateInfo.latest} available
            </Badge>
          ) : null}
        </CardContent>
        {overviewError ? (
          <div className="hr-inline-error text-xs text-destructive">{overviewError}</div>
        ) : null}
      </Card>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <StatCard
          label="Paired devices"
          value={overview ? (ov.paired_device_count ?? ov.session_count ?? sessions.length) : "—"}
          hint={sessionsError || "Hermes-Relay sessions"}
        />
        <StatCard
          label="Remote access"
          value={route.value}
          hint={remoteError || route.hint}
        />
        <StatCard
          label="Last Bridge event"
          value={lastActivity ? relativeTime(lastActivity.sent_at) : "—"}
          hint={activityError || (lastActivity ? lastActivity.decision || "Recorded" : "No recent activity")}
        />
      </div>

      <div className="hr-overview-grid">
        <Card>
          <CardHeader>
            <CardTitle>Quick actions</CardTitle>
            <CardDescription>Connect the standard Dashboard first, then add Hermes-Relay capabilities.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <Button className="w-full" onClick={() => setPairOpen(true)}>Pair new device</Button>
            <Button className="w-full" variant="outline" onClick={onConnectMobile}>Connect mobile app</Button>
            {sessions.length > 0 ? (
              <div className="hr-overview-device">
                <div className="text-xs uppercase tracking-wider text-muted-foreground">Most recently paired</div>
                <SessionCard session={sessions[0]} compact />
              </div>
            ) : null}
            <Button size="sm" variant="ghost" onClick={() => onNavigate("devices")}>Manage devices</Button>
          </CardContent>
        </Card>
        <RecentActivity
          rows={activity}
          error={activityError}
          onOpenActivity={() => onNavigate("activity")}
        />
      </div>

      {!autoRefresh ? (
        <Button size="sm" variant="outline" onClick={loadAll}>Refresh overview</Button>
      ) : null}
      <PairDialog open={pairOpen} onClose={() => { setPairOpen(false); loadSessions(); loadOverview(); }} />
    </div>
  );
}

export default function RelayDevices({ autoRefresh, onConnectMobile }) {
  const [sessions, setSessions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pairOpen, setPairOpen] = useState(false);
  const [revoking, setRevoking] = useState(null);
  const [copied, setCopied] = useState(null);
  const [pendingRevoke, setPendingRevoke] = useState(null);
  const { toast, showToast } = useToast();

  const load = useCallback(async () => {
    try {
      setSessions(sessionList(await getSessions()));
      setError(null);
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (!autoRefresh) return undefined;
    const id = setInterval(load, 10000);
    return () => clearInterval(id);
  }, [autoRefresh, load]);

  const onCopyPrefix = useCallback(async (prefix) => {
    if (!prefix) return;
    try {
      await navigator.clipboard.writeText(prefix);
      setCopied(prefix);
      showToast("Token prefix copied", "success");
      window.setTimeout(() => setCopied(null), 1500);
    } catch (_error) {
      showToast("Could not copy token prefix", "error");
    }
  }, [showToast]);

  const confirmRevoke = useCallback(async () => {
    if (!pendingRevoke) return;
    setRevoking(pendingRevoke.prefix);
    try {
      await revokeSession(pendingRevoke.prefix);
      showToast(`${pendingRevoke.label} revoked`, "success");
      setPendingRevoke(null);
      await load();
    } catch (revokeError) {
      showToast(`Revoke failed: ${errorMessage(revokeError)}`, "error");
    } finally {
      setRevoking(null);
    }
  }, [pendingRevoke, load, showToast]);

  const hasSupervisedSession = sessions.some((session) => supervisedSessionDisplay(session));

  return (
    <div className="space-y-4">
      <Toast toast={toast} />
      <div className="hr-connection-choice-grid">
        <Card>
          <CardHeader>
            <CardTitle>Connect mobile app</CardTitle>
            <CardDescription>Standard Hermes Dashboard connection for Chat, Manage, sessions, and voice.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button size="sm" variant="outline" onClick={onConnectMobile}>Show setup QR</Button>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Pair with Hermes-Relay</CardTitle>
            <CardDescription>Add Terminal, Bridge, media, remote-access, and extended voice grants.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button size="sm" onClick={() => setPairOpen(true)}>Pair new device</Button>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <div>
            <CardTitle>Paired devices</CardTitle>
            <CardDescription>Clients currently authorized against Hermes-Relay.</CardDescription>
          </div>
          {!autoRefresh ? <Button size="sm" variant="outline" onClick={load}>Refresh</Button> : null}
        </CardHeader>
        <CardContent>
          {hasSupervisedSession ? (
            <div className="mb-3 rounded-md border border-border bg-muted/20 px-3 py-2 text-xs text-muted-foreground">
              Supervised Mode is reported and enforced by Hermes-Relay Android, not by the plugin service.
            </div>
          ) : null}
          {error && sessions.length === 0 ? (
            <Alert variant="destructive">
              <AlertTitle>Paired devices unavailable</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : loading ? (
            <div className="text-sm text-muted-foreground">Loading paired devices…</div>
          ) : sessions.length === 0 ? (
            <div className="hr-empty-state">
              <div className="font-medium">No Hermes-Relay devices paired</div>
              <div className="text-sm text-muted-foreground">Pair a device to enable Hermes-Relay capabilities.</div>
            </div>
          ) : (
            <div className="hr-device-list">
              {sessions.map((session, index) => (
                <SessionCard
                  key={sessionTokenPrefix(session) || index}
                  session={session}
                  copied={copied}
                  revoking={revoking}
                  onCopy={onCopyPrefix}
                  onRevoke={setPendingRevoke}
                />
              ))}
            </div>
          )}
          {error && sessions.length > 0 ? (
            <div className="mt-3 text-xs text-destructive">Refresh failed: {error}</div>
          ) : null}
        </CardContent>
      </Card>

      <ConfirmDialog
        open={!!pendingRevoke}
        title="Revoke Hermes-Relay device"
        description={pendingRevoke
          ? `${pendingRevoke.label} will lose Hermes-Relay access and must pair again.`
          : "This device will lose Hermes-Relay access."}
        confirmLabel="Revoke"
        destructive
        loading={!!revoking}
        onCancel={() => setPendingRevoke(null)}
        onConfirm={confirmRevoke}
      />
      <PairDialog open={pairOpen} onClose={() => { setPairOpen(false); load(); }} />
    </div>
  );
}

export function RelaySettings({ autoRefresh }) {
  const [category, setCategory] = useState("general");
  const [agentContext, setAgentContext] = useState(null);
  const [contextError, setContextError] = useState(null);
  const [contextSaving, setContextSaving] = useState(null);
  const [phoneConfig, setPhoneConfig] = useState(null);
  const [phoneError, setPhoneError] = useState(null);
  const [updateInfo, setUpdateInfo] = useState(null);
  const { toast, showToast } = useToast();

  const loadContext = useCallback(async () => {
    try {
      setAgentContext(await getAgentContext());
      setContextError(null);
    } catch (error) {
      setContextError(errorMessage(error));
    }
  }, []);

  const loadPhone = useCallback(async () => {
    try {
      setPhoneConfig(await getPhoneConfig());
      setPhoneError(null);
    } catch (error) {
      setPhoneError(errorMessage(error));
    }
  }, []);

  const loadUpdate = useCallback(async (refresh = false) => {
    try {
      setUpdateInfo(await getUpdateCheck({ refresh }));
    } catch (error) {
      setUpdateInfo({ error: errorMessage(error) });
    }
  }, []);

  useEffect(() => {
    loadContext();
    loadPhone();
    loadUpdate(false);
  }, [loadContext, loadPhone, loadUpdate]);

  useEffect(() => {
    if (!autoRefresh) return undefined;
    const id = setInterval(() => {
      loadContext();
      loadPhone();
    }, 15000);
    return () => clearInterval(id);
  }, [autoRefresh, loadContext, loadPhone]);

  const onToggleAgentContext = useCallback(async (key, checked) => {
    setContextSaving(key);
    try {
      await putEnvSetting(key, checked ? "1" : "0");
      await loadContext();
      showToast("Hermes-Relay Agent Context updated", "success");
    } catch (error) {
      showToast(`Agent Context update failed: ${errorMessage(error)}`, "error");
    } finally {
      setContextSaving(null);
    }
  }, [loadContext, showToast]);

  const categories = [
    { key: "general", label: "General" },
    { key: "context", label: "Agent Context" },
    { key: "maintenance", label: "Maintenance" },
  ];

  return (
    <div className="hr-settings-layout">
      <Toast toast={toast} />
      <aside className="hr-settings-nav" aria-label="Hermes-Relay settings sections">
        <div className="hr-settings-nav-label">Settings</div>
        {categories.map((item) => (
          <button
            key={item.key}
            type="button"
            className={`hr-settings-nav-item ${category === item.key ? "active" : ""}`}
            aria-current={category === item.key ? "page" : undefined}
            onClick={() => setCategory(item.key)}
          >
            {item.label}
          </button>
        ))}
      </aside>

      <div className="hr-settings-content">
        {category === "general" ? (
          phoneError && !phoneConfig ? (
            <Alert variant="destructive">
              <AlertTitle>General settings unavailable</AlertTitle>
              <AlertDescription>{phoneError}</AlertDescription>
            </Alert>
          ) : phoneConfig && phoneConfig.enabled ? (
            <HomeChannelCard
              key={phoneConfig.home_channel_name || "phone"}
              config={phoneConfig}
              onSaved={loadPhone}
            />
          ) : (
            <Card>
              <CardHeader>
                <CardTitle>General</CardTitle>
                <CardDescription>Phone Home Channel is not enabled for this Hermes-Relay installation.</CardDescription>
              </CardHeader>
            </Card>
          )
        ) : null}

        {category === "context" ? (
          contextError && !agentContext ? (
            <Alert variant="destructive">
              <AlertTitle>Agent Context unavailable</AlertTitle>
              <AlertDescription>{contextError}</AlertDescription>
            </Alert>
          ) : agentContext ? (
            <AgentContextCard
              data={agentContext}
              saving={contextSaving}
              onToggle={onToggleAgentContext}
            />
          ) : (
            <div className="text-sm text-muted-foreground">Loading Agent Context…</div>
          )
        ) : null}

        {category === "maintenance" ? (
          updateInfo ? (
            <UpdateCheckCard info={updateInfo} onRefresh={loadUpdate} />
          ) : (
            <div className="text-sm text-muted-foreground">Checking Hermes-Relay version…</div>
          )
        ) : null}
      </div>
    </div>
  );
}
