package com.davv.trusti.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
fun DiseaseTestList(
    diseases: List<String>,
    onResultsChange: (Map<String, DiseaseTestState>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val results = remember {
        mutableStateOf(diseases.associateWith { DiseaseTestState.EMPTY })
    }

    LazyColumn(modifier = modifier) {
        items(diseases) { disease ->
            DiseaseTestResult(
                disease = disease,
                state = results.value[disease] ?: DiseaseTestState.EMPTY,
                onStateChange = { newState ->
                    results.value = results.value.toMutableMap().apply {
                        this[disease] = newState
                    }
                    onResultsChange(results.value)
                }
            )
        }
    }
}
