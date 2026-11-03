#!/usr/bin/env python3
"""同梱プログラムの対応ソースを検証し、公開APKに添えるアーカイブを作る。"""

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'app/src/main/assets/runtime/packages.json'


def sha(path, algorithm='sha256'):
    digest = hashlib.new(algorithm)
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def contained(directory, relative):
    """入力の名前で出力先から外へ出ず、リンク先のファイルも取り込まない。"""
    root = Path(directory).resolve()
    relative = Path(relative)
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('出力範囲外のパスです: ' + str(relative))
    path = root
    for part in relative.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError('シンボリックリンクを含むパスは使用できません: ' + str(relative))
    path = path.resolve()
    if path == root or root not in path.parents:
        raise ValueError('出力範囲外のパスです: ' + str(relative))
    return path


def native_sources():
    for line in (ROOT / 'tools/sandbox/sources.lock').read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        name, version, url, checksum = line.split()
        if not url.startswith('https://') or not re.fullmatch('[a-f0-9]{64}', checksum):
            raise ValueError('対応ソースのURLまたはハッシュが不正です: ' + name)
        yield name + '-' + version + '.tar.gz', url, checksum


def fetch_native(directory):
    records = []
    for name, url, expected in native_sources():
        target = contained(directory, name)
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists() or sha(target) != expected:
            temporary = target.with_suffix(target.suffix + '.part')
            request = urllib.request.Request(url, headers={'User-Agent': 'Mika-JP-source-package'})
            with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as out:
                if not response.url.startswith('https://'):
                    raise ValueError('HTTPS以外への転送は使用できません')
                shutil.copyfileobj(response, out)
            if sha(temporary) != expected:
                raise ValueError('ソースのSHA-256が一致しません: ' + name)
            temporary.replace(target)
        records.append(dict(file='native/' + name, url=url, sha256=expected))
        print('ソース検証済み: ' + name, flush=True)
    return records


def verify_runtime(directory):
    marker = Path(directory) / 'PACKAGES.sha256'
    index = Path(directory) / 'SOURCES.txt'
    if not marker.exists() or marker.read_text(encoding='utf-8').strip() != sha(MANIFEST) or not index.exists():
        return False
    entries = set()
    for line in index.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        name, expected, _ = line.split('  ', 2)
        target = contained(directory, name)
        if not target.is_file() or sha(target, 'sha512') != expected:
            return False
        entries.add(name)
    packages = json.loads(MANIFEST.read_text(encoding='utf-8'))['packages']
    expected = {p['origin'] + '-' + p['version'] for p in packages if re.search(r'\b(?:L?GPL|MPL)', p['license'])}
    for origin in expected:
        recipe = origin + '/APKBUILD'
        if recipe not in entries:
            return False
        content = contained(directory, recipe).read_text(encoding='utf-8')
        checksums = re.search(r'^sha512sums="(.*?)"', content, re.S | re.M)
        if not checksums:
            return False
        for line in checksums.group(1).strip().splitlines():
            fields = line.split()
            if len(fields) != 2:
                return False
            expected_hash, filename = fields
            name = origin + '/' + filename
            if name not in entries or sha(contained(directory, name), 'sha512') != expected_hash:
                return False
    return bool(expected)


def prepare_tree(output):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    native = output / 'native'
    records = fetch_native(native)
    runtime = output / 'runtime'
    if not verify_runtime(runtime):
        subprocess.run([sys.executable, '-X', 'utf8', str(ROOT / 'tools/sandbox/runtime-sources.py'), str(runtime)],
                       cwd=ROOT, check=True)
        (runtime / 'PACKAGES.sha256').write_text(sha(MANIFEST) + '\n', encoding='utf-8')
    if not verify_runtime(runtime):
        raise ValueError('同梱実行環境の対応ソースを検証できませんでした')
    tracked = subprocess.check_output([
        'git', 'ls-files', '-z', '--', 'tools/sandbox', 'tools/release', 'licenses',
        'app/src/main/cpp/mikarun', 'app/src/main/assets/sandbox/mika',
        'app/src/main/java/com/wangzan101/mika/cap/sandbox/BundledRuntime.kt', 'docs/release.md',
    ], cwd=ROOT).decode().split('\0')
    for relative in sorted(set(tracked) | {'LICENSE', 'NOTICE', 'app/src/main/assets/runtime/packages.json'}):
        if not relative:
            continue
        source = contained(ROOT, relative)
        if source.is_symlink() or not source.is_file():
            raise ValueError('通常ファイル以外を対応ソースへ含められません: ' + relative)
        target = contained(output / 'project', relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    (output / 'NATIVE-SOURCES.json').write_text(json.dumps(records, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return output


def archive_tree(tree, archive, prefix):
    tree = Path(tree).resolve()
    archive = Path(archive)
    with archive.open('wb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w') as tar:
            for path in sorted(tree.rglob('*')):
                if path.is_symlink():
                    raise ValueError('対応ソースにシンボリックリンクを含められません')
                if not path.is_file():
                    continue
                if path.name == '.gitkeep' and path.stat().st_size == 0:
                    continue
                relative = path.relative_to(tree).as_posix()
                if any(part.startswith('.') for part in path.relative_to(tree).parts):
                    raise ValueError('対応ソースに非公開の隠しファイルを含められません: ' + relative)
                info = tar.gettarinfo(str(path), arcname=prefix + '/' + relative)
                info.uid = info.gid = 0
                info.uname = info.gname = ''
                info.mtime = 0
                info.mode = 0o755 if path.suffix in {'.sh', '.py'} else 0o644
                with path.open('rb') as stream:
                    tar.addfile(info, stream)
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path, help='ソース一式の作業・保存先')
    parser.add_argument('--version', help='配布版。省略時はダウンロードと検証だけを行う')
    args = parser.parse_args()
    if args.version and not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z._-]*', args.version):
        parser.error('バージョン名に使用できない文字があります')
    tree = prepare_tree(args.output / 'corresponding-sources')
    if args.version:
        name = 'Mika-JP-' + args.version + '-corresponding-sources'
        archive = archive_tree(tree, args.output / (name + '.tar.gz'), name)
        print(json.dumps(dict(file=str(archive), bytes=archive.stat().st_size, sha256=sha(archive)), ensure_ascii=False))


if __name__ == '__main__':
    main()
