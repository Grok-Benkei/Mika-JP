#!/usr/bin/env python3
"""APK・公開元コミット・署名・対応ソースを確認し、配布用チェックサムを作る。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def run(arguments):
    result = subprocess.run([str(a) for a in arguments], cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
    if result.returncode:
        raise ValueError('検証コマンドが失敗しました: ' + str(arguments[0]) + '\n' + result.stderr[:2000])
    return result.stdout


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def write_checksums(output, files):
    # GNU sha256sumがCRをファイル名の一部として扱わないよう、WindowsでもLFで保存する。
    target = Path(output) / 'SHA256SUMS'
    target.write_text(''.join(digest(p) + '  ' + p.name + '\n' for p in files), encoding='utf-8', newline='\n')
    return target


def tools(sdk):
    suffix = '.exe' if os.name == 'nt' else ''
    candidates = sorted((Path(sdk) / 'build-tools').glob('*'), key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.name)), reverse=True)
    for candidate in candidates:
        aapt = candidate / ('aapt' + suffix)
        signer = candidate / 'lib/apksigner.jar'
        if aapt.is_file() and signer.is_file():
            return aapt, signer
    raise ValueError('Android SDK Build Toolsが見つかりません')


def version():
    text = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
    return re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1), int(re.search(r'versionCode\s*=\s*(\d+)', text).group(1))


def check_archive(path, project_commit=None):
    with tarfile.open(path, 'r:gz') as tar:
        names = []
        for member in tar.getmembers():
            parts = Path(member.name).parts
            if member.name.startswith(('/', '\\')) or '\\' in member.name or ':' in member.name or '..' in parts or member.issym() or member.islnk():
                raise ValueError('ソースアーカイブに範囲外の項目があります: ' + member.name)
            if any(part.endswith(('.jks', '.keystore', '.p12', '.pfx', '.dpapi')) for part in parts):
                raise ValueError('ソースアーカイブに秘密情報用ファイルがあります')
            names.append(member.name)
        if project_commit and tar.pax_headers.get('comment') != project_commit:
            raise ValueError('アプリのソースアーカイブと公開元コミットが一致しません')
        return names


def verify_apk(path, aapt, signer, java, commit, expected_version, expected_code, allow_unsigned):
    badging = run([aapt, 'dump', 'badging', path])
    if "name='jp.studioyebisu.mika'" not in badging.splitlines()[0]:
        raise ValueError('アプリIDがMika JPではありません')
    if f"versionName='{expected_version}'" not in badging.splitlines()[0] or f"versionCode='{expected_code}'" not in badging.splitlines()[0]:
        raise ValueError('APKの版がソースの設定と一致しません')
    if 'application-debuggable' in badging:
        raise ValueError('デバッグ可能なAPKを公開用に使用できません')
    signed = subprocess.run([str(java), '-jar', str(signer), 'verify', '--verbose', '--print-certs', str(path)], capture_output=True, text=True, encoding='utf-8')
    certificate = None
    if signed.returncode == 0:
        if re.search(r'certificate DN:.*CN=Android Debug', signed.stdout, re.I):
            raise ValueError('Androidの開発用署名を公開用として使用できません')
        certificate = re.search(r'certificate SHA-256 digest: ([a-fA-F0-9]+)', signed.stdout).group(1).lower()
    elif not allow_unsigned:
        raise ValueError('APKの公開用署名を確認できません')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        dex = [archive.read(n) for n in names if re.fullmatch(r'classes\d*\.dex', n)]
        if not any(commit[:7].encode() in data for data in dex):
            raise ValueError('APK内のビルド番号が公開元コミットと一致しません')
        if any(n.startswith('optional/') for n in names):
            raise ValueError('任意資料を標準APKへ含められません')
        if any(b'LocaleConfigTestActivity' in data for data in dex):
            raise ValueError('テスト用ActivityがAPKへ混入しています')
        abis = sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')})
    return dict(file=path.name, bytes=path.stat().st_size, sha256=digest(path), abis=abis,
                signatureVerified=certificate is not None, certificateSha256=certificate)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', action='append', required=True, type=Path)
    parser.add_argument('--sources', required=True, type=Path, help='同梱コンポーネントの対応ソース')
    parser.add_argument('--project-source', required=True, type=Path, help='git archiveで作ったアプリのソース')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--allow-unsigned', action='store_true', help='署名前の準備にだけ使用。公開可能とは判定しない')
    args = parser.parse_args()
    output = args.output.resolve()
    if any(p.resolve().parent != output for p in [*args.apk, args.sources, args.project_source]):
        parser.error('配布物はすべて--outputで指定した同じフォルダーに置いてください')
    if not args.sdk:
        parser.error('ANDROID_HOMEまたは--sdkを指定してください')
    if run(['git', 'status', '--porcelain']).strip():
        raise ValueError('未確定の変更があるため配布元を固定できません')
    commit = run(['git', 'rev-parse', 'HEAD']).strip()
    release_version, release_code = version()
    aapt, signer = tools(args.sdk)
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    if not java:
        raise ValueError('JDK 17を設定してください')
    apks = [verify_apk(p, aapt, signer, java, commit, release_version, release_code, args.allow_unsigned) for p in args.apk]
    certs = {p['certificateSha256'] for p in apks}
    if len(certs) != 1:
        raise ValueError('APK間で署名が一致しません')
    source_names = check_archive(args.sources)
    for suffix in ['/NATIVE-SOURCES.json', '/runtime/SOURCES.txt', '/project/LICENSE', '/project/NOTICE']:
        if not any(name.endswith(suffix) for name in source_names):
            raise ValueError('対応ソースの必須項目が不足しています: ' + suffix)
    check_archive(args.project_source, commit)
    args.output.mkdir(parents=True, exist_ok=True)
    result = dict(version=release_version, versionCode=release_code, applicationId='jp.studioyebisu.mika', commit=commit,
                  apks=apks, signed=all(p['signatureVerified'] for p in apks), preparationOnly=args.allow_unsigned,
                  correspondingSources=dict(file=args.sources.name, sha256=digest(args.sources)),
                  projectSource=dict(file=args.project_source.name, sha256=digest(args.project_source)))
    manifest = args.output / 'release-manifest.json'
    manifest.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    files = [*args.apk, args.sources, args.project_source, manifest]
    write_checksums(args.output, files)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
