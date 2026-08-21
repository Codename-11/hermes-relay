"""Command-line runner for the vanilla Gateway fixture."""

from __future__ import annotations

import argparse
import ipaddress
import ssl
from pathlib import Path
from typing import Sequence

from aiohttp import web

from .scenario import load_scenario
from .server import GatewayFixture


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="hermes-gateway-fixture", description=__doc__)
    parser.add_argument("scenario", help="bundled scenario name or JSON path")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--tls-cert", type=Path, help="PEM certificate chain for HTTPS/WSS")
    parser.add_argument("--tls-key", type=Path, help="PEM private key for HTTPS/WSS")
    return parser


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = build_parser()
    args = parser.parse_args(argv)
    if (args.tls_cert is None) != (args.tls_key is None):
        parser.error("--tls-cert and --tls-key must be provided together")
    if not _is_loopback(args.host) and args.tls_cert is None:
        parser.error("non-loopback bindings require --tls-cert and --tls-key")
    return args


def _is_loopback(host: str) -> bool:
    if host.casefold() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def create_tls_context(certificate: Path, key: Path) -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certfile=certificate, keyfile=key)
    return context


def main(argv: Sequence[str] | None = None) -> None:
    args = parse_args(argv)
    fixture = GatewayFixture(load_scenario(args.scenario))
    tls_context = (
        create_tls_context(args.tls_cert, args.tls_key)
        if args.tls_cert is not None and args.tls_key is not None
        else None
    )
    web.run_app(
        fixture.app,
        host=args.host,
        port=args.port,
        ssl_context=tls_context,
        print=None,
    )


if __name__ == "__main__":
    main()
