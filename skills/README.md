# Gemma Tavern Skills

Skills extend the agent experience with reusable instructions and optional JavaScript execution.

## Where skills live

- community-shareable examples in `skills/<skill-name>/`
- built-in packaged skills in `Android/src/app/src/main/assets/skills/<skill-name>/`

Use kebab-case for the folder name.

## Minimum structure

Text-only skill:

```text
my-skill/
└── SKILL.md
```

JavaScript-backed skill:

```text
my-skill/
├── SKILL.md
└── scripts/
    └── index.html
```

## SKILL.md format

Every skill needs frontmatter plus instructions.

```markdown
---
name: my-skill
description: One sentence that helps the model decide when to use the skill.
metadata:
  homepage: https://example.com/my-skill
---

# My Skill

## Instructions
Call the `run_js` tool with the following exact parameters:
- script name: index.html
- data: a JSON string with the fields ...
```

Optional metadata fields such as `require-secret` and `require-secret-description` are supported.

## JavaScript runtime contract

JavaScript skills run inside a webview. Your entry page must expose an async function named `window['ai_edge_gallery_get_result']`.

The function receives a JSON string and must return a JSON string containing either:

- `result`, or
- `error`.

It may also return `image` or `webview` payloads.

The function name keeps the historic `ai_edge_gallery_*` prefix for runtime compatibility.

## Installation paths in app

The agent skill UI supports:

- importing from a hosted URL,
- importing from a local file or folder,
- adding from bundled or featured examples.

## Sharing skills

- host static skill files on GitHub Pages or another static host,
- include a `metadata.homepage` URL,
- use the repository Skills discussion template when sharing with other users.

## Authoring tips

- keep trigger descriptions concrete,
- describe `run_js` payloads exactly,
- never place secrets in `SKILL.md`,
- prefer relative paths inside `scripts/` when loading local assets.
