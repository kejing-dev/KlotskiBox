package io.embodit.klotskibox.data

import android.content.Context
import android.util.Log
import io.embodit.klotskibox.domain.GameStore
import io.embodit.klotskibox.domain.Language
import io.embodit.klotskibox.domain.Layout
import io.embodit.klotskibox.domain.SaveCodec
import io.embodit.klotskibox.domain.SavedGame
import io.embodit.klotskibox.domain.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/game.txt, written atomically after every step: a temp file, a read-back check, then a rename; the previous
 * good copy stays as game.bak (Chess Stores.kt pattern).
 */
class FileGameStore(context: Context) : GameStore {
    private val dir = context.filesDir
    private val file = File(dir, "game.txt")
    private val backup = File(dir, "game.bak")

    override fun load(): SavedGame? =
        listOf(file, backup).firstNotNullOfOrNull { f -> runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }.getOrNull() }

    override fun save(game: SavedGame) {
        val text = SaveCodec.encode(game)
        runCatching {
            val tmp = File(dir, "game.tmp")
            tmp.writeText(text)
            check(SaveCodec.decode(tmp.readText()) == game) { "read-back mismatch" }
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("Klotski", "SAVE failed", it) }
    }

    override fun clear() {
        file.delete()
        backup.delete()
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so every window recomposes on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("sound", s.sound)
            .putInt("volume", s.volume)
            .putBoolean("reducedMotion", s.reducedMotion)
            .putInt("tilt", s.tilt)
            .putBoolean("firstRunDone", s.firstRunDone)
            .putBoolean("coach", s.coach)
            .putString("best", s.best.entries.joinToString(";") { "${it.key}=${it.value}" })
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            sound = prefs.getBoolean("sound", true),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            reducedMotion = prefs.getBoolean("reducedMotion", false),
            tilt = prefs.getInt("tilt", Settings.DEFAULT_TILT).takeIf { it in Layout.TILTS } ?: Settings.DEFAULT_TILT,
            firstRunDone = prefs.getBoolean("firstRunDone", false),
            coach = prefs.getBoolean("coach", true),
            best = prefs.getString("best", "").orEmpty().split(";").filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=").toInt() },
        )
    }.getOrElse { Settings() }
}
