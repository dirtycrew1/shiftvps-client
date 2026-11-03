---
name: ShiftVPS native interface
description: Native connection controls using the confirmed ShiftVPS website identity.
colors:
  accent: "#9DAAFF"
  selected: "#1D2240"
  button-top: "#5365D8"
  button-bottom: "#4D5FD4"
  button-text: "#FFFFFF"
  online: "#8ED9B4"
  error: "#FFB4B9"
  background: "#08080B"
  card: "#101115"
  card-raised: "#17181E"
  border: "#2B2C34"
  text: "#EDEDF0"
  muted: "#A5A7B2"
  outline: "#747682"
  update-text: "#E4E7FF"
  update-muted: "#C5CCEF"
  update-border: "#3D477B"
  error-container: "#29171E"
  error-text: "#FFDCE0"
typography:
  display:
    fontFamily: "IBM Plex Sans"
    fontSize: "36sp"
    fontWeight: 600
    lineHeight: "42sp"
    letterSpacing: "-0.8sp"
  headline:
    fontFamily: "IBM Plex Sans"
    fontSize: "28sp"
    fontWeight: 600
    lineHeight: "34sp"
    letterSpacing: "-0.5sp"
  connection-state:
    fontFamily: "IBM Plex Sans"
    fontSize: "24sp"
    fontWeight: 600
    lineHeight: "30sp"
    letterSpacing: "-0.3sp"
  title:
    fontFamily: "IBM Plex Sans"
    fontSize: "22sp"
    fontWeight: 600
    lineHeight: "28sp"
  server-name:
    fontFamily: "IBM Plex Sans"
    fontSize: "17sp"
    fontWeight: 600
    lineHeight: "24sp"
  banner-title:
    fontFamily: "IBM Plex Sans"
    fontSize: "15sp"
    fontWeight: 600
    lineHeight: "22sp"
  body-large:
    fontFamily: "IBM Plex Sans"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
  body:
    fontFamily: "IBM Plex Sans"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "21sp"
  body-small:
    fontFamily: "IBM Plex Sans"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "18sp"
  button-label:
    fontFamily: "IBM Plex Sans"
    fontSize: "16sp"
    fontWeight: 600
    lineHeight: "22sp"
  label:
    fontFamily: "IBM Plex Sans"
    fontSize: "13sp"
    fontWeight: 500
    lineHeight: "18sp"
  label-small:
    fontFamily: "IBM Plex Sans"
    fontSize: "11sp"
    fontWeight: 500
    lineHeight: "16sp"
rounded:
  button: "12dp"
  row: "16dp"
  card: "20dp"
  flag: "3dp"
spacing:
  compact: "8dp"
  row-gap: "10dp"
  small: "12dp"
  row-inset: "14dp"
  medium: "16dp"
  page: "20dp"
  section: "24dp"
  large: "32dp"
components:
  button-primary:
    backgroundColor: "{colors.button-top}"
    textColor: "{colors.button-text}"
    typography: "{typography.button-label}"
    rounded: "{rounded.button}"
    padding: "14dp 20dp"
    width: "100%"
  button-secondary:
    backgroundColor: "{colors.card-raised}"
    textColor: "{colors.button-text}"
    typography: "{typography.button-label}"
    rounded: "{rounded.button}"
    padding: "14dp 20dp"
    width: "100%"
  button-text:
    textColor: "{colors.accent}"
    typography: "{typography.label}"
    padding: "0dp 8dp"
  connection-card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    rounded: "{rounded.card}"
    padding: "{spacing.page}"
    width: "100%"
  server-row-selected:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.text}"
    typography: "{typography.server-name}"
    rounded: "{rounded.row}"
    padding: "12dp 14dp"
    width: "100%"
  update-banner:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.update-text}"
    typography: "{typography.banner-title}"
    rounded: "{rounded.row}"
    padding: "12dp 16dp"
    width: "100%"
---

# Design System: ShiftVPS native interface

## Overview

**Creative North Star: "ShiftVPS website identity"**

