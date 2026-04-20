# UI Material3 Guardrails

This project uses Material 3 as the primary UI system. New UI work should extend that system rather than layering a separate visual language on top.

## Core Rules

- Prefer Material 3 primitives first: `TopAppBar`, `NavigationBar`, `AlertDialog`, `ListItem`, `Card`, `OutlinedTextField`, `FilledIconButton`, `FilledTonalButton`, `OutlinedButton`, and `DropdownMenuItem`.
- Treat `MaterialTheme.colorScheme`, typography, and shapes as the only default design tokens. Avoid page-local hex colors, custom shadows, and ad hoc borders unless there is a documented product reason.
- Keep action hierarchy explicit:
  - Primary actions use `FilledButton` or `FilledIconButton`.
  - Secondary actions use `FilledTonalButton` / `FilledTonalIconButton`.
  - Tertiary actions use `OutlinedButton`, `OutlinedIconButton`, or `TextButton`.
- Menus and dialogs should use standard Material 3 rows and actions. Avoid custom clickable cards inside dialogs unless the pattern is formalized as a reusable component.

## Chat-Specific Rules

- The chat composer should stay inside Material 3 input patterns. Do not add custom container shadows, bespoke send buttons, or custom text-field chrome.
- Keep composer affordances outside the text field when they are peer actions. In this workspace, audio / add-content / send should be separate Material 3 icon buttons, while the input itself uses a filled `TextField` container.
- Chat bubbles may stay product-specific, but styling must come from Material 3 tokens only. Avoid stacking extra shadows, gradients, or decorative borders onto bubbles.
- Media bubbles should size to their content instead of forcing attachments into a fixed square. Avatar rows stay top-aligned with both text and media messages.
- Chat banners such as persona state, missing-model warnings, and similar status blocks should use `Card` variants instead of one-off surfaces.

## Shared Patterns

- Reuse `AppOutlinedTextField` for role/persona/editor forms so shape, border color, and container treatment stay aligned across pages.
- Prefer Material 3 `Button` for the page's primary action, `FilledTonalButton` for secondary emphasis, and `OutlinedButton` / `TextButton` for lower-emphasis actions.
- For action trays such as session swipe actions, prefer Material 3 icon-button variants with tokenized container colors over raw `background(...copy(alpha = ...))` circles.

## Review Checklist

- Does this UI already exist as a Material 3 component?
- Does the change introduce a new visual rule that only exists on one screen?
- Are colors, radii, and elevations coming from theme tokens instead of local magic numbers?
- If the change needs a custom pattern, is it reusable enough to live in a shared component instead of a page file?