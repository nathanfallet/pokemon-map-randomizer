package me.nathanfallet.pokemonmaprandomizer

import dev.kotlinds.BlzCodec
import dev.kotlinds.NarcArchive
import dev.kotlinds.NdsRom
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

        val finalNds = nds
            .withArm9(arm9Patched)
            .withFiles(
                buildMap {
                    put(narcPath, newNarc)
                    when (game) {
                        // HGSS: replace script sequences NARC
                        Game.HG, Game.SS -> {
                            val scrSeqNarc = resBytes("scr_seq.narc")
                            if (scrSeqNarc != null) put("a/0/1/2", scrSeqNarc)
                        }
                        // BW2: patch fly flags in map header NARC + apply bw2_changes NARCs
                        Game.B2, Game.W2 -> {
                            put(
                                "a/0/1/2", patchBw2MapHeaders(
                                    nds.files["a/0/1/2"]
                                        ?: error("Map header NARC not found at a/0/1/2")
                                )
                            )
                            putAll(loadBw2Changes(nds.files))
                        }
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
        // C++ uses SEASON_LOCK_BASE_W2 = "files/Blocks_BW2_Seasons/SeasonScripts/W2SeasonLock"
        // W2 file has capital E: W2SeasonLock_English.csv; B2 uses lowercase: B2SeasonLock_english.csv
        val base = when (game) {
            Game.W2 -> "Blocks_BW2_Seasons/SeasonScripts/W2SeasonLock${langSuffix.replaceFirstChar { it.uppercase() }}"
            Game.B2 -> "Blocks_BW2_Seasons/SeasonScripts/B2SeasonLock$langSuffix"
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
        loadReplacementBins(dir).forEach { (idx, data) ->
            if (idx < eventFiles.size) eventFiles[idx] = data
        }
    }

    /** Loads all .bin files from [dir] resource directory, keyed by the file index
     *  parsed from the filename pattern `STEM_NNNNNNNN.bin` (the number after the last `_`). */
    private fun loadReplacementBins(dir: String): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        val cl = Thread.currentThread().contextClassLoader
        val indexStream = cl.getResourceAsStream("$dir/index.txt")
        val names: List<String> = if (indexStream != null) {
            indexStream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotBlank() }
        } else {
            try {
                val url = cl.getResource(dir) ?: return result
                val f = java.io.File(url.toURI())
                if (!f.isDirectory) return result
                f.listFiles()?.filter { it.name.endsWith(".bin") }?.map { it.name } ?: return result
            } catch (_: Exception) {
                return result
            }
        }
        for (name in names.sorted()) {
            if (!name.endsWith(".bin")) continue
            // Index is the digits after the last underscore: "6_00000028.bin" → 28
            val idx = name.substringBeforeLast(".").substringAfterLast("_").toIntOrNull() ?: continue
            val data = cl.getResourceAsStream("$dir/$name") ?: continue
            result[idx] = data.readBytes()
        }
        return result
    }

    // -------------------------------------------------------------------------
    // BW2-specific map header patching (fly flags) and NARC changes
    // -------------------------------------------------------------------------

    /** Sets bit 5 (0x20) of byte 0x1F in every 0x30-byte map header entry (C++ Gen5 fly-flag patch). */
    private fun patchBw2MapHeaders(narcBytes: ByteArray): ByteArray {
        val files = NarcArchive.unpack(narcBytes).toMutableList()
        for (i in files.indices) {
            val data = files[i].copyOf()
            var pos = 0x1F
            while (pos < data.size) {
                data[pos] = (data[pos].toInt() or 0x20).toByte()
                pos += 0x30
            }
            files[i] = data
        }
        return NarcArchive.pack(files)
    }

    /** Applies bw2_changes replacement bins to their respective NARCs and returns updated NARC bytes keyed by ROM path. */
    private fun loadBw2Changes(existingFiles: Map<String, ByteArray>): Map<String, ByteArray> {
        val targets = listOf(
            Triple("bw2_changes/map_functions_bw2", "a/0/5/6", "map_functions"),
            Triple("bw2_changes/tr_data_bw2", "a/0/9/1", "trainer data"),
            Triple("bw2_changes/tr_poke_bw2", "a/0/9/2", "trainer poke"),
        )
        return buildMap {
            for ((resDir, narcPath, _) in targets) {
                val bins = loadReplacementBins(resDir)
                if (bins.isEmpty()) continue
                val original = existingFiles[narcPath] ?: continue
                val files = NarcArchive.unpack(original).toMutableList()
                bins.forEach { (idx, data) -> if (idx < files.size) files[idx] = data }
                put(narcPath, NarcArchive.pack(files))
            }
        }
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
