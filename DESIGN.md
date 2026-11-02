# ShiftVPS native interface

## Visual authority

Preserve the established ShiftVPS identity: dark neutral surfaces, readable native typography and one blue accent. This is a focused connection utility. The user's confirmed controls and simplicity take precedence over the upstream configuration interface.

## Surface and tokens

The main screen is a single column, up to 520 dp wide, with 20 dp horizontal margins. The default desktop window is 460 × 760 dp. The product name and current account sit above the connection state, server list and full-width connection button.

| Token | Value |
| --- | --- |
| Background | `#0E1014` |
| Surface | `#171A20` |
| Primary text | `#F2F3F6` |
| Secondary text | `#AEB4C0` |
| Accent | `#8194FF` |
| Selected row | `#242C4B` |
| Quiet divider | `#30353F` |
| Corner radius | 12 dp |
| Main action | At least 56 dp high |
| Other touch targets | At least 48 dp high |

Use native Material typography and controls with a local, fixed ShiftVPS color scheme. No glass, gradients, glows, decorative graphs, protocol editors or generic navigation rail. A selected server is marked by both a radio control and background; connection states and failures use readable text, not color alone.

## Interaction and honest data

Latency values come from actual tests. Unknown latency is a dash; a failed test is unavailable. Never display example servers or invented timings in production. The controller owns ranking and selection. Refresh keeps existing usable content while loading; connected users cannot silently change servers. Status and error messages use polite accessibility live regions. Browser authorization shows the short confirmation code and a cancel action, without asking for the account password inside the client.

The desktop tray offers opening ShiftVPS, stopping an active connection and quitting. Android's required VPN permission dialog remains intact. External profile imports, advanced notification actions and unmanaged shortcut entry points are omitted.

## Verification limits

Native compilation, screenshots and device behavior must be verified separately. Source inspection and XML validation are not substitutes for running Android, Windows and macOS builds; no VPN connection may be established on the development PC during that verification.
