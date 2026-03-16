package com.davv.trusti.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SharingPreferences(
    val shareCurrentStatus: Boolean = true,
    val shareHistory: Boolean = true,
    val shareCounter: Boolean = true,
    val shareVaccines: Boolean = true
) : Parcelable
