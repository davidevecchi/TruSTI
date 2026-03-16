package com.davv.trusti

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.davv.trusti.model.TestResult
import com.davv.trusti.ui.StandardPageLayout
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.utils.ProfileManager
import com.davv.trusti.utils.TestsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Get the latest POSITIVE/NEGATIVE result for each disease
 */
private fun getLatestPosNegResults(tests: List<com.davv.trusti.model.TestsRecord>): Map<String, TestResult> {
    val results = mutableMapOf<String, Pair<TestResult, Long>>()
    
    tests.forEach { record ->
        record.tests.forEach { test ->
            if (test.result == TestResult.POSITIVE || test.result == TestResult.NEGATIVE) {
                val existing = results[test.disease]
                if (existing == null || record.date > existing.second) {
                    results[test.disease] = Pair(test.result, record.date)
                }
            }
        }
    }
    
    return results.mapValues { it.value.first }
}

/**
 * Get vaccination status for diseases (especially HPV)
 */
private fun getVaccinationStatus(tests: List<com.davv.trusti.model.TestsRecord>): Map<String, Boolean> {
    val vaccinated = mutableMapOf<String, Boolean>()
    
    tests.forEach { record ->
        record.tests.forEach { test ->
            if (test.result == TestResult.VACCINATED) {
                vaccinated[test.disease] = true
            }
        }
    }
    
    return vaccinated
}

/**
 * Process timeline to show only last VACCINATED and last POS/NEG test per disease
 */
private fun processTimeline(tests: List<com.davv.trusti.model.TestsRecord>): List<com.davv.trusti.model.TestsRecord> {
    val lastVax = mutableMapOf<String, com.davv.trusti.model.TestsRecord>()
    val lastPosNeg = mutableMapOf<String, com.davv.trusti.model.TestsRecord>()
    
    tests.forEach { record ->
        record.tests.forEach { test ->
            when (test.result) {
                TestResult.VACCINATED -> {
                    val existing = lastVax[test.disease]
                    if (existing == null || record.date > existing.date) {
                        lastVax[test.disease] = record.copy(tests = listOf(test))
                    }
                }
                TestResult.POSITIVE, TestResult.NEGATIVE -> {
                    val existing = lastPosNeg[test.disease]
                    if (existing == null || record.date > existing.date) {
                        lastPosNeg[test.disease] = record.copy(tests = listOf(test))
                    }
                }
                else -> {}
            }
        }
    }
    
    return (lastVax.values + lastPosNeg.values).sortedByDescending { it.date }
}

class ProfileFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            TruSTITheme {
                ProfileScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen() {
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }

    var name by remember { mutableStateOf(ProfileManager.getUsername(context)) }
    var disambig by remember { mutableStateOf(ProfileManager.getDisambiguation(context)) }

    val tests = remember { TestsStore.load(context) }
    val latestPosNegResults = remember { getLatestPosNegResults(tests) }
    val vaccinationStatus = remember { getVaccinationStatus(tests) }
    val hasPositive = latestPosNegResults.values.any { it == TestResult.POSITIVE }
    val testCount = tests.size
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    StandardPageLayout(title = "Profile") {
        item {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Preview") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Edit") }
                )
            }
        }

        when (selectedTab) {
            0 -> item {
                Spacer(Modifier.height(16.dp))
                ProfilePreview(
                    name = name,
                    disambig = disambig,
                    shareStatus = ProfileManager.getShareStatus(context),
                    hasPositive = hasPositive,
                    shareCounter = ProfileManager.getShareCounter(context),
                    testCount = testCount,
                    shareHistory = ProfileManager.getShareHistory(context),
                    tests = tests,
                    latestPosNegResults = latestPosNegResults,
                    vaccinationStatus = vaccinationStatus,
                    dateFmt = dateFmt
                )
            }
            1 -> item {
                Spacer(Modifier.height(16.dp))
                ProfileEdit(
                    name = name,
                    disambig = disambig,
                    onNameChange = { name = it },
                    onDisambigChange = { disambig = it },
                    onSave = {
                        if (name.isNotBlank()) ProfileManager.setUsername(context, name)
                    },
                    onRollDisambig = { disambig = ProfileManager.rollDisambiguation(context) }
                )
            }
        }
    }
}

