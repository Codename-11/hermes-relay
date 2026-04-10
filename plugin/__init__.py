"""
hermes-android plugin — registers android_* tools and the `hermes pair`
CLI sub-command into hermes-agent via the v0.3.0+ plugin system.

Drop this folder into ~/.hermes/plugins/hermes-android and restart hermes.
Run `hermes pair` to generate a QR code for pairing the Android app.
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

    # Register CLI sub-command: hermes pair (v0.8.0+)
    # Wrapped in try/except so the plugin still works on older hermes-agent
    # versions that don't expose register_cli_command.
    try:
        from .cli import register_cli, pair_command

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
    except (AttributeError, ImportError):
        # Older hermes-agent (v0.7.0 and earlier) — CLI command not registered.
        # The 14 tools above still work; users can fall back to the deprecated
        # skills/hermes-pairing-qr/hermes-pair script.
        pass
