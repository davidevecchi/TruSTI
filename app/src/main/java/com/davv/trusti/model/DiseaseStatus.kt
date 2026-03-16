package com.davv.trusti.model

data class DiseaseStatus(
    val hasPositive: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)