Operate mode. The native Android, Windows and macOS interface follows the user's explicit request to match the ShiftVPS website: near-black ground, locally bundled IBM Plex Sans, quiet dots, bordered dark surfaces and a blue vertical-gradient main action. These confirmed choices supersede the first prototype's generic typography and flat-button restriction.

The interface keeps the connection task compact and legible. Brand detail lives in the shared palette, type and component treatment. Cards remain quiet; the primary action carries the strongest color. The values below describe the existing Compose implementation rather than a new visual direction.

**Key Characteristics:**

- Website identity carried into native controls.
- Explicit connection states and a single prominent action.
- Country names remain readable beside decorative native flags.
- Honest example data and bounded verification claims.

## Colors

The palette uses a near-black neutral foundation, pale readable text and a restrained blue accent. Frontmatter contains the normative values extracted from ShiftVpsTheme.kt and the shared components. Sidecar tonal ramps are generated display metadata, not additional runtime palette tokens.

### Primary

Accent identifies interactive text and native radio selection. Selected fills the chosen row and update banner. Button-top and button-bottom are the two endpoints of the vertical main-action gradient; button-text supplies its white label. The top endpoint is darker than the website's button to keep normal white text above 4.5:1 across the gradient.

### Status

Online accompanies the written connected state. Error, error-container and error-text carry recoverable error messages. Update-text, update-muted and update-border support the compact update banner without competing with the connection action.

### Neutral

Background is the page field. Card and card-raised distinguish quiet containers and icon wells. Border divides or encloses them. Text is the principal reading color; muted supports account details, timestamps and secondary labels. Outline belongs to the native Material color scheme.

**The Website Identity Rule.** Preserve the confirmed website palette, bundled Plex typography, quiet dots and gradient action when extending this interface.

**The State Has Words Rule.** Pair status color and selection tint with explicit text and native control semantics.

## Typography

IBM Plex Sans Regular, Medium, SemiBold and Bold are bundled locally, including Cyrillic, from a pinned official IBM revision. Sources, hashes and OFL licensing remain in [FONT-PROVENANCE.md](FONT-PROVENANCE.md). The confirmation code alone uses system monospace and wider tracking to aid character comparison.

The frontmatter records the implemented type ramp: display for sign-in, headline for the confirmation code, connection-state for status, title for brand and section headings, server-name for rows, banner-title for updates, body roles for supporting copy and label roles for actions and measurements. No display fallback is substituted in the native build.

Native lengths in frontmatter use dp and sp. The sidecar's HTML/CSS specimens translate these numerically to CSS pixels for the documentation panel; they are not native layout or font-scale tests. Native Button, RadioButton and TextButton keep Material interaction, focus and permission behavior.

## Layout

The content column is centered, bounded to 560 dp, and inset by the page spacing. The default desktop window is 460 × 760 dp. The header is at least 84 dp high and shows the text-only SHIFTVPS wordmark, current account and logout action. The signed-in path places connection state and its full-width action above the server list; sign-in centers a compact panel with one action in the available scrolling area.

Both content paths use LazyColumn. Status-bar and navigation-bar insets are applied. Inline errors and updates participate in that flow. Main actions are at least 56 dp high, other actions at least 48 dp, and server rows at least 76 dp. Long account names ellipsize to one line; server names allow two lines. There is no fixed footer covering the connection control.

**Review evidence, 2026-11-02:** the six CPU-rendered Compose fixtures produced at 21:04 cover 390 × 844 phone login, disconnected, connected and available-update states; a 460 × 760 desktop list; and the nine supported country flags plus unknown fallback. The independent visual review found no material defects within that scope. At those captured sizes, there is no visible overflow and the update banner leaves Connect fully visible.

Every fixture visibly says “ПРЕДПРОСМОТР · пример данных” and constructs no account controller, core, network client or installer. These are shared-layout previews, not captures from Android, Windows or either macOS architecture. Real-device connection behavior, large font scales, OS insets and keyboard behavior, screen readers and performance remain unverified by these fixtures. Verification must not establish a VPN connection on the development PC.

## Elevation & Depth

