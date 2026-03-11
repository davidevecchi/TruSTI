package com.davv.trusti.model

enum class TestResult { NOT_TESTED, NEGATIVE, POSITIVE, VACCINATED, TAKE_CARE }

data class DiseaseTest(
    val disease: String,
    val result: TestResult
)

data class TestsRecord(
    val id: String,
    val date: Long,          // System.currentTimeMillis()
    val tests: List<DiseaseTest>,
    val fileUri: String? = null  // content URI of attached photo/PDF
)
