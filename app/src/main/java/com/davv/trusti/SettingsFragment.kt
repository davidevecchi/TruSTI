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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.ui.StandardPageLayout
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
    
    var username by remember { mutableStateOf(ProfileManager.getUsername(context)) }
    var disambiguation by remember { mutableStateOf(ProfileManager.getDisambiguation(context)) }
    var currentTheme by remember { 
        mutableStateOf(prefs.getInt(SettingsFragmentCompanion.KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
    }

    StandardPageLayout(
        title = stringResource(R.string.settings_title)
    ) {
        item {
            ProfileCard(
                username = username,
                disambiguation = disambiguation,
                onUsernameChange = { username = it },
                onDisambiguationChange = { disambiguation = it },
                onSaveProfile = {
                    if (username.isNotBlank()) {
                        ProfileManager.setUsername(context, username)
                    }
                }
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            ThemeCard(
                currentTheme = currentTheme,
                onThemeChange = { theme ->
                    currentTheme = theme
                    prefs.edit().putInt(SettingsFragmentCompanion.KEY_THEME, theme).apply()
                    AppCompatDelegate.setDefaultNightMode(theme)
                }
            )
        }
    }
}

@Composable
private fun ProfileCard(
    username: String,
    disambiguation: String,
    onUsernameChange: (String) -> Unit,
    onDisambiguationChange: (String) -> Unit,
    onSaveProfile: () -> Unit
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
                text = "Profile",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // Username field
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(Modifier.height(16.dp))

            // Disambiguation section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Disambiguation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    SuggestionChip(
                        onClick = { /* Not clickable */ },
                        label = { Text(disambiguation) },
                        enabled = false,
                        colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
                            disabledLabelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                val context = LocalContext.current
                TextButton(
                    onClick = { 
                        onDisambiguationChange(ProfileManager.rollDisambiguation(context))
                    }
                ) {
                    Text(
                        text = "Roll",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onSaveProfile,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = "Save Profile",
                    style = MaterialTheme.typography.labelLarge
                )
            }
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

object SettingsFragmentCompanion {
    const val PREFS_NAME = "trusti_prefs"
    const val KEY_THEME = "theme_mode"
}
