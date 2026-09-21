# Release notes

These files preserve the user-facing notes published on GitHub:

- [v1.1.3 — Upload directly to the queue](v1.1.3.md)
- [v1.1.2 — Explicit printer-filter confirmation](v1.1.2.md)
- [v1.1.1 — GMU building names and addresses](v1.1.1.md)
- [v1.1.0 — Print settings, preview zoom, and clearer workflows](v1.1.0.md)
- [v1.0.1 — Saved cost centers and queue polish](v1.0.1.md)
- [v1.0.0 — Initial release](v1.0.0.md)

For a new release, write `vMAJOR.MINOR.PATCH.md` and supply its contents as the Release workflow's required `notes` input. Start with the main benefit, then describe changes in terms of actions and screens the user recognizes. Include upgrade instructions or remaining limitations when they affect the changes. Keep the commit comparison as a supporting link, and only describe features present in the published version. Check the remote tags when comparing releases.

The workflow appends a generated changelog, so omit the final comparison link from the input when publishing a new version. The archived files above include that link because they contain the complete published notes. For an existing release, `gh release edit vMAJOR.MINOR.PATCH --notes-file docs/releases/vMAJOR.MINOR.PATCH.md` updates the description without rebuilding the APK.
