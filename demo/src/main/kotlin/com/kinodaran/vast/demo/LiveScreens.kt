package com.kinodaran.vast.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** How many player screens are alive, shown on the list: it must return to zero. */
object LiveScreens {
    var count by mutableIntStateOf(0)
}
