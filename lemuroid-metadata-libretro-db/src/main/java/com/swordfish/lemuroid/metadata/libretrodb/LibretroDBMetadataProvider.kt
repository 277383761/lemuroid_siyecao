package com.swordfish.lemuroid.metadata.libretrodb

import com.swordfish.lemuroid.common.kotlin.filterNullable
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.metadata.GameMetadata
import com.swordfish.lemuroid.lib.library.metadata.GameMetadataProvider
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.metadata.libretrodb.db.LibretroDBManager
import com.swordfish.lemuroid.metadata.libretrodb.db.LibretroDatabase
import com.swordfish.lemuroid.metadata.libretrodb.db.entity.LibretroRom
import java.util.Locale
import timber.log.Timber

class LibretroDBMetadataProvider(private val ovgdbManager: LibretroDBManager) :
    GameMetadataProvider {

    private val sortedSystemIds: List<String> by lazy {
        SystemID.values()
            .map { it.dbname }
            .sortedByDescending { it.length }
    }

    private companion object {
        val ARCHIVE_EXTENSIONS = setOf("zip", "7z")

        /** Extensions used to look up a database entry with the same base name. */
        val knownRomExtensions: List<String> by lazy {
            (GameSystem.all().flatMap { it.supportedExtensions + it.uniqueExtensions } + ARCHIVE_EXTENSIONS)
                .map { it.lowercase(Locale.getDefault()) }
                .distinct()
        }
    }

    override suspend fun retrieveMetadata(storageFile: StorageFile): GameMetadata? {
        val db = ovgdbManager.dbInstance

        Timber.d("Looking metadata for file: $storageFile")

        val metadata = runCatching {
            findByCRC(storageFile, db)
                ?: findBySerial(storageFile, db)
                ?: findByFilename(db, storageFile)
                ?: findByPathAndFilename(db, storageFile)
                ?: findByFilenameAnyExtension(db, storageFile)
                ?: findByApproximateName(db, storageFile)
                ?: findByUniqueExtension(storageFile)
                ?: findByKnownSystem(storageFile)
                ?: findByPathAndSupportedExtension(storageFile)
        }.getOrElse {
            Timber.e("Error in retrieving $storageFile metadata: $it... Skipping.")
            null
        }

        metadata?.let { Timber.d("Metadata retrieved for item: $it") }

        return metadata
    }

    private fun convertToGameMetadata(rom: LibretroRom): GameMetadata {
        val system = GameSystem.findById(rom.system!!)
        return GameMetadata(
            name = rom.name,
            romName = rom.romName,
            thumbnail = computeCoverUrl(system, rom.name),
            system = rom.system,
            developer = rom.developer
        )
    }

    private suspend fun findByFilename(db: LibretroDatabase, file: StorageFile): GameMetadata? {
        return db.gameDao().findByFileName(file.name)
            .filterNullable { extractGameSystem(it).scanOptions.scanByFilename }
            ?.let { convertToGameMetadata(it) }
    }

    private suspend fun findByPathAndFilename(
        db: LibretroDatabase,
        file: StorageFile
    ): GameMetadata? {
        // Primary: match by filename AND require the parent folder to contain the system name.
        // This keeps the original, precise behaviour when games are organised into per-system folders.
        val byPath = db.gameDao().findByFileName(file.name)
            .filterNullable { extractGameSystem(it).scanOptions.scanByPathAndFilename }
            .filterNullable { parentContainsSystem(file.path, extractGameSystem(it).id.dbname) }
            ?.let { convertToGameMetadata(it) }

        if (byPath != null) return byPath

        // Fallback: relax the folder-name restriction. If the ROM is found in the libretro DB by
        // filename (e.g. kof98.zip), use the system recorded in the DB even when the parent folder
        // does not contain the system name. This lets users drop arcade/zip games into /sdcard/roms.
        return db.gameDao().findByFileName(file.name)
            .filterNullable { extractGameSystem(it).scanOptions.scanByPathAndFilename }
            ?.let { convertToGameMetadata(it) }
    }

    private fun findByPathAndSupportedExtension(file: StorageFile): GameMetadata? {
        // Zipped cartridge ROMs (roms/nes/xxx.zip) are common, but "zip" is deliberately not
        // part of each system's extension list because it is not a unique extension. Any core
        // can load an archive, so inside a system-named folder an archive belongs to that system.
        val isArchive = file.extension.lowercase(Locale.getDefault()) in ARCHIVE_EXTENSIONS

        // Primary: match when the parent folder contains the system name.
        val byPath = sortedSystemIds
            .filter { parentContainsSystem(file.path, it) }
            .map { GameSystem.findById(it) }
            .filter { it.scanOptions.scanByPathAndSupportedExtensions }
            .firstOrNull { isArchive || it.supportedExtensions.contains(file.extension) }

        if (byPath != null) {
            return GameMetadata(
                name = file.extensionlessName,
                romName = file.name,
                // Even without a database hit, libretro thumbnails are named after the ROM
                // (No-Intro name), so the file name is usually a valid cover key.
                thumbnail = computeCoverUrl(byPath, file.extensionlessName),
                system = byPath.id.dbname,
                developer = null
            )
        }

        // Fallback: relax the folder-name restriction. If the folder does not contain a system
        // name, fall back to the first system whose supportedExtensions contain the file extension.
        // Ambiguous extensions (e.g. .iso shared by PSX/PSP/SegaCD) keep their original priority by
        // sortedSystemIds (longest dbname first) so the most specific system wins.
        // Archives are skipped here: without a system folder there is no way to tell which
        // system a .zip belongs to.
        if (isArchive) return null

        val byExtension = sortedSystemIds
            .map { GameSystem.findById(it) }
            .filter { it.scanOptions.scanByPathAndSupportedExtensions }
            .firstOrNull { it.supportedExtensions.contains(file.extension) }

        return byExtension?.let {
            GameMetadata(
                name = file.extensionlessName,
                romName = file.name,
                thumbnail = computeCoverUrl(it, file.extensionlessName),
                system = it.id.dbname,
                developer = null
            )
        }
    }

    private fun parentContainsSystem(parent: String?, dbname: String): Boolean {
        val lowerParent = parent?.lowercase(Locale.getDefault()) ?: return false
        if (lowerParent.contains(dbname)) return true

        // "天马G" (Pegasus) libraries use platform folder names such as "FC-HD" instead of
        // "nes". Without this, zipped ROMs in those folders have no way to resolve a system.
        val systemID = SystemID.values().firstOrNull { it.dbname == dbname } ?: return false
        return lowerParent
            .split('/', '\\', ':')
            .any { segment -> systemID.matchesFolderName(segment) }
    }

    /** Match a file against the database ignoring the extension, i.e. "Game.zip" is matched to
     *  the database entry "Game.nes". Zipped ROMs are named after the inner file, so an exact
     *  name lookup never hits them and they would end up without title, system and cover. */
    private suspend fun findByFilenameAnyExtension(
        db: LibretroDatabase,
        file: StorageFile
    ): GameMetadata? {
        val baseName = file.extensionlessName
        if (baseName.isBlank()) return null

        // Only ROM-like files are allowed here, otherwise covers (xxx.jpg) would be matched
        // against "xxx.nes" and inserted as duplicate games.
        val extension = file.extension.lowercase(Locale.getDefault())
        if (extension !in knownRomExtensions) return null

        val matches = db.gameDao().findByFileNames(knownRomExtensions.map { "$baseName.$it" })
        if (matches.isEmpty()) return null

        val isArchive = extension in ARCHIVE_EXTENSIONS
        val match = matches.firstOrNull {
            parentContainsSystem(file.path, extractGameSystem(it).id.dbname)
        } ?: if (isArchive) {
            matches.firstOrNull()
        } else {
            matches.firstOrNull { extractGameSystem(it).supportedExtensions.contains(file.extension) }
        }

        return match?.let { convertToGameMetadata(it) }
    }

    /** Match a file whose name has no region/version tags, e.g. "Super Mario Bros. 3.nes",
     *  against the database entry "Super Mario Bros. 3 (USA).nes". The database (and the
     *  libretro thumbnail server) always use the No-Intro name, so without this the game gets
     *  no cover at all. Uses a range scan on the indexed romName column. */
    private suspend fun findByApproximateName(
        db: LibretroDatabase,
        file: StorageFile
    ): GameMetadata? {
        val base = file.extensionlessName.trim()
        if (base.isEmpty()) return null

        val extension = file.extension.lowercase(Locale.getDefault())
        if (extension !in knownRomExtensions) return null

        val prefixUpper = base.dropLast(1) + ((base.last().code + 1).toChar())
        val candidates = db.gameDao().findByRomNamePrefix(base, prefixUpper).filter { rom ->
            val romName = rom.romName ?: return@filter false
            if (!romName.startsWith(base) || rom.system == null) return@filter false

            // Only accept a different file when the remainder is a tag or the extension
            // ("Tetris (USA).nes"), never another game ("Tetris 2 (USA).nes").
            val suffix = romName.substring(base.length)
            suffix.startsWith(" (") || suffix.startsWith(".")
        }

        if (candidates.isEmpty()) return null

        val folderSystems = sortedSystemIds
            .filter { parentContainsSystem(file.path, it) }
            .toSet()

        fun systemMatchesExtension(rom: LibretroRom): Boolean {
            val system = rom.system ?: return false
            return runCatching {
                GameSystem.findById(system).supportedExtensions.contains(extension)
            }.getOrDefault(false)
        }

        fun regionRank(rom: LibretroRom): Int {
            val suffix = rom.romName?.substring(base.length).orEmpty()
            val tag = suffix.removePrefix(" (").substringBefore(")")
            return when {
                tag == "USA" -> 0
                tag.startsWith("USA") -> 1
                tag == "Europe" -> 2
                tag == "World" -> 3
                tag == "Japan" -> 4
                else -> 5
            }
        }

        val best = candidates.minWithOrNull(
            compareBy<LibretroRom> { rom ->
                val system = rom.system
                when {
                    system != null && system in folderSystems -> 0
                    extension in ARCHIVE_EXTENSIONS -> 1
                    systemMatchesExtension(rom) -> 1
                    else -> 2
                }
            }.thenBy { regionRank(it) }
                .thenBy { it.romName?.length ?: Int.MAX_VALUE }
        )

        return best?.let { convertToGameMetadata(it) }
    }

    private suspend fun findByCRC(file: StorageFile, db: LibretroDatabase): GameMetadata? {
        if (file.crc == null || file.crc == "0") return null
        return file.crc?.let { crc32 -> db.gameDao().findByCRC(crc32) }
            ?.let { convertToGameMetadata(it) }
    }

    private suspend fun findBySerial(file: StorageFile, db: LibretroDatabase): GameMetadata? {
        if (file.serial == null) return null
        return db.gameDao().findBySerial(file.serial!!)
            ?.let { convertToGameMetadata(it) }
    }

    private fun findByKnownSystem(file: StorageFile): GameMetadata? {
        if (file.systemID == null) return null

        return GameMetadata(
            name = file.extensionlessName,
            romName = file.name,
            thumbnail = computeCoverUrl(GameSystem.findById(file.systemID!!.dbname), file.extensionlessName),
            system = file.systemID!!.dbname,
            developer = null,
        )
    }

    private fun findByUniqueExtension(file: StorageFile): GameMetadata? {
        val system = GameSystem.findByUniqueFileExtension(file.extension)

        if (system?.scanOptions?.scanByUniqueExtension == false) {
            return null
        }

        val result = system?.let {
            GameMetadata(
                name = file.extensionlessName,
                romName = file.name,
                // Libretro thumbnails are keyed by the ROM name, so we can still build a cover
                // URL when the file is not present in the database.
                thumbnail = computeCoverUrl(it, file.extensionlessName),
                system = it.id.dbname,
                developer = null
            )
        }

        return result
    }

    private fun extractGameSystem(rom: LibretroRom): GameSystem {
        return GameSystem.findById(rom.system!!)
    }

    private fun computeCoverUrl(system: GameSystem, name: String?): String? {
        var systemName = system.libretroFullName

        // Specific mame version don't have any thumbnails in Libretro database
        if (system.id == SystemID.MAME2003PLUS) {
            systemName = "MAME"
        }

        if (name == null) {
            return null
        }

        val imageType = "Named_Boxarts"

        val thumbGameName = name.replace("&", "_")

        return "http://thumbnails.libretro.com/$systemName/$imageType/$thumbGameName.png"
    }
}
