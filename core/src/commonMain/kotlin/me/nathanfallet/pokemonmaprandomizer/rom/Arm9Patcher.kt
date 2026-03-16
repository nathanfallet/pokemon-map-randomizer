package me.nathanfallet.pokemonmaprandomizer.rom

/**
 * Applies byte-level patches to a decompressed ARM9 binary.
 *
 * Patch CSV format (first row is header, skipped):
 *   Offset,New
 *   2996,0
 *   …
 *
 * Offsets and values are decimal integers.
 */
object Arm9Patcher {

    /**
     * @param arm9       Decompressed ARM9 binary (will be copied; original unchanged).
     * @param csvContent Content of the patch CSV (hgss_script*.csv or bw2_script_universal.csv).
     * @return           New byte array with all patches applied.
     */
    fun patch(arm9: ByteArray, csvContent: String): ByteArray {
        val result = arm9.copyOf()
        for (line in csvContent.lines().drop(1)) {
            val trimmed = line.trim().trimStart('\uFEFF')
            if (trimmed.isBlank()) continue
            val parts = trimmed.split(",", "\t")
            if (parts.size < 2) continue
            val offset = parts[0].trim().toIntOrNull() ?: continue
            val value = parts[1].trim().toIntOrNull() ?: continue
            if (offset in result.indices) {
                result[offset] = value.toByte()
            }
        }
        return result
    }
}
