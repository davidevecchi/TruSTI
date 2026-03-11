package com.davv.trusti.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import com.davv.trusti.R
import com.davv.trusti.model.TestResult

/**
 * Comprehensive status resources organized by enum for consistent UI across the app
 */
object StatusResources {
    
    data class StatusInfo(
        val color: Color,
        val icon: Int,
        val labelString: Int
    )
    
    // Unified status map with inline status definitions
    private val statusMap = mapOf<TestResult, StatusInfo>(
        TestResult.POSITIVE to StatusInfo(
            color = Color(0xFFE53935),
            icon = R.drawable.health_cross_24px,
            labelString = R.string.status_positive
        ),
        TestResult.NEGATIVE to StatusInfo(
            color = Color(0xFF4CAF50),
            icon = R.drawable.assignment_turned_in_24px,
            labelString = R.string.status_negative
        ),
        TestResult.VACCINATED to StatusInfo(
            color = Color(0xFF1E88E5),
            icon = R.drawable.syringe_24px,
            labelString = R.string.status_vaccinated
        ),
        TestResult.TAKE_CARE to StatusInfo(
            color = Color(0xFFFFC107),
            icon = R.drawable.gpp_maybe_24px,
            labelString = R.string.status_take_care
        ),
        TestResult.NOT_TESTED to StatusInfo(
            color = Color(0xFF9E9E9E),
            icon = R.drawable.indeterminate_question_box_24px,
            labelString = R.string.status_not_tested
        )
    )
    
    // Private access to the unified map
    internal fun getStatusInfo(key: TestResult): StatusInfo? = statusMap[key]
}

/**
 * Extension functions using the unified status map
 */
fun TestResult.getStatusColor(): Color =
    StatusResources.getStatusInfo(this)?.color ?: Color(0xFF9E9E9E)

fun TestResult.getStatusIcon(): Int =
    StatusResources.getStatusInfo(this)?.icon ?: R.drawable.indeterminate_question_box_24px

@Composable
fun TestResult.getStatusLabel(): String =
    stringResource(StatusResources.getStatusInfo(this)?.labelString ?: R.string.status_not_tested)
