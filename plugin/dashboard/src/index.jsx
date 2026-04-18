const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import RelayManagement from "./tabs/RelayManagement.jsx";
import BridgeActivity from "./tabs/BridgeActivity.jsx";
import PushConsole from "./tabs/PushConsole.jsx";
import MediaInspector from "./tabs/MediaInspector.jsx";

const {
  Tabs,
  TabsList,
  TabsTrigger,
  TabsContent,
  Switch,
  Label,
} = SDK.components;

const AUTO_REFRESH_KEY = "hermes-relay-autorefresh";

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
            Paired devices, bridge activity, push, and media for hermes-relay.
          </p>
        </div>
        <div className="flex items-center gap-2">
          {Switch ? (
            <Switch
              id="auto-refresh"
              checked={autoRefresh}
              onCheckedChange={setAutoRefresh}
            />
          ) : (
            <input
              id="auto-refresh"
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
            />
          )}
          {Label ? (
            <Label htmlFor="auto-refresh">Auto-refresh</Label>
          ) : (
            <label htmlFor="auto-refresh" className="text-sm">
              Auto-refresh
            </label>
          )}
        </div>
      </div>

      <Tabs value={tab} onValueChange={setTab} className="w-full">
        <TabsList>
          <TabsTrigger value="management">Management</TabsTrigger>
          <TabsTrigger value="activity">Activity</TabsTrigger>
          <TabsTrigger value="push">Push</TabsTrigger>
          <TabsTrigger value="media">Media</TabsTrigger>
        </TabsList>
        <TabsContent value="management" className="mt-4">
          <RelayManagement autoRefresh={autoRefresh} />
        </TabsContent>
        <TabsContent value="activity" className="mt-4">
          <BridgeActivity autoRefresh={autoRefresh} />
        </TabsContent>
        <TabsContent value="push" className="mt-4">
          <PushConsole />
        </TabsContent>
        <TabsContent value="media" className="mt-4">
          <MediaInspector autoRefresh={autoRefresh} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

// Register with the dashboard plugin host.
if (typeof window !== "undefined") {
  const hub = window.__HERMES_PLUGINS__;
  if (hub && typeof hub.register === "function") {
    hub.register("hermes-relay", RelayPluginRoot);
  } else {
    // Surface a clear console error if the SDK is missing — helps operators
    // distinguish "plugin didn't load" from "plugin has a runtime error".
    // eslint-disable-next-line no-console
    console.error(
      "[hermes-relay] window.__HERMES_PLUGINS__.register unavailable — dashboard shell did not initialize."
    );
  }
}
