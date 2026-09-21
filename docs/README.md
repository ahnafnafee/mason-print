# docs

Source comments in this repo cite four research documents by path:

| Cited as | What it holds |
| --- | --- |
| `docs/FINDINGS.md` | Reverse engineering of the stock Pharos Print client and its wire protocol |
| `docs/CLONE-PLAN.md` | The requirements this client was built against, clause by clause |
| `docs/PHAROS-API-FINDINGS.md` | The `/PharosAPI` endpoint table and its observed quirks |
| `docs/FUNDING-MODELS.md` | Who pays for a print job, and when |

**Those files are not in this repository.** They live in the separate research
workspace where this client was developed, alongside the decompiled vendor app
they describe. They are cited here because they are why the code makes the
choices it makes, not because they ship with it.

If you are reading a comment that points at one and you do not have the
workspace, the comment itself is meant to stand on its own. Every one of them
states the constraint before it cites the source.

## What is here

- `assets/` - the app icon, used by the README.
- `DESIGN-PROMPT.md` - the brief the redesign was produced from.
- `releases/` - version-specific release notes and guidance for future releases.
