const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import RelayManagement from "./tabs/RelayManagement.jsx";
import BridgeActivity from "./tabs/BridgeActivity.jsx";
import MediaInspector from "./tabs/MediaInspector.jsx";
import RemoteAccess from "./tabs/RemoteAccess.jsx";
import { Switch } from "./lib/ui-shims.jsx";

const { Label } = SDK.components;

const AUTO_REFRESH_KEY = "hermes-relay-autorefresh";

const TABS = [
  { key: "management", label: "Management" },
  { key: "activity", label: "Activity" },
  { key: "media", label: "Media" },
  { key: "remote", label: "Remote Access" },
];

function readAutoRefresh() {
  try {
    const raw = window.localStorage.getItem(AUTO_REFRESH_KEY);
    if (raw === null) return true;
    return raw === "true";
  } catch (_err) {
    return true;
  }
}

function writeAutoRefresh(value) {
  try {
    window.localStorage.setItem(AUTO_REFRESH_KEY, value ? "true" : "false");
  } catch (_err) {
    /* localStorage unavailable — ignore */
  }
}

function TabButton({ active, onClick, children }) {
  const base =
    "px-4 py-2 text-sm font-medium border-b-2 transition-colors focus:outline-none focus-visible:ring-1 focus-visible:ring-ring";
  const on = "border-foreground text-foreground";
  const off = "border-transparent text-muted-foreground hover:text-foreground";
  return (
    <button type="button" onClick={onClick} className={`${base} ${active ? on : off}`}>
      {children}
    </button>
  );
}

function RelayPluginRoot() {
  const [tab, setTab] = useState("management");
  const [autoRefresh, setAutoRefreshState] = useState(readAutoRefresh);

  const setAutoRefresh = useCallback((next) => {
    const value = typeof next === "function" ? next(readAutoRefresh()) : !!next;
    setAutoRefreshState(value);
    writeAutoRefresh(value);
  }, []);

  useEffect(() => {
    writeAutoRefresh(autoRefresh);
  }, [autoRefresh]);

  return (
    <div className="space-y-4 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Relay</h1>
          <p className="text-sm text-muted-foreground">
            Paired devices, bridge activity, media, and remote access for hermes-relay.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Switch
            id="auto-refresh"
            checked={autoRefresh}
            onCheckedChange={setAutoRefresh}
          />
          <Label htmlFor="auto-refresh">Auto-refresh</Label>
        </div>
      </div>

      <div role="tablist" className="flex items-center gap-1 border-b border-border">
        {TABS.map((t) => (
          <TabButton
            key={t.key}
            active={tab === t.key}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </TabButton>
        ))}
      </div>

      <div className="mt-4">
        {tab === "management" && <RelayManagement autoRefresh={autoRefresh} />}
        {tab === "activity" && <BridgeActivity autoRefresh={autoRefresh} />}
        {tab === "media" && <MediaInspector autoRefresh={autoRefresh} />}
        {tab === "remote" && <RemoteAccess autoRefresh={autoRefresh} />}
      </div>
    </div>
  );
}

if (typeof window !== "undefined") {
  const hub = window.__HERMES_PLUGINS__;
  if (hub && typeof hub.register === "function") {
    hub.register("hermes-relay", RelayPluginRoot);
  } else {
    // eslint-disable-next-line no-console
    console.error(
      "[hermes-relay] window.__HERMES_PLUGINS__.register unavailable — dashboard shell did not initialize."
    );
  }
}
