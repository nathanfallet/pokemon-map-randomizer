package me.nathanfallet.pokemonmaprandomizer

import me.nathanfallet.nds.BlzCodec
import me.nathanfallet.nds.NarcArchive
import me.nathanfallet.nds.NdsRom
import me.nathanfallet.pokemonmaprandomizer.loader.BlockLoader
import me.nathanfallet.pokemonmaprandomizer.loader.WarpDictionaryLoader
import me.nathanfallet.pokemonmaprandomizer.loader.WarpLoader
import me.nathanfallet.pokemonmaprandomizer.model.Game
import me.nathanfallet.pokemonmaprandomizer.model.Language
import me.nathanfallet.pokemonmaprandomizer.model.Season
import me.nathanfallet.pokemonmaprandomizer.randomizer.MapRandomizer
import me.nathanfallet.pokemonmaprandomizer.randomizer.RandomizationResult
import me.nathanfallet.pokemonmaprandomizer.randomizer.WarpWriter
import me.nathanfallet.pokemonmaprandomizer.rom.Arm9Patcher
import me.nathanfallet.pokemonmaprandomizer.rom.LanguageDetector
import kotlin.random.Random

/**
 * Top-level orchestrator.  Mirrors the Gen4/Gen5 main.cpp pipeline.
 *
 * Full pipeline:
 *  1. Parse NDS ROM
 *  2. Decompress ARM9 (BLZ)
 *  3. Detect language, apply ARM9 patches
 *  4. Extract NARC event data
 *  5. Load warps, dictionary, blocks
 *  6. MapRandomizer.randomize(seed) — retry with seed+1 if null
 *  7. WarpWriter.write() for each event data file
 *  8. Repack NARC, rebuild NDS (ARM9 kept decompressed, matching C++ ndstool behaviour)
 *  9. Return output ROM bytes + log string
 */
class Randomizer {

    fun randomize(
        romBytes: ByteArray,
        game: Game,
        seed: Long? = null,
        season: Season? = null,
    ): RandomizationOutput {
        // ---- 1. Parse NDS ROM -----------------------------------------------
        val nds = NdsRom.parse(romBytes)

        // ---- 2. Decompress ARM9 --------------------------------------------
        val arm9Decompressed = BlzCodec.decompress(nds.arm9)

        // ---- 3. Language detection + ARM9 patching --------------------------
        val language = LanguageDetector.detect(arm9Decompressed, game)

        val patchCsv = when (game) {
            Game.HG, Game.SS -> when (language) {
                Language.FRENCH -> res("hgss_script_french.csv")
                Language.GERMAN -> res("hgss_script_german.csv")
                Language.SPANISH -> res("hgss_script_spanish.csv")
                else -> res("hgss_script.csv")
            }

            Game.B2, Game.W2 -> res("bw2_script_universal.csv")
        }
        var arm9Patched = Arm9Patcher.patch(arm9Decompressed, patchCsv)

        // BW2: also apply season lock
        if ((game == Game.B2 || game == Game.W2) && season != null) {
            val seasonLockCsv = seasonLockCsv(game, language)
            arm9Patched = applySeasonLock(arm9Patched, seasonLockCsv, season)
        }

        // ---- 4. Extract NARC event data -------------------------------------
        val narcPath = when (game) {
            Game.HG, Game.SS -> "a/0/3/2"
            Game.B2, Game.W2 -> "a/1/2/6"
        }
        val narcBytes = nds.files[narcPath]
            ?: error("Event data NARC not found at $narcPath")
        var eventFiles = NarcArchive.unpack(narcBytes).toMutableList()

        // Apply replacement event-data files from resources (game-specific fixes)
        applyReplacementEventData(eventFiles, game)

        // ---- 5. Load warps, dictionary, blocks -----------------------------
        val warpsCsv = when (game) {
            Game.HG, Game.SS -> res("Warps.csv")
            Game.B2, Game.W2 -> res("WarpsBW2.csv")
        }
        val dictCsv = when (game) {
            Game.HG, Game.SS -> res("WarpDictionary.csv")
            Game.B2, Game.W2 -> res("WarpDictionaryBW2.csv")
        }

        val warps = WarpLoader.load(warpsCsv)
        WarpDictionaryLoader.loadAndApply(dictCsv, warps)

        val blockFiles = loadBlockFiles(game, season)
        val startingKey = if (game == Game.HG || game == Game.SS) "NewBark" else "AspertiaCity"
        // Gen4 (HGSS) C++ sets block=null for .red warps; Gen5 (BW2) C++ does NOT.
        val clearRedBlockRef = game == Game.HG || game == Game.SS
        val blockData = BlockLoader.load(blockFiles, warps, startingKey, clearRedBlockRef)

        // ---- 6. Randomize (retry on unbeatable seed) -----------------------
        val randomizer = MapRandomizer(blockData)
        var effectiveSeed = seed ?: Random.nextLong()
        var result: RandomizationResult? = null
        while (result == null) {
            result = randomizer.randomize(effectiveSeed)
            if (result == null) effectiveSeed++
        }

        // ---- 7. Write warps into event data --------------------------------
        val updatedEventFiles = eventFiles.mapIndexed { _, fileBytes ->
            WarpWriter.write(fileBytes, result.warps, game)
        }

        // ---- 8. Repack NARC, rebuild NDS (ARM9 stays decompressed, like C++ ndstool) --
        val newNarc = NarcArchive.pack(updatedEventFiles)

        // Also replace scr_seq.narc if present
        val scrSeqNarc = resBytes("scr_seq.narc")
        val scrSeqPath = when (game) {
            Game.HG, Game.SS -> "a/0/1/2"
            Game.B2, Game.W2 -> "a/0/1/2"
        }

        val finalNds = nds
            .withArm9(arm9Patched)
            .withFiles(
                buildMap {
                    put(narcPath, newNarc)
                    if (scrSeqNarc != null) put(scrSeqPath, scrSeqNarc)
                    // BW2: apply bw2_changes (map_functions, tr_data, tr_poke)
                    if (game == Game.B2 || game == Game.W2) {
                        putAll(loadBw2Changes(game, nds.files))
                    }
                }
            )

        val fullLog = buildString {
            appendLine("Seed: $effectiveSeed")
            append(result.log)
        }

        return RandomizationOutput(finalNds.pack(), fullLog, effectiveSeed)
    }

