package fr.husi.shiftvps

import fr.husi.ktx.JSONMap
import fr.husi.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftVpsGamingRoutingTest {
    private fun options(platform: Platform): JSONMap = mutableMapOf<String, Any?>(
        "route" to mapOf("rules" to listOf(mapOf("action" to "route", "outbound" to "proxy"))),
        "dns" to mapOf("rules" to listOf(mapOf("server" to "dns-fake"))),
    ).also { enforceManagedRouting(it, platform) }

    private fun rules(options: JSONMap, section: String) =
        ((options[section] as Map<*, *>)["rules"] as List<*>).map { it as Map<*, *> }

    private fun matchesApp(rule: Map<*, *>, path: String): Boolean {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return (rule["process_name"] as? List<*>)?.contains(name) == true ||
            (rule["process_path_regex"] as? List<*>)?.any { Regex(it as String).containsMatchIn(path) } == true
    }

    @Test
    fun `competitive games and launchers use direct TCP UDP and DNS including custom libraries`() {
        val examples = mapOf(
            Platform.Windows to listOf(
                "D:\\Library\\steamapps\\common\\dota 2 beta\\game\\bin\\win64\\dota2.exe",
                "E:/SteamLibrary/steamapps/common/Counter-Strike Global Offensive/game/bin/win64/cs2.exe",
                "Z:/Custom/CS2.EXE", "Z:/Custom/dota2.exe", "Z:/Custom/steam.exe",
                "Z:/Custom/EpicGamesLauncher.exe", "D:/Riot Games/VALORANT/live/ShooterGame/Binaries/Win64/VALORANT-Win64-Shipping.exe",
                "D:/Games/r5apex.exe", "D:/Games/TslGame.exe", "D:/Games/RocketLeague.exe",
                "D:/Games/Battle.net.exe", "D:/Games/EADesktop.exe", "D:/Games/UbisoftConnect.exe",
                "D:/Games/GalaxyClient.exe", "D:/Games/steamapps/common/New Game/unknown-game.exe",
            ),
            Platform.MacOs to listOf(
                "/Applications/Steam.app/Contents/MacOS/steam_osx",
                "/Volumes/Games/steamapps/common/dota 2 beta/game/dota.sh",
                "/Applications/Epic Games Launcher.app/Contents/MacOS/EpicGamesLauncher-Mac-Shipping",
            ),
            Platform.Linux to listOf("/usr/bin/steam", "/games/steamapps/common/Counter-Strike Global Offensive/game/bin/linuxsteamrt64/cs2"),
        )
        for ((platform, paths) in examples) {
            val config = options(platform)
            for (path in paths) {
                val route = rules(config, "route").firstOrNull { matchesApp(it, path) }
                assertEquals(MANAGED_DIRECT_TAG, route?.get("outbound"), path)
                assertFalse("network" in route.orEmpty() || "port" in route.orEmpty(), path)
                assertTrue(rules(config, "dns").any { matchesApp(it, path) && it["server"] == MANAGED_DNS_TAG }, path)
            }
            assertEquals("proxy", rules(config, "route").last()["outbound"])
        }
    }

    @Test
    fun `game routing never excludes generic browsers Java or shared CDN providers`() {
        val config = options(Platform.Windows)
        for (path in listOf("C:/Chrome/chrome.exe", "C:/Java/bin/javaw.exe", "C:/Other/launcher.exe", "C:/Windows/System32/svchost.exe")) {
            assertFalse(rules(config, "route").any { matchesApp(it, path) }, path)
        }
        val suffixes = rules(config, "route").flatMap { (it["domain_suffix"] as? List<*>).orEmpty() }
        assertTrue("steampowered.com" in suffixes && "steamcontent.com" in suffixes)
        assertTrue("riotgames.com" in suffixes && "epicgames.com" in suffixes)
        assertFalse(listOf("com", "amazonaws.com", "akamai.net", "cloudfront.net", "microsoft.com", "google.com").any { it in suffixes })
    }

    @Test
    fun `gaming guards are idempotent and desktop processes are not emitted on Android`() {
        for (platform in listOf(Platform.Windows, Platform.MacOs, Platform.Linux, Platform.Android)) {
            val config = options(platform)
            val once = config.toMap()
            enforceManagedRouting(config, platform)
            assertEquals(once, config)
            if (platform == Platform.Android) assertFalse(rules(config, "route").any { "process_name" in it || "process_path_regex" in it })
        }
    }
}
