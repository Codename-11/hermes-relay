from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check-upstream-route-contract.py"
SPEC = importlib.util.spec_from_file_location("upstream_route_contract", SCRIPT)
assert SPEC and SPEC.loader
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)

DASHBOARD = {"/api/status", "/api/audio/transcribe", "/api/audio/speak", "/api/ws"}
RUNS = {"/v1/runs", "/v1/runs/{run_id}/events"}


def table(paths: set[str]) -> str:
    return "[" + ", ".join(f'("GET", "{path}", handler)' for path in sorted(paths)) + "]"


def decorators(paths: set[str], router: str = "app") -> str:
    return "\n".join(
        f'@{router}.{"websocket" if path == "/api/ws" else "post"}("{path}")\n'
        f"def handler_{index}(): pass"
        for index, path in enumerate(sorted(paths))
    ) + "\n"


class RouteContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, path: str, text: str) -> None:
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text, encoding="utf-8")

    def monolithic(self, missing: set[str] | None = None) -> None:
        missing = missing or set()
        self.write(contract.API_SERVER, "routes = " + table(contract.REQUIRED - DASHBOARD - missing))
        self.write(contract.WEB_SERVER, decorators(DASHBOARD - missing))

    def modular(self) -> None:
        self.write(contract.API_SERVER, '''
from gateway.platforms import api_server_runs as _runs
def _http_route_table(self):
    routes = TABLE
    routes.extend(_runs._http_routes(self))
    return routes
'''.replace("TABLE", table(contract.REQUIRED - DASHBOARD - RUNS)))
        self.write("gateway/platforms/api_server_runs.py",
                   "def _http_routes(self):\n    return " + table(RUNS))
        self.write(contract.WEB_SERVER, '''
from hermes_cli.web_routers import (
    audio as _audio_routes,
    status as _status_routes,
    chat_ws as _chat_routes,
)
app.include_router(_audio_routes.router)
app.include_router(_status_routes.router)
app.include_router(_chat_routes.router)
''')
        for name, paths in (("audio", {"/api/audio/transcribe", "/api/audio/speak"}),
                            ("status", {"/api/status"}), ("chat_ws", {"/api/ws"})):
            self.write(f"hermes_cli/web_routers/{name}.py",
                       "router = APIRouter()\n" + decorators(paths, "router"))

    def run_check(self) -> tuple[int, str]:
        output = io.StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), str(self.root)]):
            with contextlib.redirect_stdout(output):
                result = contract.main()
        return result, output.getvalue()

    def test_monolithic_layout(self) -> None:
        self.monolithic()
        code, output = self.run_check()
        self.assertEqual(code, 0, output)
        self.assertIn("all 12 REQUIRED", output)
        self.assertIn("ADVISORY routes absent", output)

    def test_monolithic_aiohttp_add_methods(self) -> None:
        self.monolithic()
        self.write(contract.API_SERVER, "\n".join(
            f'self._app.router.add_get("{path}", handler)'
            for path in contract.REQUIRED - DASHBOARD
        ))
        self.assertEqual(self.run_check()[0], 0)

    def test_modular_layout_without_executing_source(self) -> None:
        self.modular()
        path = self.root / contract.WEB_SERVER
        path.write_text(path.read_text() + "\nraise RuntimeError('must never execute')\n")
        code, output = self.run_check()
        self.assertEqual(code, 0, output)
        self.assertIn("6 registered source files", output)
        self.assertIn("gateway/platforms/api_server_runs.py", output)

    def test_every_missing_required_route_fails(self) -> None:
        for route in contract.REQUIRED:
            with self.subTest(route=route):
                self.monolithic({route})
                code, output = self.run_check()
                self.assertEqual(code, 1)
                self.assertIn(f"[MISSING] {route}\n", output)

    def test_unregistered_dashboard_modules_do_not_count(self) -> None:
        self.modular()
        self.write(contract.WEB_SERVER, '''
from hermes_cli.web_routers import audio, status, chat_ws
# app.include_router(audio.router)
example = "app.include_router(status.router)"
''')
        code, output = self.run_check()
        self.assertEqual(code, 1)
        for route in DASHBOARD:
            self.assertIn(f"[MISSING] {route}\n", output)

    def test_only_mounted_router_in_a_module_counts(self) -> None:
        self.modular()
        self.write("hermes_cli/web_routers/audio.py",
                   "router = APIRouter()\nunused = APIRouter()\n"
                   + decorators({"/api/audio/transcribe"}, "router")
                   + decorators({"/api/audio/speak"}, "unused"))
        code, output = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("[MISSING] /api/audio/speak\n", output)

    def test_api_helper_must_be_consumed(self) -> None:
        self.modular()
        path = self.root / contract.API_SERVER
        source = path.read_text().replace("routes.extend(_runs._http_routes(self))",
                                          "_runs._http_routes(self)")
        path.write_text(source)
        code, output = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("[MISSING] /v1/runs\n", output)

    def test_only_consumed_api_helper_function_counts(self) -> None:
        self.modular()
        self.write("gateway/platforms/api_server_runs.py",
                   "def _http_routes(self):\n    return []\n"
                   + "def unused(self):\n    return " + table(RUNS))
        code, output = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("[MISSING] /v1/runs\n", output)

    def test_direct_symbol_imports(self) -> None:
        self.modular()
        self.write(contract.API_SERVER, '''
from gateway.platforms.api_server_runs import _http_routes as run_routes
routes = TABLE
routes.extend(run_routes(self))
'''.replace("TABLE", table(contract.REQUIRED - DASHBOARD - RUNS)))
        self.write(contract.WEB_SERVER, '''
from hermes_cli.web_routers.audio import router as audio_router
import hermes_cli.web_routers.status as status
import hermes_cli.web_routers.chat_ws
app.include_router(audio_router)
app.include_router(status.router)
app.include_router(hermes_cli.web_routers.chat_ws.router)
''')
        code, output = self.run_check()
        self.assertEqual(code, 0, output)

    def test_nested_router_and_prefixes(self) -> None:
        self.monolithic()
        self.write(contract.WEB_SERVER, '''
from hermes_cli.web_routers import audio
app.include_router(audio.parent, prefix="/api")
''' + decorators({"/api/status", "/api/ws"}))
        self.write("hermes_cli/web_routers/audio.py", '''
parent = APIRouter()
router = APIRouter(prefix="/audio")
parent.include_router(router)
''' + decorators({"/transcribe", "/speak"}, "router"))
        code, output = self.run_check()
        self.assertEqual(code, 0, output)

    def test_changed_mount_prefix_does_not_satisfy_required_paths(self) -> None:
        self.modular()
        path = self.root / contract.WEB_SERVER
        path.write_text(path.read_text().replace("_audio_routes.router)",
                                                 '_audio_routes.router, prefix="/other")'))
        code, output = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("[MISSING] /api/audio/speak\n", output)

    def test_missing_registered_source_fails(self) -> None:
        for path in (contract.API_SERVER, contract.WEB_SERVER,
                     "gateway/platforms/api_server_runs.py", "hermes_cli/web_routers/audio.py"):
            with self.subTest(path=path):
                self.modular()
                (self.root / path).unlink()
                code, output = self.run_check()
                self.assertEqual(code, 1)
                self.assertIn("MISSING SOURCE FILE", output)

    def test_missing_registered_member_fails(self) -> None:
        for path, source, diagnostic in (
            ("gateway/platforms/api_server_runs.py", "def unused(self): pass", "MISSING ROUTE HELPER"),
            ("hermes_cli/web_routers/audio.py", "unused = APIRouter()", "MISSING ROUTER"),
        ):
            with self.subTest(path=path):
                self.modular()
                self.write(path, source)
                code, output = self.run_check()
                self.assertEqual(code, 1)
                self.assertIn(diagnostic, output)

    def test_fork_markers_rejected_in_entry_and_registered_api_module(self) -> None:
        for path in (contract.API_SERVER, "gateway/platforms/api_server_runs.py"):
            for marker in contract.FORK_MARKERS:
                with self.subTest(path=path, marker=marker):
                    self.modular()
                    destination = self.root / path
                    destination.write_text(destination.read_text() + f"\n# {marker}\n")
                    code, output = self.run_check()
                    self.assertEqual(code, 1)
                    self.assertIn("FORK MARKERS", output)

    def test_unregistered_sibling_is_not_parsed(self) -> None:
        self.monolithic()
        self.write("gateway/platforms/api_server_unused.py", "hermes_relay\nnot valid Python!")
        self.write("hermes_cli/web_routers/unused.py", "not valid Python!")
        self.assertEqual(self.run_check()[0], 0)

    def test_invalid_source_fails(self) -> None:
        self.modular()
        self.write("hermes_cli/web_routers/audio.py", "def broken(")
        code, output = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("FAIL:", output)

    def test_dynamic_plugin_mount_is_outside_vanilla_surface(self) -> None:
        self.monolithic()
        path = self.root / contract.WEB_SERVER
        path.write_text(path.read_text() + '''
def mount_plugin(mod, plugin):
    router = getattr(mod, "router", None)
    app.include_router(router, prefix=f"/api/plugins/{plugin['name']}")
''')
        code, output = self.run_check()
        self.assertEqual(code, 0, output)

    def test_cyclic_router_registration_fails(self) -> None:
        self.modular()
        self.write("hermes_cli/web_routers/audio.py", '''
router = APIRouter()
router.include_router(router)
''')
        code, output = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("CYCLIC ROUTER REGISTRATION", output)

    def test_dynamic_prefix_fails_closed(self) -> None:
        self.modular()
        path = self.root / contract.WEB_SERVER
        path.write_text(path.read_text().replace("_audio_routes.router)",
                                                 "_audio_routes.router, prefix=config_prefix)"))
        code, output = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("non-literal router prefix", output)


