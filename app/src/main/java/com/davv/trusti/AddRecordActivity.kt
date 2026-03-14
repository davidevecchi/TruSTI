package com.davv.trusti

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davv.trusti.model.DiseaseTest
import com.davv.trusti.model.TestResult
import com.davv.trusti.model.TestsRecord
import com.davv.trusti.ui.DiseaseTestResult
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.ui.TsukimiRounded
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.utils.TestsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AddRecordActivity : ComponentActivity() {

    private val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
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
            attachedFileName.value = uri.lastPathSegment ?: "Medical_Report.pdf"
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
                        P2PMessenger.get(this@AddRecordActivity).pushMyStatusToAll()
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

    var testResults by remember { mutableStateOf(diseases.associateWith { TestResult.NOT_TESTED }) }
    var otherDisease by remember { mutableStateOf("") }
    var otherTestResult by remember { mutableStateOf(TestResult.NOT_TESTED) }

    if (showDatePicker) {
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    selectedDate = millis
                    onDateChange(millis)
                }
                showDatePicker = false
            }) { Text("OK") }
        }, dismissButton = {
            TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
        }) {
            DatePicker(state = datePickerState)
        }
    }

    fun buildTests(): List<DiseaseTest>? {
        val tests = mutableListOf<DiseaseTest>()
        testResults.forEach { (disease, state) ->
            if (state != TestResult.NOT_TESTED) {
                tests.add(DiseaseTest(disease, state))
            }
        }
        if (otherDisease.isNotBlank() && otherTestResult != TestResult.NOT_TESTED) {
            tests.add(DiseaseTest(otherDisease.trim(), otherTestResult))
        }
        return tests.takeIf { it.isNotEmpty() }
    }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    "Add Medical Report",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = TsukimiRounded,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
    }, floatingActionButton = {
        ExtendedFloatingActionButton(
            text = { Text("Save Report") },
            icon = { Icon(Icons.Default.Check, contentDescription = null) },
            onClick = {
                val tests = buildTests()
                if (tests == null) {
                    Toast.makeText(context, "Select at least one test", Toast.LENGTH_SHORT).show()
                } else {
                    onSave(tests)
                }
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            item {
                SectionHeader(title = "General Information")

                ListItem(
                    headlineContent = { Text("Test Date") },
                    supportingContent = { Text(dateFormat.format(Date(selectedDate))) },
                    leadingContent = {
                        Icon(Icons.Default.DateRange, contentDescription = null)
                    },
                    trailingContent = {
                        TextButton(onClick = { showDatePicker = true }) {
                            Text("Change")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { showDatePicker = true }
                )

                ListItem(
                    headlineContent = { Text("Attachment") }, supportingContent = {
                        Text(if (fileName.isEmpty()) "No file selected" else fileName)
                    }, leadingContent = {
                        Icon(Icons.Default.Description, contentDescription = null)
                    }, trailingContent = {
                        TextButton(onClick = onPickFile) {
                            Text(if (fileName.isEmpty()) "Attach" else "Change")
                        }
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPickFile() }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            item {
                SectionHeader(title = "Test Results")
            }

            items(diseases) { disease ->
                DiseaseTestResult(
                    disease = disease,
                    state = testResults[disease] ?: TestResult.NOT_TESTED,
                    onStateChange = { newState ->
                        testResults = testResults.toMutableMap().apply { this[disease] = newState }
                    })
            }

            item {
                Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                    SectionHeader(title = "Custom Entry")

                    OutlinedTextField(
                        value = otherDisease,
                        onValueChange = { otherDisease = it },
                        label = { Text("Disease name") },
                        placeholder = { Text("e.g. Mycoplasma hominis") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) })

                    if (otherDisease.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        DiseaseTestResult(
                            disease = otherDisease.trim(),
                            state = otherTestResult,
                            onStateChange = { otherTestResult = it },
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(100.dp)) // FAB clearance
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
