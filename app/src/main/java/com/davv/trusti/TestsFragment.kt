package com.davv.trusti

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.davv.trusti.model.DiseaseTest
import com.davv.trusti.model.TestResult
import com.davv.trusti.model.TestsRecord
import com.davv.trusti.ui.StandardAddFab
import com.davv.trusti.ui.StandardEmptyState
import com.davv.trusti.ui.StandardPageLayout
import com.davv.trusti.ui.StandardSectionDivider
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.ui.getStatusColor
import com.davv.trusti.ui.getStatusIcon
import com.davv.trusti.ui.getStatusLabel
import com.davv.trusti.utils.TestsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TestsFragment : Fragment() {

    private var records by mutableStateOf<List<TestsRecord>>(emptyList())
    private var isRefreshing by mutableStateOf(false)

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
                    isRefreshing = isRefreshing,
                    onRefresh = { refreshRecords() },
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

    private fun refreshRecords() {
        if (isRefreshing) return
        isRefreshing = true
        lifecycleScope.launch {
            // Simulated refresh delay for UX consistency
            delay(1000)
            records = TestsStore.load(requireContext())
            isRefreshing = false
        }
    }
}

@Composable
private fun TestsScreen(
    records: List<TestsRecord>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onAddRecord: () -> Unit,
    onDelete: (String) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    var recordToDelete by remember { mutableStateOf<TestsRecord?>(null) }
    val sortedRecords = remember(records) { records.sortedByDescending { it.date } }

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

    StandardPageLayout(
        title = stringResource(R.string.tests_title),
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        floatingActionButton = {
            StandardAddFab(
                text = stringResource(R.string.tests_add),
                onClick = onAddRecord
            )
        }
    ) {
        // Always show the summary card, even when no records exist
        item(key = "summary") {
            SummaryCard(sortedRecords)
            Spacer(Modifier.height(6.dp)) // Increased space after summary card
            StandardSectionDivider()
            Spacer(Modifier.height(6.dp)) // Increased space after summary card
        }

        if (records.isEmpty()) {
            item {
                StandardEmptyState(
                    title = stringResource(R.string.tests_empty_title),
                    subtitle = stringResource(R.string.tests_empty_sub)
                )
            }
        } else {
            sortedRecords.forEachIndexed { index, record ->
                item(key = record.id) {
                    RecordCard(
                        record = record,
                        dateFormat = dateFormat,
                        onLongClick = { recordToDelete = record }
                    )
                    if (index < sortedRecords.lastIndex) {
                        TimeSeparator(record.date, sortedRecords[index + 1].date)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TestCard(
    title: String,
    subtitle: String? = null,
    badgeContent: @Composable (() -> Unit)? = null,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { mod ->
                    if (onLongClick != null) {
                        mod.combinedClickable(
                            onLongClick = onLongClick,
                            onClick = { onExpandedChange(!expanded) }
                        )
                    } else {
                        mod.clickable { onExpandedChange(!expanded) }
                    }
                }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    badgeContent?.invoke()
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
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

/**
 * Shared data class for processed test results
 */
private data class ProcessedTestResults(
    val grouped: Map<TestResult, List<DiseaseTest>>,
    val uniqueResults: List<TestResult>,
    val testedCount: Int
)

/**
 * Result priority for consistent ordering across the app
 */
private val resultPriority =
    listOf(TestResult.POSITIVE, TestResult.TAKE_CARE, TestResult.VACCINATED, TestResult.NEGATIVE)

/**
 * Process test results by filtering NOT_TESTED and grouping by result
 */
private fun processTestResults(tests: List<DiseaseTest>): ProcessedTestResults {
    val filtered = tests.filter { it.result != TestResult.NOT_TESTED }
    val grouped = filtered.groupBy { it.result }
    val uniqueResults = resultPriority.filter { it in grouped }

    return ProcessedTestResults(
        grouped = grouped,
        uniqueResults = uniqueResults,
        testedCount = filtered.size
    )
}

@Composable
private fun StatusBadge(result: TestResult, count: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(result.getStatusIcon()),
            contentDescription = result.getStatusLabel(),
            tint = result.getStatusColor(),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = result.getStatusColor(),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordDetails(
    uniqueResults: List<TestResult>,
    grouped: Map<TestResult, List<DiseaseTest>>
) {
    uniqueResults.forEachIndexed { groupIndex, result ->
        val diseases = grouped[result] ?: return@forEachIndexed

        if (groupIndex > 0) Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Icon(
                painter = painterResource(result.getStatusIcon()),
                contentDescription = null,
                tint = result.getStatusColor(),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = result.getStatusLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            diseases.sortedWith(compareBy<DiseaseTest> { getDiseaseCategoryWeight(it.disease) }.thenBy {
                getDiseaseSortWeight(
                    it.disease
                )
            }).forEach { entry ->
                DiseaseChip(entry.disease, entry.result.getStatusColor())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineDetails(
    testsByDate: Map<Long, List<DiseaseTest>>,
    dateFormat: SimpleDateFormat
) {
    val sortedDates = testsByDate.keys.sortedDescending()

    sortedDates.forEachIndexed { dateIndex, date ->
        val tests = testsByDate[date] ?: return@forEachIndexed

        if (dateIndex > 0) Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Text(
                text = dateFormat.format(Date(date)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Group tests by result status using existing resultPriority order
        val testsByResult = tests.groupBy { it.result }

        resultPriority.forEachIndexed { resultIndex, result ->
            val resultTests = testsByResult[result]?.sortedBy { getDiseaseSortWeight(it.disease) }
            if (!resultTests.isNullOrEmpty()) {
                if (resultIndex > 0) Spacer(Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    resultTests.forEach { test ->
                        DiseaseChip(test.disease, test.result.getStatusColor())
                    }
                }
            }
        }
    }
}

@Composable
private fun DiseaseChip(disease: String, color: Color) {
    Box(
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = disease,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordCard(
    record: TestsRecord,
    dateFormat: SimpleDateFormat,
    onLongClick: () -> Unit
) {
    val processed = processTestResults(record.tests)
    var expanded by remember { mutableStateOf(false) }

    TestCard(
        title = dateFormat.format(Date(record.date)),
        subtitle = "${processed.testedCount} test${if (processed.testedCount != 1) "s" else ""} recorded",
        badgeContent = {
            processed.uniqueResults.forEach { result ->
                StatusBadge(result, processed.grouped[result]?.size ?: 0)
            }
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        onLongClick = onLongClick,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        RecordDetails(processed.uniqueResults, processed.grouped)
    }
}

@Composable
private fun SummaryCard(records: List<TestsRecord>) {
    val allTests = records.flatMap { it.tests }
    val groupedByDisease = allTests.groupBy { it.disease }
    val diseaseCount = groupedByDisease.size

    // For each disease, find the most recent result
    val latestResults = groupedByDisease.mapValues { (_, tests) ->
        tests.maxBy { test ->
            records.find { it.tests.contains(test) }?.date ?: 0L
        }
    }.values.toList()

    val processed = processTestResults(latestResults)
    var expanded by remember { mutableStateOf(false) }

    // When there are no tests, show NO_TEST status
    val displayResults = if (processed.uniqueResults.isEmpty()) {
        listOf(TestResult.NOT_TESTED)
    } else {
        processed.uniqueResults
    }

    val displayGrouped = if (processed.uniqueResults.isEmpty()) {
        mapOf(TestResult.NOT_TESTED to listOf(DiseaseTest("No tests", TestResult.NOT_TESTED)))
    } else {
        processed.grouped
    }

    // Find the worst result for background color, or use NOT_TESTED if no results
    val worstResult = resultPriority.find { it in processed.grouped } ?: TestResult.NOT_TESTED

    // Group latest tests by date for timeline view
    val testsByDate = latestResults.groupBy {
        records.find { record -> record.tests.contains(it) }?.date ?: 0L
    }

    TestCard(
        title = "Current Status",
        subtitle = if (diseaseCount == 0) "No tests recorded" else "$diseaseCount disease${if (diseaseCount != 1) "s" else ""} tested total",
        badgeContent = {
            displayResults.forEach { result ->
                StatusBadge(result, displayGrouped[result]?.size ?: 0)
            }
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        onLongClick = null,
        containerColor = worstResult.let {
            it.getStatusColor().copy(alpha = 0.24f)
        }
    ) {
        TimelineDetails(testsByDate, SimpleDateFormat("dd MMM yyyy", Locale.getDefault()))
    }
}

@Composable
private fun TimeSeparator(newerMillis: Long, olderMillis: Long) {
    Row(
        modifier = Modifier
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
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

/**
 * Returns a category weight for disease grouping.
 * 1 = Blood-borne/Systemic, 2 = Bacterial STIs, 3 = Viral, 4 = Others
 */
private fun getDiseaseCategoryWeight(disease: String): Int {
    val d = disease.lowercase()
    return when {
        d == "hiv" || d == "syphilis" || d.contains("hepatitis") -> 1
        d == "gonorrhea" || d == "chlamydia" || d.contains("mycoplasma") || d == "trichomoniasis" -> 2
        d.contains("herpes") || d == "hpv" -> 3
        else -> 4
    }
}

/**
 * Returns a weight for disease sorting to group by category in a standard medical order.
 * Blood-borne/Systemic first, then Bacterial STIs, then others.
 */
private fun getDiseaseSortWeight(disease: String): Int {
    val d = disease.lowercase()
    return when {
        d == "hiv" -> 1
        d == "syphilis" -> 2
        d.contains("hepatitis") -> 3
        d == "gonorrhea" -> 10
        d == "chlamydia" -> 11
        d.contains("mycoplasma") -> 12
        d == "trichomoniasis" -> 13
        d.contains("herpes") -> 20
        d == "hpv" -> 30
        else -> 100
    }
}
