"""Build release notes from the subjects and explanatory bodies of shipped commits."""

import argparse
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import quote


CONVENTIONAL = re.compile(r"^(?P<type>[a-z]+)(?:\([^)]*\))?(?P<breaking>!)?:\s*(?P<title>.+)$")
BREAKING = re.compile(r"^BREAKING[ -]CHANGE:\s*", re.IGNORECASE)
TRAILER = re.compile(r"^(?:[A-Za-z]+(?:-[A-Za-z]+)+|Refs|Closes|Fixes):\s", re.IGNORECASE)
VERIFICATION = re.compile(r"^(?:verified|verification|validation|tested|tests?|test plan)\b", re.IGNORECASE)
GROUPS = ("Breaking changes", "Improvements", "Fixes", "Documentation", "Maintenance")


def git(*args):
    return subprocess.run(
        ["git", *args], check=True, capture_output=True, text=True, encoding="utf-8",
    ).stdout.strip()


def commit_id(ref):
    return git("rev-parse", "--verify", "--end-of-options", ref + "^{commit}")


def paragraphs(body):
    return [block.strip() for block in re.split(r"\n\s*\n", body.strip()) if block.strip()]


def prose(block):
    # Git wraps body paragraphs; omit trailers and preserve the words as a readable paragraph.
    lines = [line.strip() for line in block.splitlines() if not TRAILER.match(line)]
    return " ".join(lines)


def split_breaking_notes(body):
    lines = body.splitlines()
    first_footer = len(lines)
    notes = []
    current = None
    for index, line in enumerate(lines):
        if BREAKING.match(line):
            first_footer = min(first_footer, index)
            if current is not None:
                notes.append("\n".join(current).strip())
            current = [BREAKING.sub("", line)]
        elif current is not None:
            if TRAILER.match(line):
                notes.append("\n".join(current).strip())
                current = None
            else:
                current.append(line)
    if current is not None:
        notes.append("\n".join(current).strip())
    return "\n".join(lines[:first_footer]), notes


def describe(subject, body):
    match = CONVENTIONAL.match(subject)
    kind = match["type"] if match else ""
    title = match["title"] if match else subject
    title = title[:1].upper() + title[1:]
    explanation_body, breaking = split_breaking_notes(body)
    blocks = paragraphs(explanation_body)
    group = (
        "Breaking changes" if breaking or (match and match["breaking"])
        else "Improvements" if kind in ("feat", "perf")
        else "Fixes" if kind in ("fix", "revert")
        else "Documentation" if kind == "docs"
        else "Maintenance"
    )
    explanation = next((
        prose(block) for block in blocks
        if not BREAKING.match(block) and not TRAILER.match(block) and not VERIFICATION.match(block)
    ), "")
    # Escape link-label punctuation; commit messages are content, never commands or link targets.
    title = re.sub(r"([\\`*_\[\]<>])", r"\\\1", title)
    return group, title, explanation, breaking


def generate(previous_tag, revision, tag, repository_url, custom_notes=""):
    repository_url = repository_url.rstrip("/")
    head = commit_id(revision)
    base = commit_id(previous_tag) if previous_tag else None
    comparison = (
        f"{repository_url}/compare/{quote(previous_tag, safe='')}...{quote(tag, safe='')}"
        if base else f"{repository_url}/commits/{quote(tag, safe='')}"
    )
    if custom_notes.strip():
        content = custom_notes.strip()
    else:
        changes = {group: [] for group in GROUPS}
        history = git("log", "--reverse", "--no-merges", "--format=%H", f"{base}..{head}" if base else head)
        for sha in history.splitlines():
            message = git("show", "-s", "--format=%s%n%b", sha)
            subject, _, body = message.partition("\n")
            group, title, explanation, breaking = describe(subject, body)
            entry = f"- [{title}]({repository_url}/commit/{sha})"
            if explanation:
                entry += f"\n\n  {explanation}"
            for detail in breaking:
                if detail:
                    entry += "\n\n  **Upgrade note:** " + detail.replace("\n", "\n  ")
            changes[group].append(entry)
        content = "\n\n".join(
            f"## {group}\n\n" + "\n\n".join(entries)
            for group, entries in changes.items() if entries
        ) or "No additional commits since the previous release."
    # A custom description may already include its own comparison link.
    if comparison not in content:
        content += f"\n\n[Full changelog]({comparison})"
    return content + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous-tag", default="")
    parser.add_argument("--revision", default="HEAD")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    notes = generate(args.previous_tag, args.revision, args.tag, args.repository_url, os.environ.get("RELEASE_NOTES", ""))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(notes, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
