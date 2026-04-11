"""
hermes-relay plugin — registers android_* tools and the `hermes pair`
CLI sub-command into hermes-agent via the v0.3.0+ plugin system.

Drop this folder into ~/.hermes/plugins/hermes-relay and restart hermes.
Run `hermes pair` to generate a QR code for pairing the Hermes-Relay app.
"""

from .android_tool import _SCHEMAS, _HANDLERS, _check_requirements


def register(ctx):
    """Called by hermes-agent plugin loader. Registers tools and CLI commands."""
    # Register 14 android_* tools
    for tool_name, schema in _SCHEMAS.items():
        ctx.register_tool(
            name=tool_name,
            toolset="android",
            schema=schema,
            handler=_HANDLERS[tool_name],
            check_fn=(lambda: True) if tool_name == "android_setup" else _check_requirements,
        )

    # Register CLI sub-commands: hermes pair + hermes relay (v0.8.0+)
    # Wrapped in try/except so the plugin still works on older hermes-agent
    # versions that don't expose register_cli_command.
    try:
        from .cli import (
            register_cli,
            pair_command,
            register_relay_cli,
            relay_start_command,
        )

        ctx.register_cli_command(
            name="pair",
            help="Generate QR code for pairing mobile devices with the Hermes API server",
            setup_fn=register_cli,
            handler_fn=pair_command,
            description=(
                "Reads API server config (host, port, key) and renders a scannable "
                "QR code in the terminal. Use to connect Hermes-Relay Android or any "
                "mobile client to your gateway."
            ),
        )

        ctx.register_cli_command(
            name="relay",
            help="Run the Hermes-Relay WSS server (terminal + bridge channels)",
            setup_fn=register_relay_cli,
            handler_fn=relay_start_command,
            description=(
                "Runs the embedded WSS relay server that the Hermes-Relay Android "
                "app connects to for the terminal and bridge channels. Chat still "
                "goes direct to the Hermes API server."
            ),
        )
    except (AttributeError, ImportError):
        # Older hermes-agent (v0.7.0 and earlier) — CLI commands not registered.
        # The 14 tools above still work; users can fall back to `python -m plugin.relay`.
        pass
