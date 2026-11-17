# Direct game traffic

ShiftVPS 0.1.20 routes recognized launchers, games and their dedicated service
domains directly. Direct means the underlying Internet connection, not a
direct Naive connection to a ShiftVPS server. The route has no proxy fallback.

Windows includes Steam, Dota 2, CS2, Epic/Fortnite, Riot/Valorant/League,
Battle.net, EA/Apex, Ubisoft/Rainbow Six, GOG, PUBG, Rust, Tarkov, FACEIT,
War Thunder, Wargaming/Lesta, Roblox and Minecraft. Process names cover custom
installation locations; library paths cover other executables and future games
inside Steam, Epic, Riot, EA, GOG, Ubisoft and Rockstar libraries. macOS and Linux
use platform-specific process and library paths. Matching applies to TCP and UDP.

Android discovers launchable apps marked CATEGORY_GAME or FLAG_IS_GAME locally
and combines them with known game/launcher packages. VpnService excludes them
from the tunnel. No installed-app inventory is transmitted. New installations
are detected when the user next reconnects. Apps without a game category and
not in the curated list cannot be classified automatically.

The dedicated domain list also sends game DNS to the underlying resolver.
Shared CDN providers, generic browser processes, java.exe/javaw.exe,
launcher.exe and system processes are not broadly excluded. A custom game
outside recognized libraries may need an explicit rule. This is not a promise
to identify every executable as a game. Third-party iOS clients and Happ do not
receive these native-app process rules.

Validation covers custom paths, upper-case Windows names, TCP/UDP scope,
DNS routing, unrelated traffic, idempotence and existing Russian-domain/SSH
rules. Unit tests do not measure an actual game session or prove lower latency
on every ISP. Verify a match and in-game ping/loss after updating and manually
reconnecting. Do not switch the operator PC's active VPN during development.

References:
- https://sing-box.sagernet.org/configuration/route/rule/
- https://developer.android.com/reference/android/content/pm/ApplicationInfo
- https://developer.android.com/develop/connectivity/vpn
- https://help.steampowered.com/en/faqs/view/2EA8-4D75-DA21-31EB
- https://support.riotgames.com/en-us/riot/security/configure-your-firewall/
