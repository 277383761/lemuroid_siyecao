package com.swordfish.lemuroid.app.shared.systems

import android.content.Context
import com.swordfish.lemuroid.lib.library.MetaSystemID

data class MetaSystemInfo(
    val metaSystem: MetaSystemID,
    val count: Int,
    val isDisabled: Boolean = false
) {
    fun getName(context: Context) = context.resources.getString(metaSystem.titleResId)
}
