# Design directions — HTML references

> **Chosen: 03 Aurora.** It is the app's only theme; the other three directions and the earlier two
> designs were removed. See [AURORA.md](../AURORA.md). The references stay here as design history.

Open [`index.html`](index.html) in any browser. No build step, no server: the file is self-contained
(Google Fonts load if you are online, otherwise system serif/sans fallbacks are used).

Use the tab bar to isolate a direction, or keep **All** to compare. Each direction shows the same five
chats and the same conversation so the comparison is about structure, not content.

| Direction | Idea | Distinctiveness | Best at |
|---|---|---|---|
| 01 Paper | Messages are written lines, not bubbles | Very high | Calm, personal, "correspondence" feel |
| 02 Depth | Every row and bubble is a physical card | Low–medium | Effortless onboarding, media, settings |
| 03 Aurora | A slow colour field lives under the UI | Medium | Photos, music, a premium feel |
| 04 Grid | The UI admits it is data | Very high | Power users, sync state, downloads |

`previews/` holds rendered PNGs of the same screens, for quick review or for attaching to a discussion.

## Open questions before implementation

1. **One primary direction** — mixing all four produces a UI with no point of view.
2. **Light mode** — the mockups are dark. Paper is inherently light; Depth needs a dark twin; Aurora and Grid mostly need contrast checks.
3. **AMOLED Black** — the app's existing appearance setting. Grid and Aurora survive it best; Depth's shadows disappear on pure black.
4. **Density** — Paper trades density for calm; Grid trades comfort for precision. The chat list must handle 200+ rows.

## Porting the chosen direction to Compose

The existing token layer already has the right seams:

- `app/src/main/java/com/vector/messenger/design/DefaultTheme.kt` — `DefaultColors`, `DefaultDesign` shapes, spacing.
- `app/src/main/java/com/vector/messenger/design/DesignMaterials.kt` — surfaces, glass, borders.
- `app/src/main/java/com/vector/messenger/design/VectorMotionSpec.kt` — motion durations/easing.

A new direction should arrive as a third `InterfaceDesign` value plus its own colour set and shape
provider, so the existing two designs keep working and the appearance setting can switch between all
three.
