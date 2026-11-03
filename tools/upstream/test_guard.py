"""未信頼の差分を実行しないこと、監視の通知条件と停止条件を検証する。"""

import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

import guard


BASE = "1" * 40
HEAD = "2" * 40
CONFIG = {"repository": "wangzan101/Mika", "branch": "main", "accepted_commit": BASE,
          "maintained_repository": "yebisu0529-ship-it/Mika-JP"}
ENVIRONMENT = {"GITHUB_REPOSITORY": CONFIG["maintained_repository"]}


class FakeGitHub:
    def __init__(self, files=None, status="ahead", head=HEAD, issues=None, token="test-token"):
        self.files = [{"filename": "core/agent/Prompt.kt"}] if files is None else files
        self.status, self.head, self.issues, self.token = status, head, issues or [], token
        self.calls = []

    def request(self, path, method="GET", body=None):
        self.calls.append((path, method, body))
        if method != "GET":
            if method == "PATCH":
                for existing in self.issues:
                    if path.endswith("/" + str(existing["number"])):
                        existing.update(body)
            return {"number": 7}
        if "/commits/" in path:
            return {"sha": self.head}
        if "/compare/" in path:
            return {"files": self.files, "status": self.status, "total_commits": 3}
        return self.issues


def report(client=None):
    return guard.collect_watch(client or FakeGitHub(), CONFIG)


def issue(body, state="open", author="github-actions[bot]"):
    return {"number": 7, "body": body, "state": state, "user": {"login": author}}


