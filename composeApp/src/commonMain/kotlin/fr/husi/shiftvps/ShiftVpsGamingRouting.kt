package fr.husi.shiftvps

import fr.husi.platform.Platform

// Dedicated gaming domains only. Shared cloud/CDN providers must never bypass as a whole.
internal val gamingDirectSuffixes = listOf(
    "steampowered.com", "steamcommunity.com", "steamcontent.com", "steamstatic.com",
    "steamserver.net", "steamgames.com", "steamusercontent.com", "valvesoftware.com",
    "dota2.com", "counter-strike.net", "playdeadlock.com",
    "epicgames.com", "epicgames.dev", "epicgamescdn.com", "fortnite.com",
    "riotgames.com", "riotcdn.net", "riotgames.net", "pvp.net", "leagueoflegends.com", "playvalorant.com",
    "battle.net", "battlenet.com", "blizzard.com", "blizzard.net", "blzstatic.com", "blz-contentstack.com",
    "ea.com", "origin.com", "origin-a.akamaihd.net", "ubisoft.com", "ubi.com", "ubisoftconnect.com",
    "gog.com", "gog-statics.com", "rockstargames.com",
    "faceit.com", "faceit-cdn.net", "escapefromtarkov.com", "battlestategames.com",
    "wargaming.net", "worldoftanks.com", "worldofwarships.com", "lesta.ru",
    "gaijin.net", "warthunder.com", "minecraft.net", "mojang.com", "piston-data.mojang.com",
    "roblox.com", "rbxcdn.com", "supercell.com", "supercell.net",
)

internal val gamingAndroidPackages = setOf(
    "com.valvesoftware.android.steam.community", "com.valvesoftware.android.steam.friendsui",
    "com.valvesoftware.steamlink", "com.epicgames.portal", "com.epicgames.fortnite",
    "com.tencent.ig", "com.pubg.imobile", "com.activision.callofduty.shooter",
    "com.riotgames.league.wildrift", "com.mobile.legends", "com.axlebolt.standoff2",
    "com.supercell.clashofclans", "com.supercell.clashroyale", "com.supercell.brawlstars",
    "com.roblox.client", "com.mojang.minecraftpe",
)

internal fun gamingProcessNames(platform: Platform): List<String> = when (platform) {
    Platform.Windows -> listOf(
        "steam.exe", "steamwebhelper.exe", "SteamService.exe", "dota2.exe", "cs2.exe", "csgo.exe",
        "EpicGamesLauncher.exe", "EpicWebHelper.exe", "FortniteClient-Win64-Shipping.exe", "RocketLeague.exe",
        "RiotClientServices.exe", "RiotClientUx.exe", "RiotClientUxRender.exe", "LeagueClient.exe",
        "LeagueClientUx.exe", "LeagueClientUxRender.exe", "League of Legends.exe", "VALORANT.exe", "VALORANT-Win64-Shipping.exe",
        "Battle.net.exe", "BlizzardBrowser.exe", "Overwatch.exe", "Wow.exe", "WowClassic.exe", "Diablo IV.exe",
        "EADesktop.exe", "EABackgroundService.exe", "EALauncher.exe", "Origin.exe", "r5apex.exe", "r5apex_dx12.exe",
        "UbisoftConnect.exe", "upc.exe", "UplayWebCore.exe", "RainbowSix.exe", "RainbowSix_Vulkan.exe", "RainbowSix_DX11.exe",
        "GalaxyClient.exe", "GalaxyClientService.exe", "GalaxyCommunication.exe",
        "TslGame.exe", "ExecPubg.exe", "cs2.exe", "deadlock.exe", "RustClient.exe", "EscapeFromTarkov.exe", "BsgLauncher.exe",
        "FACEIT.exe", "faceitclient.exe", "faceitservice.exe", "WarThunder.exe", "aces.exe", "WorldOfTanks.exe",
        "WorldOfWarships.exe", "wgc.exe", "lgc.exe", "RobloxPlayerBeta.exe", "Minecraft.Windows.exe",
    ).distinct()
    Platform.MacOs -> listOf("steam_osx", "steamwebhelper", "dota2", "EpicGamesLauncher-Mac-Shipping",
        "Battle.net", "LeagueClient", "LeagueClientUx", "League of Legends", "World of Warcraft", "GalaxyClient")
    Platform.Linux -> listOf("steam", "steamwebhelper", "dota2", "cs2", "csgo_linux64", "heroic", "lutris")
    Platform.Android -> emptyList()
}

internal fun gamingProcessPaths(platform: Platform): List<String> = when (platform) {
    Platform.Windows -> listOf(
        """(?i)(?:^|[\\/])steamapps[\\/]common[\\/]""",
        """(?i)(?:^|[\\/])(?:Epic Games|Riot Games|EA Games|Origin Games|GOG Games|Ubisoft Game Launcher[\\/]games|Rockstar Games)[\\/]""",
        // Exact executable names also cover portable/custom installations, case-insensitively.
        """(?i)(?:^|[\\/])(?:""" + gamingProcessNames(platform).joinToString("|") { Regex.escape(it) } + ")$",
    )
    Platform.MacOs -> listOf(
        """(?:^|/)steamapps/common/""",
        """(?:^|/)(?:Steam|Epic Games Launcher|Battle\.net|League of Legends|GOG Galaxy)\.app/Contents/""",
        """(?:^|/)Application Support/(?:Steam|Epic|Battle\.net|GOG\.com)/""",
    )
    Platform.Linux -> listOf("""(?:^|/)steamapps/common/""", """(?:^|/)\.local/share/(?:Steam|lutris)/""")
    Platform.Android -> emptyList()
}
