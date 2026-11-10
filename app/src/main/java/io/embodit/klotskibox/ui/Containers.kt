package io.embodit.klotskibox.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The box volume is the default container (manifest). */
object Containers {
    const val BOARD = "KlotskiBox"
    const val CONTROL = "KlotskiControl"
    const val CONTROL_TAG = "control"
}

/** The three pages of the control window (design panels.control: 玩 / 规矩 / 设置). */
enum class Page(val stateId: String) { PLAY("S02"), RULES("S12"), SETTINGS("S11") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var exitConfirm by mutableStateOf(false)
}
