"""Run Hermes Reach as a standalone public rendezvous service."""

from __future__ import annotations

import argparse
import logging
import ssl
from pathlib import Path

from aiohttp import web

from .server import BrokerConfig, create_app


def main() -> None:
    parser = argparse.ArgumentParser(description="Hermes Reach opaque rendezvous broker")
    parser.add_argument("--credentials", type=Path, required=True,
                        help="private JSON file containing hashed host credentials")
    parser.add_argument("--state", type=Path, required=True,
                        help="private persisted hashed route credential state")
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9444)
    parser.add_argument("--tls-cert", type=Path)
    parser.add_argument("--tls-key", type=Path)
    parser.add_argument("--insecure-dev", action="store_true",
                        help="allow ws:// only on a loopback listener")
    args = parser.parse_args()
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error("--tls-cert and --tls-key must be provided together")
    if not args.tls_cert and (not args.insecure_dev or args.listen not in {"127.0.0.1", "::1", "localhost"}):
        parser.error("public listeners require --tls-cert and --tls-key")
    context: ssl.SSLContext | None = None
    if args.tls_cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.tls_cert, args.tls_key)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    config = BrokerConfig.from_file(args.credentials)
    config.state_path = args.state
    web.run_app(create_app(config),
                host=args.listen, port=args.port, ssl_context=context)


if __name__ == "__main__":
    main()
