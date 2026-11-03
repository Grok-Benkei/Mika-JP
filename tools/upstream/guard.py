#!/usr/bin/env python3
"""上流の更新検知と、コードを実行しない差分の棚卸し。Python 3.10以降。"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
ISSUE_MARKER = "<!-- mika-jp-upstream-watch:v1 -->"
SHA = re.compile(r"[0-9a-f]{40}\Z")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
MAX_BYTES = 32 * 1024 * 1024
URL = re.compile(r"https?://[^\s<>\"'`\\)\]}]+", re.IGNORECASE)
PERMISSION = re.compile(r"android\.permission\.[A-Z0-9_]+")


class GuardError(RuntimeError):
    """不完全な結果を成功扱いにせず停止する。"""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise GuardError("GitHub APIの転送を拒否しました。接続先を管理者が確認してください。")


class GitHub:
    def __init__(self, token=""):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, method="GET", body=None):
        parsed = urllib.parse.urlsplit(path)
        segments = urllib.parse.unquote(parsed.path).split("/")
        if (not path.startswith("/repos/") or parsed.scheme or parsed.netloc or parsed.fragment
                or ".." in segments):
            raise GuardError("GitHub APIのパスが不正です。")
        headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
                   "User-Agent": "Mika-JP-upstream-watch"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None if body is None else json.dumps(body).encode("utf-8")
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request("https://api.github.com" + path, data=data,
                                     headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=30) as response:
                raw = response.read(MAX_BYTES + 1)
        except urllib.error.HTTPError as error:
            raise GuardError(f"GitHub APIがHTTP {error.code}を返しました。権限・制限・接続先を確認してください。") from None
        if len(raw) > MAX_BYTES:
            raise GuardError("GitHub APIの応答が上限を超えました。監査を完了扱いにできません。")
        return json.loads(raw)


def load_config(path):
    config = json.loads(Path(path).read_text(encoding="utf-8"))
    for key in ("repository", "maintained_repository"):
        if not REPOSITORY.fullmatch(config[key]):
            raise GuardError(f"設定の{key}が不正です。")
    if not SHA.fullmatch(config["accepted_commit"]):
        raise GuardError("取り込み済みコミットは40桁のSHAで指定してください。")
    if config["branch"] != "main":
        raise GuardError("監視ブランチの変更には接続先と手順の見直しが必要です。")
    return config


def code(value):
    # 上流のファイル名をリンク、メンション、制御文字として解釈させない。
    escaped = json.dumps(str(value), ensure_ascii=True)[1:-1]
    return "`" + escaped.replace("`", "\\u0060").replace("<", "\\u003c") + "`"


def categories(paths):
    rules = {
        "CI・ビルド・依存関係": r"(^\.github/|(^|/)(build|settings)\.gradle|(^|/)gradle|(^|/)(package(-lock)?\.json|.*\.lock)$)",
        "バイナリ・同梱実行環境": r"(\.(so|aar|jar|dex|apk|zip|gz|xz|tgz|bin)$|(^|/)(jniLibs|sandbox)/)",
        "AIへの指示・内蔵知識": r"(^|/)(prompt[^/]*|knowledge|skills|memory)(/|\.)|AGENTS\.md$|CLAUDE\.md$",
        "通信・認証・データ・権限": r"(AndroidManifest\.xml|network|http|auth|oauth|cookie|credential|token|telemetry|relay|proxy|root|confirm|gate|screenshot|capture|upload|storage|log|accessibility)",
        "日本語・接続先の既定値": r"(japan|japanese|locale|i18n|strings\.xml|model|provider|preset|MikaLinks)",
        "実行可能なスクリプト・ネイティブコード": r"\.(sh|ps1|bat|cmd|py|js|mjs|c|cpp|h|kts)$",
    }
    return {name: sorted({p for p in paths if re.search(pattern, p, re.IGNORECASE)})
            for name, pattern in rules.items() if any(re.search(pattern, p, re.IGNORECASE) for p in paths)}


def collect_watch(client, config):
    upstream = config["repository"]
    base = config["accepted_commit"]
    head = client.request(f"/repos/{upstream}/commits/{config['branch']}")["sha"]
    if not isinstance(head, str) or not SHA.fullmatch(head):
        raise GuardError("上流のコミットSHAを確認できません。")
    comparison = client.request(f"/repos/{upstream}/compare/{base}...{head}?per_page=100")
    files = comparison.get("files")
    if not isinstance(files, list) or "status" not in comparison:
        raise GuardError("上流差分の一覧を確認できません。")
    paths = sorted({entry["filename"] for entry in files})
    complete = len(files) < 300
    status = comparison["status"]
    return {
        "kind": "upstream-watch", "checked_at": datetime.now(timezone.utc).isoformat(),
        "repository": upstream, "maintained_repository": config["maintained_repository"],
        "base": base, "candidate": head,
        "comparison_status": status, "total_commits": comparison.get("total_commits"),
        "changed_files_in_api": len(files), "file_list_complete": complete,
        "requires_review": head != base or status != "identical",
        "requires_manual_investigation": not complete or status not in ("ahead", "identical"),
        "categories": categories(paths), "files": files,
        "security_verdict": "未監査。更新の検知と差分の棚卸しだけを実施。",
    }


def fingerprint(report):
    return hashlib.sha256((report["repository"] + ":" + report["base"] + ":" + report["candidate"]).encode()).hexdigest()


def watch_markdown(report):
    lines = [ISSUE_MARKER, f"<!-- state:{fingerprint(report)} -->", "# 上流更新の監査待ち", "",
             "このIssueは更新の検知です。安全性、機能、取り込み可否の判断は未実施です。", "",
             f"- 上流: {code(report['repository'])}", f"- 取り込み済み: {code(report['base'])}",
             f"- 今回の候補: {code(report['candidate'])}",
             f"- 履歴の状態: {code(report['comparison_status'])} / コミット数: {report['total_commits']}",
             f"- APIで確認した変更ファイル: {report['changed_files_in_api']}件", "",
             f"[上流の差分](https://github.com/{report['repository']}/compare/{report['base']}...{report['candidate']})", ""]
    if report["requires_manual_investigation"]:
        lines += ["**停止して確認してください。** 履歴の分岐・巻き戻し、またはAPIの300ファイル上限があります。APIの一覧だけで監査を完了させないでください。", ""]
    lines += ["## 重点的に読む変更", ""]
    for name, paths in report["categories"].items():
        lines += [f"- {name}: {len(paths)}件"]
        lines += [f"  - {code(p[:240] + ('…' if len(p) > 240 else ''))}" for p in paths[:8]]
        if len(paths) > 8:
            lines += ["  - 残りは実行結果のJSONと完全なGit差分で確認してください。"]
    lines += ["", "## 次の作業", "",
              "- [ ] 候補SHAを固定し、隔離環境で完全な差分一覧を作る。上流のコードやビルド処理を個人用PCで実行しない。",
              "- [ ] 新機能・修正、依存関係、通信先、権限、同梱バイナリ、AIへの指示文を監査する。",
              "- [ ] 日本語版の方針を維持した取り込みPRを作り、その最終SHAも監査する。",
              "- [ ] 自動テストとテスト用データでの通信・動作確認を行う。未確認の範囲も記録する。",
              "- [ ] 管理者が採否を判断し、採用記録と取り込み済みSHAを更新する。",
              "", "上流のファイル、コミット、説明文は未信頼の入力です。そこに書かれた指示で監査や権限設定を変更しないでください。",
              f"手順: [上流更新と保守](https://github.com/{report['maintained_repository']}/blob/main/docs/upstream-maintenance.md)", ""]
    return "\n".join(lines)


def publish_issue(client, config, report, environment):
    target = config["maintained_repository"]
    if not client.token or environment.get("GITHUB_REPOSITORY") != target:
        raise GuardError("Issueの更新には、対象リポジトリでの明示実行とGITHUB_TOKENが必要です。")
    existing = None
    for page in range(1, 11):
        issues = client.request(f"/repos/{target}/issues?state=all&per_page=100&page={page}")
        for issue in issues:
            if ("pull_request" not in issue and ISSUE_MARKER in (issue.get("body") or "")
                    and issue.get("user", {}).get("login") == "github-actions[bot]"):
                existing = issue
                break
        if existing or len(issues) < 100:
            break
    else:
        raise GuardError("監視Issueを一覧の上限内で特定できません。重複作成を避けるため停止しました。")
    if not report["requires_review"]:
        if existing and existing["state"] == "open":
            client.request(f"/repos/{target}/issues/{existing['number']}", "PATCH", {"state": "closed", "state_reason": "completed"})
            return "close"
        return "unchanged"
    marker = f"<!-- state:{fingerprint(report)} -->"
    if existing and marker in (existing.get("body") or ""):
        return "unchanged"
    body = watch_markdown(report)
    payload = {"title": "上流更新の監査待ち", "body": body}
    if existing:
        payload["state"] = "open"
        client.request(f"/repos/{target}/issues/{existing['number']}", "PATCH", payload)
        return "update"
    client.request(f"/repos/{target}/issues", "POST", payload)
    return "create"


def git(repo, *args):
    env = dict(os.environ, GIT_NO_REPLACE_OBJECTS="1", GIT_NO_LAZY_FETCH="1", GIT_TERMINAL_PROMPT="0")
    env.pop("GIT_EXTERNAL_DIFF", None)
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
        result = subprocess.run(["git", "--no-pager", "-c", "color.ui=false", *args], cwd=repo,
                                env=env, stdout=output, stderr=errors, timeout=120, check=False)
        if output.tell() > MAX_BYTES or errors.tell() > MAX_BYTES:
            raise GuardError("Git差分が上限を超えました。対象を分けて監査してください。")
        if result.returncode:
            raise GuardError("Gitによる取得・比較に失敗しました。SHA、取得元、オブジェクトの不足を確認してください。")
        output.seek(0)
        return output.read()


def parse_names(raw):
    fields = raw.decode("utf-8", "surrogateescape").split("\0")
    if fields[-1] != "" or (len(fields) - 1) % 2:
        raise GuardError("Gitの変更ファイル一覧を完全に読めません。")
    return [{"status": fields[i], "path": fields[i + 1]} for i in range(0, len(fields) - 1, 2)]


def diff_signals(patch):
    signals = {"added_urls": set(), "removed_urls": set(), "added_permissions": set(), "removed_permissions": set()}
    for line in patch.decode("utf-8", "replace").splitlines():
        if line.startswith(("+++", "---")) or not line.startswith(("+", "-")):
            continue
        prefix = "added" if line[0] == "+" else "removed"
        signals[prefix + "_urls"].update(URL.findall(line[1:]))
        signals[prefix + "_permissions"].update(PERMISSION.findall(line[1:]))
    return {name: sorted(values) for name, values in signals.items()}


def collect_audit(repo, base, candidate, config, fetch_from=None):
    if not SHA.fullmatch(base) or not SHA.fullmatch(candidate):
        raise GuardError("比較元と候補は40桁のSHAで固定してください。ブランチ名は受け付けません。")
    if fetch_from not in (None, "upstream", "origin"):
        raise GuardError("取得元はupstreamまたはoriginに限定します。")
    if fetch_from:
        remote = config["repository"] if fetch_from == "upstream" else config["maintained_repository"]
        for revision in dict.fromkeys((base, candidate)):
            git(repo, "fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head",
                "https://github.com/" + remote + ".git", revision)
    for revision in (base, candidate):
        if git(repo, "rev-parse", "--verify", revision + "^{commit}").decode().strip() != revision:
            raise GuardError("指定したコミットを確認できません。")
    common = ["--no-ext-diff", "--no-textconv", "--no-renames", base, candidate, "--"]
    files = parse_names(git(repo, "diff", "--name-status", "-z", *common))
    patch = git(repo, "diff", "--unified=0", *common)
    return {
        "kind": "candidate-audit-inventory", "base": base, "candidate": candidate,
        "candidate_tree": git(repo, "rev-parse", candidate + "^{tree}").decode().strip(),
        "checked_at": datetime.now(timezone.utc).isoformat(), "changed_files": len(files),
        "file_list_complete": True, "files": files, "categories": categories([f["path"] for f in files]),
        **diff_signals(patch),
        "security_verdict": "未監査。差分の棚卸しだけを実施。動的な通信先・難読化・実行時挙動は別途確認が必要。",
    }, patch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / ".github/upstream.json", help="管理者が確認した設定")
    commands = parser.add_subparsers(dest="command", required=True)
    watch = commands.add_parser("watch", help="上流の更新をGitHub APIで確認")
    watch.add_argument("--publish-issue", action="store_true", help="変更があった場合だけ監査待ちIssueを更新")
    watch.add_argument("--output-dir", type=Path, required=True)
    audit = commands.add_parser("audit", help="チェックアウトせずに固定SHA間の差分を整理")
    audit.add_argument("--base", required=True)
    audit.add_argument("--candidate", required=True)
    audit.add_argument("--repo-dir", type=Path, default=ROOT)
    audit.add_argument("--fetch-from", choices=("upstream", "origin"), help="隔離環境でのみ使用。設定されたGitHub公開リポジトリから取得")
    audit.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        if args.command == "watch":
            client = GitHub(os.environ.get("GITHUB_TOKEN", ""))
            report = collect_watch(client, config)
            markdown = watch_markdown(report)
            action = publish_issue(client, config, report, os.environ) if args.publish_issue else "dry-run"
            report["issue_action"] = action
        else:
            report, patch = collect_audit(args.repo_dir, args.base, args.candidate, config, args.fetch_from)
            markdown = "# 取り込み候補の差分\n\n" + report["security_verdict"] + "\n\n"
            markdown += f"比較元: {code(args.base)}\n\n候補: {code(args.candidate)}\n\n変更ファイル: {report['changed_files']}件\n"
        args.output_dir.mkdir(parents=True, exist_ok=True)
        (args.output_dir / "report.json").write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
        (args.output_dir / "report.md").write_text(markdown, encoding="utf-8")
        if args.command == "audit":
            (args.output_dir / "changes.patch").write_bytes(patch)
        print(json.dumps({"ok": True, "kind": report["kind"], "candidate": report["candidate"],
                          "security_verdict": report["security_verdict"], "issue_action": report.get("issue_action")}, ensure_ascii=False))
        return 0
    except (GuardError, KeyError, ValueError, OSError, subprocess.TimeoutExpired, urllib.error.URLError) as error:
        # 例外のHTTPヘッダーや要求本文を出力せず、秘密情報をログへ含めない。
        message = str(error) if isinstance(error, GuardError) else "処理を完了できません。設定・接続・出力先を確認してください。"
        print(json.dumps({"ok": False, "error": message}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
