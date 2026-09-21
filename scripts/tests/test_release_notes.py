import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generate_release_notes import describe, generate


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.original_directory = Path.cwd()
        self.workspace = tempfile.TemporaryDirectory()
        os.chdir(self.workspace.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Test Author")
        self.git("config", "user.email", "test@example.invalid")
        self.commit("feat: initial queue")
        self.git("tag", "v1.0.0")

    def tearDown(self):
        os.chdir(self.original_directory)
        self.workspace.cleanup()

    def git(self, *args):
        return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, subject, body=""):
        self.git("commit", "--quiet", "--allow-empty", "-m", subject, "-m", body)
        return self.git("rev-parse", "HEAD")

    def render(self, previous="v1.0.0", custom="", revision="HEAD"):
        return generate(previous, revision, "v1.1.0", "https://example.invalid/owner/project", custom)

    def test_only_shipped_commits_are_grouped_with_explanations(self):
        feature = self.commit("feat(printers): show building addresses", "Find printers by the building name\nand street address.")
        fix = self.commit("fix(upload): keep uploads in the queue", "The picker previously opened a separate screen. Stay in the queue.\n\nVerified 10 tests.")
        self.git("tag", "candidate")
        self.commit("docs: update screenshots", "Show the new queue cards.")
        notes = self.render(revision="candidate")
        self.assertIn("## Improvements", notes)
        self.assertIn("## Fixes", notes)
        self.assertIn("Find printers by the building name and street address.", notes)
        self.assertIn("Stay in the queue.", notes)
        self.assertIn(f"/commit/{feature}", notes)
        self.assertIn(f"/commit/{fix}", notes)
        self.assertNotIn("initial queue", notes)
        self.assertNotIn("screenshots", notes)
        self.assertNotIn("Verified 10 tests", notes)
        self.assertIn("/compare/v1.0.0...v1.1.0", notes)

    def test_documentation_and_legacy_subjects_are_not_reported_as_features(self):
        self.commit("docs: refresh screenshots", "Show the current interface.")
        self.commit("Update packaging rules")
        notes = self.render()
        self.assertIn("## Documentation", notes)
        self.assertIn("## Maintenance", notes)
        self.assertIn("Update packaging rules", notes)
        self.assertNotIn("## Improvements", notes)
        self.assertNotIn("## Fixes", notes)

    def test_breaking_changes_keep_upgrade_instructions_and_omit_trailers(self):
        self.commit("fix!: change saved server format", "Preserve explicit ports.\n\nBREAKING CHANGE: Re-enter the server address.\n  Existing sessions must sign in again.\nSigned-off-by: Test Author <test@example.invalid>")
        self.commit("feat: new release screen", "Review the jobs.\n\nBREAKING-CHANGE: Pick the printer again.")
        notes = self.render()
        self.assertTrue(notes.startswith("## Breaking changes"))
        self.assertIn("Re-enter the server address.\n    Existing sessions must sign in again.", notes)
        self.assertIn("Pick the printer again.", notes)
        self.assertNotIn("Signed-off-by", notes)
        self.assertNotIn("test@example.invalid", notes)

    def test_breaking_footer_after_other_trailers_keeps_all_upgrade_paragraphs(self):
        self.commit("fix: change saved server format", "Preserve explicit ports.\n\nRefs: #123\nReviewed-by: Test Reviewer\nBREAKING CHANGE: Re-enter the server address.\n\nThen sign in again:\n\n```text\nOpen Settings > Server\n```\nSigned-off-by: Test Author <test@example.invalid>")
        notes = self.render()
        self.assertTrue(notes.startswith("## Breaking changes"))
        self.assertIn("Preserve explicit ports.", notes)
        self.assertIn("**Upgrade note:** Re-enter the server address.\n  \n  Then sign in again:", notes)
        self.assertIn("  ```text\n  Open Settings > Server\n  ```", notes)
        self.assertNotIn("Refs:", notes)
        self.assertNotIn("Reviewed-by", notes)
        self.assertNotIn("Signed-off-by", notes)

    def test_footer_continuation_is_not_repeated_as_an_explanation(self):
        group, _, explanation, breaking = describe("feat: change settings", "Refs: #123\nBREAKING CHANGE: Reset the saved server.\n\nThen sign in again.")
        self.assertEqual(group, "Breaking changes")
        self.assertEqual(explanation, "")
        self.assertEqual(breaking, ["Reset the saved server.\n\nThen sign in again."])

    def test_first_release_and_unchanged_release_have_honest_fallbacks(self):
        first = self.render(previous="")
        self.assertIn("Initial queue", first)
        self.assertIn("/commits/v1.1.0", first)
        self.assertNotIn("/compare/", first)
        self.assertIn("No additional commits since the previous release.", self.render())

    def test_custom_notes_replace_summaries_and_do_not_duplicate_comparison(self):
        self.commit("fix: example fix", "Details of the fix.")
        custom = "A simpler queue.\n\n- Open and upload documents."
        notes = self.render(custom=custom)
        self.assertTrue(notes.startswith(custom))
        self.assertNotIn("Example fix", notes)
        self.assertEqual(self.render(custom=notes).count("/compare/"), 1)
        self.assertIn("Example fix", self.render(custom=" \n\t "))

    def test_missing_base_fails_instead_of_including_unrelated_history(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.render(previous="v9.0.0")

    def test_literal_shell_syntax_and_link_punctuation_are_content(self):
        self.commit("fix: preserve [brackets] and $(exit 7)", "Keep literal `exit 9` in the description.")
        notes = self.render()
        self.assertIn(r"Preserve \[brackets\] and $(exit 7)", notes)
        self.assertIn("Keep literal `exit 9`", notes)

    def test_explanation_can_start_with_a_label_and_skips_verification(self):
        _, _, explanation, _ = describe("fix: keep settings", "Before: the server discarded settings.\n\nVerified 12 tests.")
        self.assertEqual(explanation, "Before: the server discarded settings.")
        _, _, explanation, _ = describe("fix: keep settings", "Validation: tests passed.\n\nApply each document's own settings.")
        self.assertEqual(explanation, "Apply each document's own settings.")


if __name__ == "__main__":
    unittest.main()
