package com.davv.trusti

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.ui.StandardPageLayout
import com.davv.trusti.utils.DebugSettings
import com.davv.trusti.utils.ProfileManager

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            TruSTITheme {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsFragmentCompanion.PREFS_NAME, Context.MODE_PRIVATE) }

    var currentTheme by remember {
        mutableStateOf(prefs.getInt(SettingsFragmentCompanion.KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
    }
    var messageLoggingEnabled by remember {
        mutableStateOf(context.getSharedPreferences("trusti_debug", Context.MODE_PRIVATE).getBoolean("log_messages", false))
    }
    var shareStatus by remember {
        mutableStateOf(ProfileManager.getShareStatus(context))
    }
    var shareCounter by remember {
        mutableStateOf(ProfileManager.getShareCounter(context))
    }
    var shareHistory by remember {
        mutableStateOf(ProfileManager.getShareHistory(context))
    }
    var shareVaccines by remember {
        mutableStateOf(ProfileManager.getShareVaccines(context))
    }

    StandardPageLayout(
        title = stringResource(R.string.settings_title)
    ) {
        item {
            ThemeCard(
                currentTheme = currentTheme,
                onThemeChange = { theme ->
                    currentTheme = theme
                    prefs.edit().putInt(SettingsFragmentCompanion.KEY_THEME, theme).apply()
                    AppCompatDelegate.setDefaultNightMode(theme)
                }
            )
        }
        item {
            DebugCard(
                messageLoggingEnabled = messageLoggingEnabled,
                onMessageLoggingChange = { enabled ->
                    messageLoggingEnabled = enabled
                    DebugSettings.setMessageLoggingEnabled(context, enabled)
                }
            )
        }
        item {
            PrivacyCard(
                shareStatus = shareStatus,
                shareCounter = shareCounter,
                shareHistory = shareHistory,
                shareVaccines = shareVaccines,
                onShareStatusChange = {
                    shareStatus = it
                    ProfileManager.setShareStatus(context, it)
                },
                onShareCounterChange = {
                    shareCounter = it
                    ProfileManager.setShareCounter(context, it)
                },
                onShareHistoryChange = {
                    shareHistory = it
                    ProfileManager.setShareHistory(context, it)
                },
                onShareVaccinesChange = {
                    shareVaccines = it
                    ProfileManager.setShareVaccines(context, it)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeCard(
    currentTheme: Int,
    onThemeChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_appearance_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            val options = listOf(
                stringResource(R.string.settings_theme_light) to AppCompatDelegate.MODE_NIGHT_NO,
                stringResource(R.string.settings_theme_dark) to AppCompatDelegate.MODE_NIGHT_YES,
                stringResource(R.string.settings_theme_system) to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEachIndexed { index, (label, mode) ->
                    SegmentedButton(
                        selected = currentTheme == mode,
                        onClick = { onThemeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugCard(
    messageLoggingEnabled: Boolean,
    onMessageLoggingChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Debug",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Log Messages (Toast)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = messageLoggingEnabled,
                    onCheckedChange = onMessageLoggingChange
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    subLabel: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(subLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PrivacyCard(
    shareStatus: Boolean,
    shareCounter: Boolean,
    shareHistory: Boolean,
    shareVaccines: Boolean,
    onShareStatusChange: (Boolean) -> Unit,
    onShareCounterChange: (Boolean) -> Unit,
    onShareHistoryChange: (Boolean) -> Unit,
    onShareVaccinesChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Privacy",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            ToggleRow(
                label = "Share status with bonds",
                subLabel = "Bonds can see if you have positive results",
                checked = shareStatus,
                onCheckedChange = onShareStatusChange
            )

            Spacer(Modifier.height(8.dp))

            ToggleRow(
                label = "Share test count",
                subLabel = "Show how many tests you've recorded",
                checked = shareCounter,
                onCheckedChange = onShareCounterChange
            )

            Spacer(Modifier.height(8.dp))

            ToggleRow(
                label = "Share test history",
                subLabel = "Show dates and diseases tested",
                checked = shareHistory,
                onCheckedChange = onShareHistoryChange
            )

            Spacer(Modifier.height(8.dp))

            ToggleRow(
                label = "Share vaccination status",
                subLabel = "Show which diseases you're vaccinated against",
                checked = shareVaccines,
                onCheckedChange = onShareVaccinesChange
            )
        }
    }
}

object SettingsFragmentCompanion {
    const val PREFS_NAME = "trusti_prefs"
    const val KEY_THEME = "theme_mode"
}
