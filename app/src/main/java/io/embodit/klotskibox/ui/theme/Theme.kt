package io.embodit.klotskibox.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.klotskibox.domain.Language
import io.embodit.klotskibox.platform.Graph
import java.util.Locale

/** Walnut box, maple tiles, red lacquer (design assets); text roles measured on the window glass (D13). */
object KlotskiColors {
    val Primary = Color(0xFF7A2E22)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFF6DDD5)
    val Secondary = Color(0xFF8C6A3F)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    val Amber = Color(0xFFE0A040)

    // figure / cards
    val TagFace = Color(0xFFFBF6EC)
    val Walnut = Color(0xFF3E2A1C)
    val Maple = Color(0xFFE6C9A0)
    val Plain = Color(0xFFEFDCC0)
    val Lacquer = Color(0xFFC24A35)
    val Gold = Color(0xFFF3D27A)
    val Copper = Color(0xFFD9A55B)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun KlotskiTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = KlotskiColors.OnPrimary,
        labelPrimary = KlotskiColors.Ink,
        labelSecondary = KlotskiColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = KlotskiColors.Error,
        alert = KlotskiColors.Amber,
        passable = KlotskiColors.Secondary,
        interaction = KlotskiColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
