"""対応ソース検証が、範囲外のパス・秘密鍵・別コミットを拒否することを確認する。"""

import io
from pathlib import Path
import tarfile
import tempfile
import unittest

import verify_release as release


class ArchiveVerificationTest(unittest.TestCase):
    def archive(self, root, name, commit='a' * 40):
        path = Path(root) / 'source.tar.gz'
        with tarfile.open(path, 'w:gz', format=tarfile.PAX_FORMAT, pax_headers={'comment': commit}) as tar:
            entry = tarfile.TarInfo(name)
            entry.size = 1
            tar.addfile(entry, io.BytesIO(b'x'))
        return path

    def test_archive_cannot_name_an_external_path(self):
        with tempfile.TemporaryDirectory() as root:
            for name in ['../../private', '/absolute', 'C:/private', 'source/..\\private']:
                with self.subTest(name=name), self.assertRaises(ValueError):
                    release.check_archive(self.archive(root, name))

    def test_signing_material_is_not_a_source_artifact(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ValueError):
                release.check_archive(self.archive(root, 'source/release.p12'))

    def test_source_must_match_the_selected_commit(self):
        with tempfile.TemporaryDirectory() as root:
            archive = self.archive(root, 'source/README.md')
            self.assertEqual(['source/README.md'], release.check_archive(archive, 'a' * 40))
            with self.assertRaises(ValueError):
                release.check_archive(archive, 'b' * 40)


if __name__ == '__main__':
    unittest.main()
