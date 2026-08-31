const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import RelayDevices, {
  RelayOverview,
  RelaySettings,
} from "./tabs/RelayManagement.jsx";
import ActivityHub from "./tabs/ActivityHub.jsx";
import RemoteAccess from "./tabs/RemoteAccess.jsx";
import GitState from "./tabs/GitState.jsx";
import RelayStatusSlot from "./components/RelayStatusSlot.jsx";
import MobileConnectDialog from "./components/MobileConnectDialog.jsx";
import { Switch } from "./lib/ui-shims.jsx";

const { Label, Tabs, TabsList, TabsTrigger } = SDK.components;

const AUTO_REFRESH_KEY = "hermes-relay-autorefresh";

const TABS = [
  { key: "overview", label: "Overview" },
  { key: "devices", label: "Devices" },
  { key: "activity", label: "Activity" },
  { key: "remote", label: "Remote Access" },
  { key: "git", label: "Git" },
  { key: "settings", label: "Settings" },
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

function RelayPluginRoot() {
  const [mobileConnectOpen, setMobileConnectOpen] = useState(false);
  const [autoRefresh, setAutoRefreshState] = useState(readAutoRefresh);

  const setAutoRefresh = useCallback((next) => {
    const value = typeof next === "function" ? next(readAutoRefresh()) : !!next;
    setAutoRefreshState(value);
    writeAutoRefresh(value);
  }, []);

  useEffect(() => {
    writeAutoRefresh(autoRefresh);
  }, [autoRefresh]);

  const openMobileConnect = useCallback(() => setMobileConnectOpen(true), []);
  const closeMobileConnect = useCallback(() => setMobileConnectOpen(false), []);

  return (
    <div className="hermes-relay-plugin p-4">
      <Tabs defaultValue="overview" className="hr-plugin-tabs">
        {(tab, setTab) => (
          <>
            <div className="hr-plugin-toolbar">
              <TabsList className="hr-plugin-tablist">
                {TABS.map((item) => (
                  <TabsTrigger
                    key={item.key}
                    active={tab === item.key}
                    value={item.key}
                    onClick={() => setTab(item.key)}
                  >
                    {item.label}
                  </TabsTrigger>
                ))}
              </TabsList>
              <div className="hr-live-control">
                <Switch
                  id="auto-refresh"
                  checked={autoRefresh}
                  onCheckedChange={setAutoRefresh}
                />
                <Label htmlFor="auto-refresh">Live</Label>
              </div>
            </div>

            <div className="hr-tab-content">
              {tab === "overview" && (
                <RelayOverview
                  autoRefresh={autoRefresh}
                  onConnectMobile={openMobileConnect}
                  onNavigate={setTab}
                />
              )}
              {tab === "devices" && (
                <RelayDevices
                  autoRefresh={autoRefresh}
                  onConnectMobile={openMobileConnect}
                />
              )}
              {tab === "activity" && <ActivityHub autoRefresh={autoRefresh} />}
              {tab === "remote" && <RemoteAccess autoRefresh={autoRefresh} />}
              {tab === "git" && <GitState autoRefresh={autoRefresh} />}
              {tab === "settings" && <RelaySettings autoRefresh={autoRefresh} />}
            </div>
          </>
        )}
      </Tabs>
      <MobileConnectDialog
        open={mobileConnectOpen}
        onClose={closeMobileConnect}
      />
    </div>
  );
}

if (typeof window !== "undefined") {
  const hub = window.__HERMES_PLUGINS__;
  if (hub && typeof hub.register === "function") {
    hub.register("hermes-relay", RelayPluginRoot);
    // Inject a compact status badge into the shell header (visible on every
    // page, not just the Relay tab). registerSlot(pluginName, slotName,
    // Component) — the host stacks slot components in registration order and
    // re-renders <PluginSlot name="header-right" /> when this arrives. Guarded
    // so an older host without slot support still registers the main tab.
    if (typeof hub.registerSlot === "function") {
      hub.registerSlot("hermes-relay", "header-right", RelayStatusSlot);
    }
  } else {
    // eslint-disable-next-line no-console
    console.error(
      "[hermes-relay] window.__HERMES_PLUGINS__.register unavailable — dashboard shell did not initialize."
    );
  }
}