class WatchTests(unittest.TestCase):
    def test_no_upstream_update_requires_no_review(self):
        result = report(FakeGitHub(files=[], status="identical", head=BASE))
        self.assertFalse(result["requires_review"])
        self.assertFalse(result["requires_manual_investigation"])

    def test_force_push_or_rewind_is_flagged(self):
        for status in ("diverged", "behind"):
            with self.subTest(status=status):
                self.assertTrue(report(FakeGitHub(status=status))["requires_manual_investigation"])

    def test_api_file_limit_cannot_be_treated_as_complete(self):
        result = report(FakeGitHub(files=[{"filename": f"src/{n}.kt"} for n in range(300)]))
        self.assertFalse(result["file_list_complete"])
        self.assertTrue(result["requires_manual_investigation"])
        self.assertIn("300ファイル", guard.watch_markdown(result))

    def test_invalid_upstream_sha_stops(self):
        client = FakeGitHub(head="main; echo injected")
        with self.assertRaises(guard.GuardError):
            report(client)
        self.assertEqual(len(client.calls), 1)

    def test_changed_time_does_not_trigger_notification(self):
        first, second = report(), report()
        second["checked_at"] = "different-time"
        self.assertEqual(guard.fingerprint(first), guard.fingerprint(second))

    def test_new_baseline_or_candidate_invalidates_notification_state(self):
        first = report()
        for key in ("base", "candidate"):
            changed = copy.deepcopy(first)
            changed[key] = "3" * 40
            self.assertNotEqual(guard.fingerprint(first), guard.fingerprint(changed))

    def test_same_candidate_does_not_write_issue_again(self):
        current = report()
        client = FakeGitHub(issues=[issue(guard.watch_markdown(current))])
        self.assertEqual(guard.publish_issue(client, CONFIG, current, ENVIRONMENT), "unchanged")
        self.assertTrue(all(method == "GET" for _, method, _ in client.calls))

    def test_manually_closed_unchanged_issue_stays_closed(self):
        current = report()
        client = FakeGitHub(issues=[issue(guard.watch_markdown(current), state="closed")])
        self.assertEqual(guard.publish_issue(client, CONFIG, current, ENVIRONMENT), "unchanged")
        self.assertTrue(all(method == "GET" for _, method, _ in client.calls))

    def test_new_candidate_updates_existing_issue(self):
        old = report(FakeGitHub(head="3" * 40))
        client = FakeGitHub(issues=[issue(guard.watch_markdown(old))])
        self.assertEqual(guard.publish_issue(client, CONFIG, report(), ENVIRONMENT), "update")
        self.assertEqual(client.calls[-1][1], "PATCH")

    def test_no_change_and_no_issue_does_not_create_one(self):
        client = FakeGitHub()
        current = report(FakeGitHub(files=[], status="identical", head=BASE))
        self.assertEqual(guard.publish_issue(client, CONFIG, current, ENVIRONMENT), "unchanged")
        self.assertTrue(all(method == "GET" for _, method, _ in client.calls))

    def test_accepted_candidate_closes_issue(self):
        client = FakeGitHub(issues=[issue(guard.watch_markdown(report()))])
        current = report(FakeGitHub(files=[], status="identical", head=BASE))
        self.assertEqual(guard.publish_issue(client, CONFIG, current, ENVIRONMENT), "close")
        self.assertEqual(client.calls[-1][2]["state"], "closed")

    def test_reappearing_candidate_reopens_issue_after_upstream_rewind(self):
        current = report()
        client = FakeGitHub(issues=[issue(guard.watch_markdown(current))])
        rewind = report(FakeGitHub(files=[], status="identical", head=BASE))
        self.assertEqual(guard.publish_issue(client, CONFIG, rewind, ENVIRONMENT), "close")
        self.assertEqual(guard.publish_issue(client, CONFIG, current, ENVIRONMENT), "update")
        self.assertEqual(client.issues[0]["state"], "open")

    def test_baseline_rollback_reopens_automatically_completed_issue(self):
        current = report()
        client = FakeGitHub(issues=[issue(guard.watch_markdown(current))])
        accepted_config = dict(CONFIG, accepted_commit=HEAD)
        accepted = guard.collect_watch(FakeGitHub(files=[], status="identical"), accepted_config)
        self.assertEqual(guard.publish_issue(client, accepted_config, accepted, ENVIRONMENT), "close")
        self.assertEqual(guard.publish_issue(client, CONFIG, current, ENVIRONMENT), "update")
        self.assertEqual(client.issues[0]["state"], "open")

    def test_token_and_target_repository_are_required_for_writes(self):
        for client, env in ((FakeGitHub(token=""), ENVIRONMENT), (FakeGitHub(), {}),
                            (FakeGitHub(), {"GITHUB_REPOSITORY": "other/fork"})):
            with self.subTest(environment=env), self.assertRaises(guard.GuardError):
                guard.publish_issue(client, CONFIG, report(), env)
            self.assertEqual(client.calls, [])

    def test_marker_copied_by_other_account_is_not_updated(self):
        client = FakeGitHub(issues=[issue(guard.watch_markdown(report()), author="someone")])
        self.assertEqual(guard.publish_issue(client, CONFIG, report(), ENVIRONMENT), "create")
        self.assertEqual(client.calls[-1][1], "POST")

    def test_untrusted_filename_cannot_inject_markdown_or_mentions(self):
        filename = "x/`\n@everyone <script>\u202eevil.kt"
        text = guard.code(filename)
        self.assertNotIn("\n", text)
        self.assertNotIn("<script>", text)
        self.assertNotIn("\u202e", text)
        self.assertEqual(text.count("`"), 2)

    def test_long_unicode_paths_cannot_overflow_the_issue_body(self):
        names = [".github/root/sandbox/knowledge/japan/build.gradle.kts/" + "あ" * 1000 + str(n) for n in range(10)]
        current = report(FakeGitHub(files=[{"filename": name} for name in names]))
        self.assertLess(len(guard.watch_markdown(current)), 60000)

    def test_redirect_cannot_forward_token_to_other_server(self):
        with self.assertRaises(guard.GuardError):
            guard.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.example")

    def test_arbitrary_api_hosts_or_paths_are_rejected(self):
        for path in ("https://other.example", "//other.example/repos/a", "/repos/../secret", "/repos/%2e%2e/secret"):
            with self.subTest(path=path), self.assertRaises(guard.GuardError):
                guard.GitHub("secret").request(path)

    def test_compare_three_dot_syntax_is_allowed_on_the_fixed_api_host(self):
        client = guard.GitHub("test-token")
        with patch.object(client.opener, "open", return_value=io.BytesIO(b'{}')) as opener:
            client.request(f"/repos/wangzan101/Mika/compare/{BASE}...{HEAD}?per_page=100")
        request = opener.call_args.args[0]
        self.assertTrue(request.full_url.startswith("https://api.github.com/repos/wangzan101/Mika/compare/"))

    def test_prompt_binary_and_workflow_changes_are_risk_signals(self):
        paths = [".github/workflows/release.yml", "app/src/main/jniLibs/arm64-v8a/a.so",
                 "core/src/main/resources/skills/agent/SKILL.md", "app/RootControl.kt"]
        result = guard.categories(paths)
        self.assertIn(paths[0], result["CI・ビルド・依存関係"])
        self.assertIn(paths[1], result["バイナリ・同梱実行環境"])
        self.assertIn(paths[2], result["AIへの指示・内蔵知識"])
        self.assertIn(paths[3], result["通信・認証・データ・権限"])


