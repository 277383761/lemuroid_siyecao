package com.swordfish.lemuroid.lib.library

import java.util.Locale

/**
 * [folderAliases] lists the platform folder names used by the "天马G" (Pegasus) libraries,
 * e.g. `FC-HD` for NES. Archives like `.zip` cannot be told apart by extension, so the
 * folder name is the only hint about which system they belong to.
 */
enum class SystemID(val dbname: String, private val folderAliases: List<String> = emptyList()) {
    NES("nes", listOf("fc", "famicom", "neshd")),
    SNES("snes", listOf("sfc", "superfamicom")),
    GENESIS("md", listOf("genesis", "megadrive", "sega5")),
    GB("gb", listOf("gameboy")),
    GBC("gbc", listOf("gameboycolor")),
    GBA("gba"),
    N64("n64", listOf("n64hd")),
    SMS("sms", listOf("mastersystem", "sega8")),
    PSP("psp"),
    NDS("nds"),
    GG("gg", listOf("gamegear")),
    ATARI2600("atari2600", listOf("a2600")),
    PSX("psx", listOf("ps", "ps1", "playstation", "playstation1")),
    FBNEO("fbneo", listOf("fba", "finalburn", "arcade")),
    MAME2003PLUS("mame2003plus", listOf("mame", "mame2003")),
    PC_ENGINE("pce", listOf("pcengine", "pcecd", "tg16", "turbografx")),
    LYNX("lynx"),
    ATARI7800("atari7800", listOf("a7800")),
    SEGACD("scd", listOf("segacd", "megacd")),
    NGP("ngp", listOf("neogeopocket")),
    NGC("ngc", listOf("gamecube", "gc")),
    WS("ws", listOf("wonderswan")),
    WSC("wsc", listOf("wonderswancolor")),
    DOS("dos"),
    NINTENDO_3DS("3ds"),
    ;

    /** Whether [folderName] names this system ("FC-HD", "SFC", "snes", "fbneo 更新", ...). */
    fun matchesFolderName(folderName: String): Boolean {
        val tokens = folderName
            .lowercase(Locale.getDefault())
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return (listOf(dbname) + folderAliases).any { tokens.contains(it) }
    }
}
