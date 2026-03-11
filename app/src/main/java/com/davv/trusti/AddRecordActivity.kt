package com.davv.trusti

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davv.trusti.model.DiseaseTest
import com.davv.trusti.model.TestResult
import com.davv.trusti.model.TestsRecord
import com.davv.trusti.ui.DiseaseTestResult
import com.davv.trusti.ui.DiseaseTestState
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.utils.TestsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AddRecordActivity : ComponentActivity() {

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private var selectedDate: Long = System.currentTimeMillis()
    private var attachedFileUri: Uri? = null
    private val attachedFileName = mutableStateOf("")

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            attachedFileUri = uri
            attachedFileName.value = uri.lastPathSegment ?: "attachment"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TruSTITheme {
                AddRecordScreen(
                    onBack = { finish() },
                    onSave = { tests ->
                        val record = TestsRecord(
                            id = UUID.randomUUID().toString(),
                            date = selectedDate,
                            tests = tests,
                            fileUri = attachedFileUri?.toString()
                        )
                        TestsStore.save(this@AddRecordActivity, record)
                        finish()
                    },
                    onDateChange = { date -> selectedDate = date },
                    onPickFile = { filePicker.launch(arrayOf("image/*", "application/pdf")) },
                    dateFormat = dateFormat,
                    fileName = attachedFileName.value
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRecordScreen(
    onBack: () -> Unit,
    onSave: (List<DiseaseTest>) -> Unit,
    onDateChange: (Long) -> Unit,
    onPickFile: () -> Unit,
    dateFormat: SimpleDateFormat,
    fileName: String
) {
    val context = LocalContext.current
    val diseases = listOf(
        "HIV",
        "Gonorrhea",
        "Chlamydia",
        "Syphilis",
        "Herpes HSV-1",
        "Herpes HSV-2",
        "Hepatitis B",
        "Hepatitis C",
        "HPV",
        "Trichomoniasis",
        "Mycoplasma genitalium"
    )

    var selectedDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate)

    var testResults by remember { mutableStateOf(diseases.associateWith { DiseaseTestState.EMPTY }) }
    var otherDisease by remember { mutableStateOf("") }
    var otherTestResult by remember { mutableStateOf(DiseaseTestState.EMPTY) }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selectedDate = millis
                        onDateChange(millis)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    fun buildTests(): List<DiseaseTest>? {
        val tests = mutableListOf<DiseaseTest>()
        testResults.forEach { (disease, state) ->
            if (state != DiseaseTestState.EMPTY) {
                tests.add(DiseaseTest(disease, state.toResult()))
            }
        }
        if (otherDisease.isNotBlank() && otherTestResult != DiseaseTestState.EMPTY) {
            tests.add(DiseaseTest(otherDisease.trim(), otherTestResult.toResult()))
        }
        return tests.takeIf { it.isNotEmpty() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Add Medical Record") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Save") },
                icon = {},
                onClick = {
                    val tests = buildTests()
                    if (tests == null) {
                        Toast.makeText(context, "Select at least one test result", Toast.LENGTH_SHORT).show()
                    } else {
                        onSave(tests)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // — Date section —
            SectionLabel("Date")
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(dateFormat.format(Date(selectedDate)))
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))

            // — Test results section —
            SectionLabel("Test Results")

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(diseases) { disease ->
                    DiseaseTestResult(
                        disease = disease,
                        state = testResults[disease] ?: DiseaseTestState.EMPTY,
                        onStateChange = { newState ->
                            testResults = testResults.toMutableMap().apply { this[disease] = newState }
                        }
                    )
                }

                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value = otherDisease,
                            onValueChange = { otherDisease = it },
                            label = { Text("Other disease") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (otherDisease.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            DiseaseTestResult(
                                disease = otherDisease.trim(),
                                state = otherTestResult,
                                onStateChange = { otherTestResult = it },
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(80.dp)) } // FAB clearance
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // — Attachment section —
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPickFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (fileName.isEmpty()) "Attach File" else fileName, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

private fun DiseaseTestState.toResult() = when (this) {
    DiseaseTestState.POSITIVE   -> TestResult.POSITIVE
    DiseaseTestState.NEGATIVE   -> TestResult.NEGATIVE
    DiseaseTestState.VACCINATED -> TestResult.VACCINATED
    DiseaseTestState.TAKE_CARE  -> TestResult.TAKE_CARE
    DiseaseTestState.EMPTY      -> TestResult.NOT_TESTED
}
