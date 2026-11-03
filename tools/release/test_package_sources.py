"""配布ソースの範囲・検証・再現性を、ネットワークを使わずに確認する。"""

import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import package_sources as sources


class SourcePackageTest(unittest.TestCase):
    def test_output_cannot_escape_selected_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                sources.contained(directory, '../outside.txt')
            with self.assertRaises(ValueError):
                sources.contained(directory, '.')

    def test_hidden_private_files_are_not_packaged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tree = root / 'sources'
            tree.mkdir()
            (tree / '.private').write_text('private fixture', encoding='utf-8')
            with self.assertRaises(ValueError):
                sources.archive_tree(tree, root / 'bundle.tar.gz', 'sources')

    def test_archive_does_not_depend_on_local_file_timestamps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tree = root / 'sources'
            tree.mkdir()
            (tree / 'source.c').write_text('int main(void) { return 0; }\n', encoding='utf-8')
            first = sources.archive_tree(tree, root / 'first.tar.gz', 'sources')
            (tree / 'source.c').touch()
            second = sources.archive_tree(tree, root / 'second.tar.gz', 'sources')
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_hash_failure_never_promotes_a_download_to_verified_source(self):
        with tempfile.TemporaryDirectory() as directory:
            response = io.BytesIO(b'wrong download')
            response.url = 'https://example.test/source.tar.gz'
            with mock.patch.object(sources, 'native_sources', return_value=[('source.tar.gz', response.url, hashlib.sha256(b'expected').hexdigest())]), \
                    mock.patch.object(sources.urllib.request, 'urlopen', return_value=response):
                with self.assertRaises(ValueError):
                    sources.fetch_native(directory)
            self.assertFalse((Path(directory) / 'source.tar.gz').exists())


if __name__ == '__main__':
    unittest.main()