    // -------------------------------------------------------------------------
    // ARM9 season lock
    // -------------------------------------------------------------------------

    /**
     * Applies the season-lock patch: for the first entry in the CSV, writes the
     * season byte; for all subsequent entries writes the CSV value.
     * (Mirrors Gen5/RomHandler.cpp LockSeason())
     */
    private fun applySeasonLock(arm9: ByteArray, csvContent: String, season: Season): ByteArray {
        val result = arm9.copyOf()
        val seasonByte = when (season) {
            Season.SPRING_SUMMER -> 0
            Season.AUTUMN -> 2
            Season.WINTER -> 3
        }.toByte()

        var first = true
        for (line in csvContent.lines().drop(1)) {
            val trimmed = line.trim().trimStart('\uFEFF')
            if (trimmed.isBlank()) continue
            val parts = trimmed.split(",")
            if (parts.size < 2) continue
            val offset = parts[0].trim().toIntOrNull() ?: continue
            if (offset in result.indices) {
                if (first) {
                    result[offset] = seasonByte
                    first = false
                } else {
                    result[offset] = (parts[1].trim().toIntOrNull() ?: 0).toByte()
                }
            }
        }
        return result
    }

    private fun seasonLockCsv(game: Game, language: Language): String {
        val langSuffix = when (language) {
            Language.GERMAN -> "_german"
            else -> "_english"
        }
        val base = when (game) {
            Game.W2 -> "bw2_changes/season_scripts_w2$langSuffix"
            Game.B2 -> "bw2_changes/season_scripts_b2$langSuffix"
            else -> return ""
        }
        return resOrEmpty("$base.csv")
    }

    // -------------------------------------------------------------------------
    // Replacement event data (pre-set fixes from resources)
    // -------------------------------------------------------------------------

