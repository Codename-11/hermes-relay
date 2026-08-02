# Plugins

Hermes Android can display full native pages contributed by installed Hermes plugins.
Plugin pages support reactive text, cards, controls, progress, images, conditional
content, and app-rendered animation without downloading executable code to the phone.

Open **Settings → Plugins** to enable a plugin, review any capabilities it requests,
and open its pages. Plugin enablement and grants are kept separately for each Hermes
connection and profile.

## Agent-created plugin pages

With the optional Hermes-Relay plugin enabled, Hermes can create a declarative plugin
page during a conversation. Drafts normally appear in the Plugins screen within five
seconds while it is open.

Agent-created pages begin as **Draft preview**. The agent cannot publish them on its
own. To keep one:

1. Enable **Relay Plugins**.
2. Enable **Allow changes**.
3. Review the draft page.
4. Select **Keep**.

Removing a kept generated page always requires confirmation in Android. A temporary
draft may be replaced or removed by the agent while the conversation is active. Generated pages
cannot contain scripts, native code, arbitrary web content, external network calls,
Android intents, or Relay management actions. A plugin that needs backend logic must
still be installed as a normal reviewed Hermes plugin.

Agent-created pages require Hermes-Relay. Viewing ordinary Android contributions from
installed plugins continues to use the standard authenticated Hermes Dashboard path.