Depth comes mainly from tonal surfaces and thin borders. The dotted ground is drawn natively with a 22 dp interval, 0.65 dp radius and a quiet fade down the screen. The enabled primary action alone uses a native Compose shadow at 10 dp elevation, transparent ambient color and a blue spot color (0x663E4FBD). There is no invented CSS shadow token for that native renderer.

**The Flat Card Rule.** Cards use tonal layering and borders; the enabled primary action alone receives the soft native elevation shadow.

## Shapes

Connection and sign-in panels use the card radius; grouped server lists round only the outer first and last row corners using the row radius. Buttons use their smaller radius. Sign-in uses the text wordmark without an invented symbol. Flag vectors keep their 4:3 composition, slight flag rounding and a subtle light border. Power and update symbols are native geometry or vector artwork, not text glyph substitutes.

## Components

### Buttons and connection card

The primary Button uses the recorded vertical blue gradient, full width and the button-label role. Its frontmatter background points to the top endpoint; the complete gradient belongs to the sidecar specimen. The connected stop action uses the raised dark surface and border. Loading and stopping are explicit states; installation blocks a new connection while preserving an active connection's stop action. A power mark, written status and selected country form the card's leading group.

### Server list and country flags

The controller supplies logical server choices and their order. Refresh and latency checks preserve usable cached choices. Unknown latency is a dash; failure is an unavailable state. Selection uses a native radio marker, a written selected label and tint together. While connected, the list remains visible and changing servers requires disconnecting first.

Flags are native vectors from pinned MIT-licensed flag-icons artwork, with source, conversion details and provenance in [art/flags/README.md](art/flags/README.md). Only authoritative node country metadata selects NL, LT, LV, IL, SE, FI, DE, EE, KZ or GE. Labels and hostnames are not parsed for country. Unknown or unsupported metadata uses the neutral globe. Country names remain text and the flag has no separate accessibility announcement.

The user requested removal of the caption “Сайты .ru — напрямую” only. It is absent from the UI; the actual centrally managed .ru DIRECT policy is retained unchanged.

### Inline updates and errors

The update banner represents available, downloading with real percentage, permission required, disconnect required, installing and failed-with-retry states. It has an update vector, written title, optional version and a compact action or progress indicator. The renderer receives state and callbacks; it does not install, download or connect by itself. Errors, updates and connection-state changes use polite live regions.

### Account sign-in

Sign-in shows one primary action and a short browser hint. Browser approval keeps the comparison code and a cancel action. A failed sign-in replaces that panel's content with the actual error and one retry action; it does not leave a stale code or a second dismiss action. Failure to open the browser cancels the pending authorization so retry can start normally. Browser SSO, PKCE and code comparison remain required.

### Native shell

The desktop tray retains opening ShiftVPS, stopping an active connection and quitting. Required Android VPN permission and authentication dialogs remain. The simple client has no protocol editor, advanced settings, external import surface or unmanaged shortcut entry points.

Application icons use the outlined `SHIFT` / `VPS` text wordmark and a compact `S` at 32 pixels and below, derived from the bundled IBM Plex Bold face. Platform launchers, the desktop window/tray and Android system icons share this family. Sources, masks, licensing and generated container checks are documented in [art/ICONS.md](art/ICONS.md).

## Do's and Don'ts

### Do

- Do reuse the recorded palette, Plex type roles and native Material interaction behavior.
- Do keep country names beside decorative vectors selected only by authoritative country metadata.
- Do retain the neutral globe for unknown or unsupported countries.
- Do keep errors and updates inline and connection actions reachable through scrolling.
- Do label illustrative data and state the limits of CPU-rendered previews.

### Don't

- Don't restore the removed .ru routing caption; its removal does not change the centrally managed DIRECT policy.
- Don't infer a country from a server label or hostname, or depend on flag emoji support.
- Don't invent latency, availability, signal strength or performance graphics.
- Don't add protocol editors, unmanaged imports, animated halos or glass treatments to this simple client.
- Don't treat a raster fixture as proof of real-device connectivity, large-font behavior or OS accessibility.
