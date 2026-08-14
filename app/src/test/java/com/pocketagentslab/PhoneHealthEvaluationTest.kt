package com.pocketagentslab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PhoneHealthEvaluationTest(
    private val name: String,
    private val freePercent: Double,
    private val temperatureC: Double,
    private val expectedWarnings: Set<String>,
) {
    @Test
    fun deterministicDiagnosisMatchesThresholds() {
        val total = 100_000L
        val diagnosis = evaluatePhoneHealth(
            rawDevice = deviceJson(),
            rawBattery = batteryJson(temperatureC),
            rawStorage = storageJson(total, (total * freePercent / 100.0).toLong()),
        )
        val actualWarnings = diagnosis.getJSONArray("warnings").let { warnings ->
            (0 until warnings.length()).map(warnings::getString).toSet()
        }

        assertEquals(name, expectedWarnings, actualWarnings)
        assertEquals(name, if (expectedWarnings.isEmpty()) "okay" else "warning", diagnosis.getString("status"))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = listOf(
            scenario("healthy_middle", 50.0, 30.0),
            scenario("healthy_warm", 25.0, 39.9),
            scenario("storage_exact_boundary_ok", 10.0, 30.0),
            scenario("temperature_exact_boundary_ok", 50.0, 40.0),
            scenario("storage_just_below", 9.9, 30.0, "low_storage"),
            scenario("storage_very_low", 1.0, 25.0, "low_storage"),
            scenario("storage_empty", 0.0, 25.0, "low_storage"),
            scenario("battery_just_above", 50.0, 40.1, "hot_battery"),
            scenario("battery_hot", 50.0, 45.0, "hot_battery"),
            scenario("battery_very_hot", 50.0, 55.0, "hot_battery"),
            scenario("both_just_over", 9.9, 40.1, "low_storage", "hot_battery"),
            scenario("both_severe", 1.0, 55.0, "low_storage", "hot_battery"),
            scenario("ample_storage_cool", 90.0, 20.0),
            scenario("almost_full_but_ok", 10.1, 39.9),
            scenario("full_free_storage", 100.0, 35.0),
        )

        private fun scenario(
            name: String,
            freePercent: Double,
            temperatureC: Double,
            vararg warnings: String,
        ): Array<Any> = arrayOf(name, freePercent, temperatureC, warnings.toSet())

        private fun deviceJson(): String = JSONObject()
            .put("model", "Test Phone")
            .put("androidVersion", "13")
            .put("cpuAbi", "arm64-v8a")
            .toString()

        private fun batteryJson(temperatureC: Double): String = JSONObject()
            .put("levelPercent", 50.0)
            .put("temperatureC", temperatureC)
            .put("isCharging", false)
            .toString()

        private fun storageJson(total: Long, available: Long): String = JSONObject()
            .put("totalBytes", total)
            .put("availableBytes", available)
            .put("usedBytes", total - available)
            .toString()
    }
}
