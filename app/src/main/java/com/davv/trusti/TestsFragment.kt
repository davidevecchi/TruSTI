package com.davv.trusti

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.davv.trusti.ui.TruSTITheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.davv.trusti.model.TestResult
import com.davv.trusti.model.TestsRecord
import com.davv.trusti.utils.TestsStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TestsFragment : Fragment() {

    private var records by mutableStateOf<List<TestsRecord>>(emptyList())

    private val addRecordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* records reloaded via onResume */ }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            TruSTITheme {
                TestsScreen(
                    records = records,
                    onAddRecord = {
                        addRecordLauncher.launch(
                            Intent(requireContext(), AddRecordActivity::class.java)
                        )
                    },
                    onDelete = { id ->
                        TestsStore.delete(requireContext(), id)
                        records = TestsStore.load(requireContext())
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        records = TestsStore.load(requireContext())
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) records = TestsStore.load(requireContext())
    }
}

@Composable
private fun TestsScreen(
    records: List<TestsRecord>,
    onAddRecord: () -> Unit,
    onDelete: (String) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    var recordToDelete by remember { mutableStateOf<TestsRecord?>(null) }

    if (recordToDelete != null) {
        val record = recordToDelete!!
        val label = record.tests.firstOrNull()?.disease ?: dateFormat.format(Date(record.date))
        AlertDialog(
            onDismissRequest = { recordToDelete = null },
            title = { Text(stringResource(R.string.tests_delete_title)) },
            text = { Text(stringResource(R.string.tests_delete_message, label)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(record.id)
                    recordToDelete = null
                }) { Text(stringResource(R.string.tests_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { recordToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.tests_add)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus),
                        contentDescription = null
                    )
                },
                onClick = onAddRecord
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.tests_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.tests_empty_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            val sorted = remember(records) { records.sortedByDescending { it.date } }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.tests_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                item(key = "summary") {
                    SummaryCard(sorted)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                sorted.forEachIndexed { index, record ->
                    item(key = record.id) {
                        RecordCard(
                            record = record,
                            dateFormat = dateFormat,
                            onLongClick = { recordToDelete = record }
                        )
                        if (index < sorted.lastIndex) {
                            TimeSeparator(record.date, sorted[index + 1].date)
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun RecordCard(
    record: TestsRecord,
    dateFormat: SimpleDateFormat,
    onLongClick: () -> Unit
) {
    val grouped = record.tests
        .filter { it.result != TestResult.NOT_TESTED }
        .groupBy { it.result }
    val resultPriority = listOf(TestResult.POSITIVE, TestResult.TAKE_CARE, TestResult.VACCINATED, TestResult.NEGATIVE)
    val uniqueResults = resultPriority.filter { it in grouped }
    val testedCount = record.tests.count { it.result != TestResult.NOT_TESTED }

    var expanded by remember { mutableStateOf(false) }
    val chevronAngle by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "chevron")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        // Header — tap to expand, long-press to delete
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onLongClick = onLongClick,
                    onClick = { expanded = !expanded }
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormat.format(Date(record.date)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    uniqueResults.forEach { result ->
                        Icon(
                            painter = painterResource(result.iconRes()),
                            contentDescription = result.label(),
                            tint = result.color(),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp).rotate(chevronAngle)
                    )
                }
            }
            Text(
                text = "$testedCount test${if (testedCount != 1) "s" else ""} recorded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Expanded body
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                uniqueResults.forEach { result ->
                    val tests = grouped[result] ?: return@forEach
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Icon(
                            painter = painterResource(result.iconRes()),
                            contentDescription = null,
                            tint = result.color(),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = result.label(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        tests.forEach { test ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = result.color().copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = test.disease,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = result.color()
                                )
                            }
                        }
                    }
                }
                if (record.fileUri != null) {
                    Text(
                        text = stringResource(R.string.tests_has_attachment),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun TestResult.label() = when (this) {
    TestResult.POSITIVE   -> "Positive"
    TestResult.NEGATIVE   -> "Negative"
    TestResult.VACCINATED -> "Vaccinated"
    TestResult.TAKE_CARE  -> "Take care"
    TestResult.NOT_TESTED -> "Not tested"
}

private fun TestResult.iconRes() = when (this) {
    TestResult.POSITIVE   -> R.drawable.health_cross_24px
    TestResult.NEGATIVE   -> R.drawable.assignment_turned_in_24px
    TestResult.VACCINATED -> R.drawable.syringe_24px
    TestResult.TAKE_CARE  -> R.drawable.gpp_maybe_24px
    TestResult.NOT_TESTED -> R.drawable.indeterminate_question_box_24px
}

@Composable
private fun TestResult.color(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        TestResult.POSITIVE   -> if (dark) Color(0xFFFF8A80) else Color(0xFFE53935)
        TestResult.NEGATIVE   -> if (dark) Color(0xFF69F0AE) else Color(0xFF4CAF50)
        TestResult.VACCINATED -> if (dark) Color(0xFF82B1FF) else Color(0xFF1E88E5)
        TestResult.TAKE_CARE  -> if (dark) Color(0xFFFFE082) else Color(0xFFFFC107)
        TestResult.NOT_TESTED -> if (dark) Color(0xFFBDBDBD) else Color(0xFF9E9E9E)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(sorted: List<TestsRecord>) {
    val resultPriority = listOf(TestResult.POSITIVE, TestResult.VACCINATED, TestResult.NEGATIVE)

    val latestByDisease = remember(sorted) {
        val map = linkedMapOf<String, TestResult>()
        sorted.forEach { record ->
            record.tests
                .filter { it.result != TestResult.NOT_TESTED && it.result != TestResult.TAKE_CARE }
                .forEach { test ->
                    if (test.disease !in map) {
                        map[test.disease] = test.result
                    }
                }
        }
        map
    }

    if (latestByDisease.isEmpty()) return

    val grouped = latestByDisease.entries.groupBy { it.value }
    val orderedResults = resultPriority.filter { it in grouped }
    val diseaseCount = latestByDisease.size

    var expanded by remember { mutableStateOf(false) }
    val chevronAngle by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "summaryChevron")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        // Header — tap to expand
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Status",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    orderedResults.forEach { result ->
                        Icon(
                            painter = painterResource(result.iconRes()),
                            contentDescription = result.label(),
                            tint = result.color(),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronAngle)
                    )
                }
            }
            Text(
                text = "$diseaseCount disease${if (diseaseCount != 1) "s" else ""} tracked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Collapsible body
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                orderedResults.forEachIndexed { groupIndex, result ->
                    val diseases = grouped[result] ?: return@forEachIndexed

                    if (groupIndex > 0) Spacer(Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Icon(
                            painter = painterResource(result.iconRes()),
                            contentDescription = null,
                            tint = result.color(),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = result.label(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        diseases.forEach { entry ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = result.color().copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = entry.key,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = result.color()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeSeparator(newerMillis: Long, olderMillis: Long) {
    Row(
        modifier = Modifier
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.arrows_outward_24px),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .size(16.dp)
                .rotate(90f)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = formatTimeDelta(newerMillis, olderMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

private fun formatTimeDelta(newerMillis: Long, olderMillis: Long): String {
    val newer = Calendar.getInstance().apply { timeInMillis = newerMillis }
    val older = Calendar.getInstance().apply { timeInMillis = olderMillis }

    var years = newer.get(Calendar.YEAR) - older.get(Calendar.YEAR)
    var months = newer.get(Calendar.MONTH) - older.get(Calendar.MONTH)
    var days = newer.get(Calendar.DAY_OF_MONTH) - older.get(Calendar.DAY_OF_MONTH)

    if (days < 0) {
        months--
        val prev = (newer.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        days += prev.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    if (months < 0) {
        years--
        months += 12
    }

    val parts = mutableListOf<String>()
    if (years > 0) parts.add("${years}y")
    if (months > 0) parts.add("$months month${if (months != 1) "s" else ""}")
    parts.add("$days day${if (days != 1) "s" else ""}")
    return parts.joinToString(", ")
}