class DiffTests(unittest.TestCase):
    def test_existing_output_is_preserved_and_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp)
            previous = destination / "changes.patch"
            previous.write_bytes(b"previous-candidate")
            with self.assertRaises(guard.GuardError):
                guard.prepare_output(destination)
            self.assertEqual(previous.read_bytes(), b"previous-candidate")

    def test_existing_output_stops_before_api_calls_or_issue_writes(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(sys, "argv", ["guard.py", "watch", "--publish-issue", "--output-dir", temp]), \
                    patch.object(guard, "GitHub") as client, patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(guard.main(), 1)
                client.assert_not_called()

    def test_fixed_shas_are_required_before_fetch_or_git_execution(self):
        with patch.object(guard, "git") as git:
            for candidate in ("main", "--upload-pack=payload", "a" * 39):
                with self.subTest(candidate=candidate), self.assertRaises(guard.GuardError):
                    guard.collect_audit(Path("."), BASE, candidate, CONFIG, "upstream")
            git.assert_not_called()

    def test_nul_separated_names_preserve_unusual_filenames(self):
        raw = "A\0src/a\tb\nc.kt\0D\0old.kt\0".encode()
        self.assertEqual(guard.parse_names(raw), [{"status": "A", "path": "src/a\tb\nc.kt"},
                                                 {"status": "D", "path": "old.kt"}])

    def test_incomplete_filename_list_stops(self):
        for raw in (b"A\0file", b"A\0file\0unexpected\0"):
            with self.subTest(raw=raw), self.assertRaises(guard.GuardError):
                guard.parse_names(raw)

    def test_permissions_and_urls_keep_added_and_removed_directions(self):
        patch_bytes = (b"diff --git a/manifest b/manifest\n--- a/manifest\n+++ b/manifest\n@@ -1 +1 @@\n"
                       b"-https://old.example/api android.permission.INTERNET\n"
                       b"+https://new.example/upload android.permission.READ_SMS\n")
        result = guard.diff_signals(patch_bytes)
        self.assertEqual(result["added_urls"], ["https://new.example/upload"])
        self.assertEqual(result["removed_urls"], ["https://old.example/api"])
        self.assertEqual(result["added_permissions"], ["android.permission.READ_SMS"])

    def test_source_separators_and_double_signs_cannot_hide_urls_or_permissions(self):
        text = ("diff --git a/code b/code\n--- a/code\n+++ b/code\n@@ -1 +1,2 @@\n"
                "+data\rhttps://cr.example/ android.permission.READ_CONTACTS\u2028https://unicode.example/\n"
                "+++ https://plus.example/ android.permission.READ_SMS\n"
                "--- https://minus.example/ android.permission.SEND_SMS\n")
        result = guard.diff_signals(text.encode())
        self.assertEqual(result["added_urls"], ["https://cr.example/", "https://plus.example/", "https://unicode.example/"])
        self.assertEqual(result["removed_urls"], ["https://minus.example/"])
        self.assertEqual(result["added_permissions"], ["android.permission.READ_CONTACTS", "android.permission.READ_SMS"])
        self.assertEqual(result["removed_permissions"], ["android.permission.SEND_SMS"])

    def test_real_diff_does_not_checkout_or_run_diff_helpers(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            def run(*args):
                return subprocess.check_output(["git", "-c", "user.name=Guard Test", "-c", "user.email=test@example.invalid", *args], cwd=repo).decode().strip()
            run("init", "-q")
            (repo / "data.txt").write_text("https://old.example/\n", encoding="utf-8")
            (repo / ".gitattributes").write_text("*.txt diff=trap\n", encoding="utf-8")
            run("add", ".")
            run("commit", "-qm", "base")
            base = run("rev-parse", "HEAD")
            (repo / "data.txt").write_text("https://new.example/ android.permission.READ_SMS\n", encoding="utf-8")
            run("add", ".")
            run("commit", "-qm", "candidate")
            candidate = run("rev-parse", "HEAD")
            marker = repo / "DIFF_HELPER_EXECUTED"
            helper = repo / "helper.py"
            helper.write_text("from pathlib import Path\nPath('DIFF_HELPER_EXECUTED').write_text('bad')\n", encoding="utf-8")
            command = f'"{sys.executable}" "{helper}"'
            run("config", "diff.external", command)
            run("config", "diff.trap.textconv", command)
            run("diff", base, candidate, "--", "data.txt")
            self.assertTrue(marker.exists(), "外部diffの検査用プログラムが実行できることを先に確認")
            marker.unlink()
            run("config", "--unset", "diff.external")
            run("diff", "--textconv", base, candidate, "--", "data.txt")
            self.assertTrue(marker.exists(), "textconvの検査用プログラムが実行できることを先に確認")
            marker.unlink()
            run("config", "diff.external", command)
            current = (repo / "data.txt").read_bytes()
            result, diff = guard.collect_audit(repo, base, candidate, CONFIG)
            self.assertFalse(marker.exists())
            self.assertEqual(run("rev-parse", "HEAD"), candidate)
            self.assertEqual((repo / "data.txt").read_bytes(), current)
            self.assertEqual(result["changed_files"], 1)
            self.assertIn("https://new.example/", result["added_urls"])
            self.assertIn("未監査", result["security_verdict"])
            self.assertIn(b"android.permission.READ_SMS", diff)
            self.assertEqual(result["patch_sha256"], guard.hashlib.sha256(diff).hexdigest())

    def test_authentication_request_cannot_call_existing_credential_helper(self):
        requests = []

        class AuthenticationRequired(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                self.send_response(401)
                self.send_header("WWW-Authenticate", 'Basic realm="guard-test"')
                self.end_headers()

            def log_message(self, *args):
                pass

        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            marker = repo / "CREDENTIAL_HELPER_EXECUTED"
            helper = repo / "helper.py"
            helper.write_text("from pathlib import Path\nPath('CREDENTIAL_HELPER_EXECUTED').write_text('bad')\n", encoding="utf-8")
            env = {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
                   "HTTP_PROXY": "", "HTTPS_PROXY": "", "ALL_PROXY": "", "NO_PROXY": "127.0.0.1"}
            server = ThreadingHTTPServer(("127.0.0.1", 0), AuthenticationRequired)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with patch.dict(os.environ, env):
                    subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
                    command = f'!"{sys.executable}" "{helper}"'
                    subprocess.run(["git", "config", "credential.helper", command], cwd=repo, check=True)
                    # 同じ検査用helperが起動できることを、ダミーの資格情報要求で確認する。
                    subprocess.run(["git", "-c", "core.askPass=", "credential", "fill"], cwd=repo,
                                   env=dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_ASKPASS="", SSH_ASKPASS=""),
                                   input=b"protocol=http\nhost=127.0.0.1\n\n", stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    self.assertTrue(marker.exists())
                    marker.unlink()
                    with self.assertRaises(guard.GuardError):
                        guard.git(repo, "fetch", "--no-auto-maintenance", "--no-write-commit-graph",
                                  f"http://127.0.0.1:{server.server_port}/public.git")
                    self.assertTrue(requests, "fetchのオプションエラーではなくHTTP認証の拒否を確認")
                    self.assertFalse(marker.exists())
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