@Composable
private fun ProfilePreview(
    name: String,
    disambig: String,
    shareStatus: Boolean,
    hasPositive: Boolean,
    shareCounter: Boolean,
    testCount: Int,
    shareHistory: Boolean,
    tests: List<com.davv.trusti.model.TestsRecord>,
    latestPosNegResults: Map<String, TestResult>,
    vaccinationStatus: Map<String, Boolean>,
    dateFmt: SimpleDateFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = name.ifBlank { "Anonymous" },
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(4.dp))

            SuggestionChip(
                onClick = {},
                label = { Text(disambig) },
                enabled = false,
                colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
                    disabledLabelColor = MaterialTheme.colorScheme.onSurface
                )
            )

            Spacer(Modifier.height(16.dp))

            // Status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                when {
                    !shareStatus -> {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Status hidden", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    latestPosNegResults.isEmpty() && vaccinationStatus.isEmpty() -> {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF43A047), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("No test results", style = MaterialTheme.typography.bodySmall, color = Color(0xFF43A047))
                    }
                    else -> {
                        val statusParts = mutableListOf<String>()
                        val statusColors = mutableListOf<Color>()
                        
                        // Check for positive results
                        if (hasPositive) {
                            statusParts.add("Has positive results")
                            statusColors.add(Color(0xFFE53935))
                        }
                        
                        // Check HPV special case
                        val hpvVax = vaccinationStatus["HPV"] == true
                        val hpvResult = latestPosNegResults["HPV"]
                        if (hpvVax && hpvResult != null) {
                            val hpvStatus = when (hpvResult) {
                                TestResult.POSITIVE -> "HPV: vaccinated + positive"
                                TestResult.NEGATIVE -> "HPV: vaccinated + negative"
                                else -> null
                            }
                            hpvStatus?.let {
                                statusParts.add(it)
                                statusColors.add(if (hpvResult == TestResult.POSITIVE) Color(0xFFE53935) else Color(0xFF43A047))
                            }
                        } else if (hpvVax) {
                            statusParts.add("HPV: vaccinated")
                            statusColors.add(Color(0xFF43A047))
                        }
                        
                        // If no specific status yet, show general status
                        if (statusParts.isEmpty()) {
                            statusParts.add("No positive results")
                            statusColors.add(Color(0xFF43A047))
                        }
                        
                        // Display first status with icon
                        Icon(
                            if (statusParts.first().contains("positive")) Icons.Filled.Close else Icons.Filled.Check,
                            contentDescription = null, 
                            tint = statusColors.first(), 
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(statusParts.first(), style = MaterialTheme.typography.bodySmall, color = statusColors.first())
                    }
                }
            }

            if (shareCounter && testCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "$testCount test${if (testCount == 1) "" else "s"} on record",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (shareHistory && tests.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Test history",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                val processedTimeline = processTimeline(tests)
                processedTimeline.forEach { record ->
                    val test = record.tests.first()
                    val resultLabel = when (test.result) {
                        TestResult.POSITIVE -> "positive"
                        TestResult.NEGATIVE -> "negative"
                        TestResult.VACCINATED -> "vaccinated"
                        else -> "unknown"
                    }
                    Text(
                        text = "${dateFmt.format(Date(record.date))}  ·  ${test.disease}  ·  $resultLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileEdit(
    name: String,
    disambig: String,
    onNameChange: (String) -> Unit,
    onDisambigChange: (String) -> Unit,
    onSave: () -> Unit,
    onRollDisambig: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("Identity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Disambiguation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text(disambig) },
                        enabled = false,
                        colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
                            disabledLabelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
                TextButton(onClick = onRollDisambig) {
                    Text("Roll", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onSave, modifier = Modifier.align(Alignment.End)) {
                Text("Save", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
