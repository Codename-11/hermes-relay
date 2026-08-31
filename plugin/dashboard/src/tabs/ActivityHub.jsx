const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;

import BridgeActivity from "./BridgeActivity.jsx";
import MediaInspector from "./MediaInspector.jsx";

const { Tabs, TabsList, TabsTrigger } = SDK.components;

const VIEWS = [
  { key: "bridge", label: "Bridge activity" },
  { key: "media", label: "Media tokens" },
];

export default function ActivityHub({ autoRefresh }) {
  return (
    <Tabs defaultValue="bridge" className="hr-activity-tabs">
      {(view, setView) => (
        <>
          <TabsList className="hr-activity-tablist" aria-label="Hermes-Relay activity views">
            {VIEWS.map((item) => (
              <TabsTrigger
                key={item.key}
                active={view === item.key}
                value={item.key}
                onClick={() => setView(item.key)}
              >
                {item.label}
              </TabsTrigger>
            ))}
          </TabsList>
          {view === "bridge" ? (
            <BridgeActivity autoRefresh={autoRefresh} />
          ) : (
            <MediaInspector autoRefresh={autoRefresh} />
          )}
        </>
      )}
    </Tabs>
  );
}
