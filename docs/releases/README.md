# Release notes

These files preserve the user-facing notes published on GitHub:

- [v1.1.3 — Upload directly to the queue](v1.1.3.md)
- [v1.1.2 — Explicit printer-filter confirmation](v1.1.2.md)
- [v1.1.1 — GMU building names and addresses](v1.1.1.md)
- [v1.1.0 — Print settings, preview zoom, and clearer workflows](v1.1.0.md)
- [v1.0.1 — Saved cost centers and queue polish](v1.0.1.md)
- [v1.0.0 — Initial release](v1.0.0.md)

For a new release, enter the version and leave `notes` blank. The workflow reads non-merge commits since the previous version tag through the checked-out commit being built. Conventional Commit types group changes into improvements, fixes, documentation, and maintenance. Each entry includes its title, the first explanatory body paragraph, and a link to the commit. Breaking changes are placed first, with any `BREAKING CHANGE` upgrade notes retained. Verification paragraphs and author trailers are omitted.

Generation uses the existing commit messages; it does not invent benefits or infer unrecorded behavior from code. Write commit subjects and bodies that explain the change and why it matters. Commits without a body still appear by title, and a documentation-only release is described as documentation. A full comparison link is included at the end.

The optional `notes` input replaces the generated summaries with custom Markdown. You can prepare that text in `vMAJOR.MINOR.PATCH.md`, but neither a file nor manual notes are required. An existing comparison link is preserved rather than duplicated. For an existing release, `gh release edit vMAJOR.MINOR.PATCH --notes-file docs/releases/vMAJOR.MINOR.PATCH.md` updates the description without rebuilding the APK.

To preview a release locally, use the previous published tag and the version you plan to release:

```sh
python scripts/generate_release_notes.py --previous-tag v1.1.3 --tag v1.1.4 --repository-url https://github.com/ahnafnafee/mason-print --output build/release-notes.md
```
