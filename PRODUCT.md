# ShiftVPS client

## Confirmed product brief

- Native application for Android, Windows and macOS, branded ShiftVPS.
- The audience is existing ShiftVPS subscribers, not users configuring a general-purpose proxy client.
- Sign in using the existing ShiftVPS account in the system browser; the application receives its own device session.
- After sign-in, retrieve that account's managed server list automatically.
- Check actual connection latency on entry and show available, faster servers first.
- The main actions are selecting a server and connecting or disconnecting.
- Keep the current server fixed while connected. Changing servers requires disconnecting first.
- Refresh subscriptions and apply validated settings managed by ShiftVPS; do not expose protocol, DNS or routing editors.
- Domains ending in `.ru`, including their subdomains, connect directly. Other traffic follows the selected server.
- Collect bounded technical diagnostics for support; never collect browsing history, traffic contents or raw credentials.
- Use the proven upstream platform and networking components, with a separate application interface.

## Boundaries

This client work must not switch the development computer's VPN, DNS, proxy or routes. The production website server `46.182.25.146` is excluded from the VPN topology. Shared staff test credentials must never be bundled into public clients. Existing subscriptions and billing remain authoritative.

## Primary surface

Operate mode. One compact, phone-first screen, also suitable as a desktop window. Sign-in, loading, browser approval, empty server list, failed refresh, latency checks, connecting, connected, disconnecting and disconnected states must be explicit. A healthy HTTP response or a simulated screen does not prove real device VPN connectivity.