class WorkflowExtractionTests(unittest.TestCase):
    def test_extracts_both_layouts_from_exact_git_tree(self) -> None:
        git = shutil.which("git")
        bash = shutil.which("bash")
        if sys.platform == "win32" and git:
            candidate = Path(git).resolve().parents[1] / "bin/bash.exe"
            if candidate.is_file():
                bash = str(candidate)
        if not git or not bash:
            self.skipTest("git and bash are required for workflow extraction coverage")
        workflow = (ROOT / ".github/workflows/ci-contract.yml").read_text(encoding="utf-8")
        start = workflow.index('          git -C "$UPSTREAM_GIT" ls-tree')
        end = workflow.index('          echo "Extracted contract sources', start)
        extraction = "\n".join(line[10:] for line in workflow[start:end].splitlines())
        for modular in (False, True):
            with self.subTest(modular=modular), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                repo = root / "source"
                repo.mkdir()
                files = {contract.API_SERVER: "# api", contract.WEB_SERVER: "# dashboard",
                         "hermes_cli/unrelated.py": "raise RuntimeError()"}
                if modular:
                    files.update({"gateway/platforms/api_server_runs.py": "# runs",
                                  "hermes_cli/web_routers/audio.py": "# audio",
                                  "hermes_cli/web_routers/nested/status.py": "# status"})
                for name, content in files.items():
                    path = repo / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(content)
                def run_git(*args: str) -> str:
                    return subprocess.check_output([git, "-C", str(repo), *args], text=True).strip()
                run_git("init", "--quiet")
                run_git("add", ".")
                run_git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
                        "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "fixture")
                sha = run_git("rev-parse", "HEAD")
                # Verify extraction reads the commit, rather than modified working files.
                (repo / contract.API_SERVER).write_text("changed after commit")
                environment = {**os.environ, "UPSTREAM_GIT": repo.as_posix(), "UPSTREAM_COMMIT": sha}
                subprocess.run([bash, "-euo", "pipefail", "-c", extraction],
                               cwd=root, env=environment, check=True, capture_output=True, text=True)
                extracted = root / "_upstream"
                for name, content in files.items():
                    if name == "hermes_cli/unrelated.py":
                        self.assertFalse((extracted / name).exists())
                    else:
                        self.assertEqual((extracted / name).read_text(), content)


if __name__ == "__main__":
    unittest.main()
