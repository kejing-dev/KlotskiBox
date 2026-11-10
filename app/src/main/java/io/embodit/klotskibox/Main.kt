package io.embodit.klotskibox

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.klotskibox.ui.Containers
import io.embodit.klotskibox.ui.board.BoardScreen
import io.embodit.klotskibox.ui.control.ControlWindow
import io.embodit.klotskibox.ui.theme.KlotskiTheme

/**
 * Two surfaces in Shared Space (design-package windowModel volume_plus_control_window): the box volume (default
 * container, 0.48 x 0.56 x 0.62 m from the manifest) and the control window (Planar 560 x 640 dp) to its right.
 * Every open (first open, S10 pinch, [重开控制窗]) uses Placement.right(box) (profile D44 correction: Shared Space never
 * promises "in front of you"; the user moves the panel with the system handle).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            KlotskiTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 640.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            KlotskiTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 520.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
