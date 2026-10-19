package net.tamasoft.meditationbell

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneId

/** Saves each sitting to Health Connect as one meditation session. Reads nothing. */
@OptIn(ExperimentalMindfulnessSessionApi::class)
object Health {
    const val WRITE = "android.permission.health.WRITE_MINDFULNESS"

    /** Sittings shorter than this are taken as a mistaken start and not saved. */
    const val MIN_SECONDS = 60L

    fun available(c: Context): Boolean {
        if (HealthConnectClient.getSdkStatus(c) != HealthConnectClient.SDK_AVAILABLE) return false
        return HealthConnectClient.getOrCreate(c).features
            .getFeatureStatus(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    suspend fun allowed(c: Context): Boolean =
        available(c) && WRITE in HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions()

    /** True if saved, or too short to save. False means: try again later. */
    suspend fun save(c: Context, start: Instant, end: Instant): Boolean {
        if (end.epochSecond - start.epochSecond < MIN_SECONDS) return true
        if (!allowed(c)) return false
        val zone = ZoneId.systemDefault().rules
        val record = MindfulnessSessionRecord(
            start, zone.getOffset(start),
            end, zone.getOffset(end),
            // The start time names the sitting, so saving it twice updates it instead of duplicating it.
            Metadata.activelyRecorded(
                Device(Device.TYPE_PHONE, Build.MANUFACTURER, Build.MODEL),
                "sitting-${start.toEpochMilli()}", 1L,
            ),
            MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION,
            null, null,
        )
        return try {
            HealthConnectClient.getOrCreate(c).insertRecords(listOf(record))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Saves a sitting now, or keeps it to retry. */
    suspend fun saveOrQueue(c: Context, start: Instant, end: Instant) {
        if (!save(c, start, end)) Sitting.queue(c, start, end)
    }

    /** Retries every sitting that could not be saved before. */
    suspend fun retry(c: Context) {
        val done = Sitting.unsaved(c).filter { (a, b) -> save(c, a, b) }
        if (done.isNotEmpty()) Sitting.clearUnsaved(c, done)
    }
}