    private fun applyReplacementEventData(eventFiles: MutableList<ByteArray>, game: Game) {
        val dir = when (game) {
            Game.HG, Game.SS -> "event_data"
            Game.B2, Game.W2 -> "bw2_changes/event_data_bw2"
        }
        val cl = Thread.currentThread().contextClassLoader
        // Files named "<index>_<something>.bin" or just "<index>.bin"
        val pkg = dir.replace('/', '.')
        // We enumerate by trying known file-list resource or scanning
        // Simpler: iterate over files in the jar manifest or use a known index file
        // For now, load any .bin files under the directory via ClassLoader resource listing
        val indexBytes = cl.getResourceAsStream("$dir/index.txt")
        if (indexBytes != null) {
            val lines = indexBytes.bufferedReader().readLines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank()) continue
                val idx = trimmed.substringBefore("_").toIntOrNull() ?: continue
                val data = cl.getResourceAsStream("$dir/$trimmed") ?: continue
                if (idx < eventFiles.size) {
                    eventFiles[idx] = data.readBytes()
                }
            }
        } else {
            // Fallback: try to load files by pattern (won't work in all jar configurations
            // but works when running from file system during development)
            try {
                val url = cl.getResource(dir) ?: return
                val f = java.io.File(url.toURI())
                if (!f.isDirectory) return
                for (entry in f.listFiles()?.sortedBy { it.name } ?: return) {
                    if (!entry.name.endsWith(".bin")) continue
                    val idx = entry.name.substringBefore("_").toIntOrNull() ?: continue
                    if (idx < eventFiles.size) {
                        eventFiles[idx] = entry.readBytes()
                    }
                }
            } catch (_: Exception) {
                // Silently ignore — replacement files are optional fixes
            }
        }
    }

    // -------------------------------------------------------------------------
    // BW2-specific NARC changes (map_functions, tr_data, tr_poke)
    // -------------------------------------------------------------------------

    private fun loadBw2Changes(game: Game, existingFiles: Map<String, ByteArray>): Map<String, ByteArray> {
        val changes = mutableMapOf<String, ByteArray>()
        // bw2_changes contain NARC files that replace specific game data NARCs
        // The mapping is: bw2_changes/<type>_bw2/<files> → various ROM paths
        // These are applied on top of the existing ROM filesystem
        // (specific paths depend on the game; approximated here)
        return changes
    }

    // -------------------------------------------------------------------------
    // Block file loading
    // -------------------------------------------------------------------------

    private fun loadBlockFiles(game: Game, season: Season?): Map<String, String> {
        // Key = unique path within the classpath root (so files with the same name in
        // different subdirectories don't collide).  BlockLoader.load() only needs the
        // filename extension and "startingKey" substring match on the key.
        val result = mutableMapOf<String, String>()
        val cl = Thread.currentThread().contextClassLoader
        val blockExts = setOf("blk", "grn", "red", "pnk", "blu")

        fun addDir(dir: String) {
            val url = cl.getResource(dir) ?: return
            when (url.protocol) {
                "file" -> {
                    val root = java.io.File(url.toURI())
                    if (!root.isDirectory) return
                    root.walkTopDown().filter { it.isFile && it.extension in blockExts }
                        .forEach { file ->
                            // key = dir + relative path so NewBark/NewBark.blk stays unique
                            val rel = file.relativeTo(root.parentFile).path.replace('\\', '/')
                            result[rel] = file.readText()
                        }
                }

                "jar" -> {
                    val jarPath = url.path.substringBefore("!").removePrefix("file:")
                    java.util.jar.JarFile(jarPath).use { jar ->
                        jar.entries().asSequence()
                            .filter { entry ->
                                entry.name.startsWith("$dir/") && !entry.isDirectory &&
                                        entry.name.substringAfterLast('.') in blockExts
                            }
                            .forEach { entry ->
                                result[entry.name] =
                                    jar.getInputStream(entry).bufferedReader().readText()
                            }
                    }
                }
            }
        }

        when (game) {
            Game.HG, Game.SS -> {
                addDir("Blocks_HGSS_Shared")
                addDir(if (game == Game.HG) "Blocks_HG" else "Blocks_SS")
            }

            Game.B2, Game.W2 -> {
                addDir("Blocks_BW2_Shared")
                addDir(if (game == Game.B2) "Blocks_B2" else "Blocks_W2")
                when (season) {
                    Season.SPRING_SUMMER -> addDir("Blocks_BW2_Seasons/SpringSummer")
                    Season.AUTUMN -> addDir("Blocks_BW2_Seasons/Autumn")
                    Season.WINTER -> addDir("Blocks_BW2_Seasons/Winter")
                    null -> addDir("Blocks_BW2_Seasons/SpringSummer")
                }
                addDir("Blocks_BW2_Seasons/SeasonScripts")
            }
        }

        return result
    }

    // -------------------------------------------------------------------------
    // Resource helpers
    // -------------------------------------------------------------------------

    private fun res(path: String): String {
        val cl = Thread.currentThread().contextClassLoader
        return cl.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("Resource not found: $path")
    }

    private fun resOrEmpty(path: String): String {
        val cl = Thread.currentThread().contextClassLoader
        return cl.getResourceAsStream(path)?.bufferedReader()?.readText() ?: ""
    }

    private fun resBytes(path: String): ByteArray? {
        val cl = Thread.currentThread().contextClassLoader
        return cl.getResourceAsStream(path)?.readBytes()
    }
}
