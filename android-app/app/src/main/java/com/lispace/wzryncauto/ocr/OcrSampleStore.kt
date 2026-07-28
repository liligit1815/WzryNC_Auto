package com.lispace.wzryncauto.ocr

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class StoredOcrSample(
    val id: String,
    val imageFile: File,
    val metadataFile: File,
    val sampleCount: Int,
)

class OcrSampleStore(private val rootDirectory: File) {
    fun saveMaturity(
        screenshotPng: ByteArray,
        observation: OcrObservation,
        capturedAt: LocalDateTime = LocalDateTime.now(),
    ): StoredOcrSample {
        require(screenshotPng.isNotEmpty())
        val directory = File(rootDirectory, "maturity").apply {
            check(exists() || mkdirs()) { "Cannot create OCR sample directory" }
        }
        val id = "${capturedAt.format(ID_TIME_FORMAT)}-${UUID.randomUUID().toString().take(8)}"
        val image = File(directory, "$id.png")
        val metadata = File(directory, "$id.json")
        publishAtomically(image, screenshotPng)
        try {
            publishAtomically(
                metadata,
                metadataJson(id, observation, capturedAt).toString(2).toByteArray(),
            )
        } catch (error: Throwable) {
            image.delete()
            throw error
        }
        return StoredOcrSample(
            id = id,
            imageFile = image,
            metadataFile = metadata,
            sampleCount = countMaturitySamples(),
        )
    }

    fun countMaturitySamples(): Int =
        File(rootDirectory, "maturity")
            .listFiles { file -> file.extension == "json" }
            ?.size
            ?: 0

    private fun metadataJson(
        id: String,
        observation: OcrObservation,
        capturedAt: LocalDateTime,
    ) = JSONObject().apply {
        put("schema_version", 1)
        put("id", id)
        put("captured_at", capturedAt.toString())
        put("raw_text", observation.rawText)
        put("source_width", observation.sourceWidth)
        put("source_height", observation.sourceHeight)
        put("roi_width", observation.roiWidth)
        put("roi_height", observation.roiHeight)
        when (val reading = observation.reading) {
            is MaturityReading.Time -> {
                put("result_type", "time")
                put("hour", reading.hour)
                put("minute", reading.minute)
                put("tomorrow_hint", reading.tomorrowHint)
            }
            is MaturityReading.Mature -> put("result_type", "mature")
            is MaturityReading.Unrecognized -> {
                put("result_type", "unrecognized")
                put("failure_reason", reading.reason)
            }
        }
        put("review_status", "pending")
        put("expected_text", JSONObject.NULL)
    }

    private fun publishAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val ID_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    }
}
