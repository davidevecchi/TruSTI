package com.davv.trusti.utils

import android.content.Context
import com.davv.trusti.model.DiseaseTest
import com.davv.trusti.model.TestsRecord
import com.davv.trusti.model.TestResult
import org.json.JSONArray
import org.json.JSONObject

object TestsStore {

    private const val PREFS = "trusti_Tests"
    private const val KEY = "records"

    fun save(context: Context, record: TestsRecord) {
        val list = load(context).toMutableList()
        list.removeAll { it.id == record.id }
        list.add(0, record)
        persist(context, list)
    }

    fun delete(context: Context, id: String) {
        persist(context, load(context).filter { it.id != id })
    }

    fun load(context: Context): List<TestsRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { obj ->
                    val tests = mutableListOf<DiseaseTest>()
                    val testsArr = obj.optJSONArray("tests")
                    if (testsArr != null) {
                        for (j in 0 until testsArr.length()) {
                            val test = testsArr.getJSONObject(j)
                            val result = when (test.getString("result")) {
                                "POSITIVE"   -> TestResult.POSITIVE
                                "NEGATIVE"   -> TestResult.NEGATIVE
                                "VACCINATED" -> TestResult.VACCINATED
                                "TAKE_CARE"  -> TestResult.TAKE_CARE
                                else         -> TestResult.NOT_TESTED
                            }
                            tests.add(DiseaseTest(test.getString("disease"), result))
                        }
                    }
                    TestsRecord(
                        id = obj.getString("id"),
                        date = obj.getLong("date"),
                        tests = tests,
                        fileUri = obj.optString("fileUri").takeIf { it.isNotEmpty() }
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(context: Context, list: List<TestsRecord>) {
        val arr = JSONArray(list.map { r ->
            val testsArr = JSONArray(r.tests.map { test ->
                JSONObject()
                    .put("disease", test.disease)
                    .put("result", test.result.name)
            })
            JSONObject()
                .put("id", r.id)
                .put("date", r.date)
                .put("tests", testsArr)
                .put("fileUri", r.fileUri ?: "")
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
