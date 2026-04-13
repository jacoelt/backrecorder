package com.backrecorderapp.domain

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.util.TimeUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

interface RecordingFlusher {
    suspend fun flushCurrentSegment()
}

sealed class SaveResult {
    data object Success : SaveResult()
    data class UpgradeRequired(val reason: BlockReason) : SaveResult()
    data class Error(val message: String) : SaveResult()
}

class SaveRecordingUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val segmentManager: SegmentManager,
    private val trimUseCase: TrimSegmentUseCase,
    private val subscriptionGate: SubscriptionGate,
    private val recordingFlusher: RecordingFlusher
) {

    suspend fun execute(startTime: Long, endTime: Long, outputUri: Uri): SaveResult {
        // Check subscription gate
        val gateResult = subscriptionGate.checkSaveAccess(startTime, endTime)
        if (gateResult is GateResult.Blocked) {
            return SaveResult.UpgradeRequired(gateResult.reason)
        }

        // Get overlapping segments
        var segments = segmentManager.getSegmentsInRange(startTime, endTime)
        if (segments.isEmpty()) {
            return SaveResult.Error("No recorded data found for the selected time range")
        }

        // If any segment is still being recorded, its MP4 moov atom hasn't been
        // written yet — MediaExtractor cannot read it. Ask the recording service
        // to finalize the current segment, then clamp endTime and refetch so only
        // complete segments are processed.
        var effectiveEndTime = endTime
        if (segments.any { !it.isComplete }) {
            val flushMoment = System.currentTimeMillis()
            try {
                recordingFlusher.flushCurrentSegment()
            } catch (e: Exception) {
                return SaveResult.Error("Failed to finalize in-progress recording: ${e.message}")
            }
            effectiveEndTime = minOf(endTime, flushMoment)
            segments = segmentManager.getSegmentsInRange(startTime, effectiveEndTime)
                .filter { it.isComplete }
            if (segments.isEmpty()) {
                return SaveResult.Error("No recorded data found for the selected time range")
            }
        }

        return try {
            if (segments.size == 1) {
                saveSingleSegment(segments[0], startTime, effectiveEndTime, outputUri)
            } else {
                saveMultipleSegments(segments, startTime, effectiveEndTime, outputUri)
            }
            SaveResult.Success
        } catch (e: Exception) {
            SaveResult.Error(e.message ?: "Unknown error while saving")
        }
    }

    private fun effectiveDurationMs(segment: SegmentEntity): Long {
        return if (segment.isComplete) {
            segment.durationMs
        } else {
            System.currentTimeMillis() - segment.startTime
        }
    }

    private fun saveSingleSegment(
        segment: SegmentEntity,
        startTime: Long,
        endTime: Long,
        outputUri: Uri
    ) {
        val inputFile = File(segment.filePath)
        val duration = effectiveDurationMs(segment)
        val startUs = ((startTime - segment.startTime) * 1000).coerceAtLeast(0)
        val endUs = ((endTime - segment.startTime) * 1000).coerceAtMost(duration * 1000)

        val tempFile = File(context.cacheDir, "temp_save_${System.currentTimeMillis()}.m4a")
        try {
            trimUseCase.trim(inputFile, tempFile, startUs, endUs)
            copyToUri(tempFile, outputUri)
        } finally {
            tempFile.delete()
        }
    }

    private fun saveMultipleSegments(
        segments: List<SegmentEntity>,
        startTime: Long,
        endTime: Long,
        outputUri: Uri
    ) {
        val tempFiles = mutableListOf<File>()
        try {
            // Trim first and last segments, copy middle ones as-is
            for ((index, segment) in segments.withIndex()) {
                val tempFile = File(context.cacheDir, "temp_seg_${index}_${System.currentTimeMillis()}.m4a")
                tempFiles.add(tempFile)

                val inputFile = File(segment.filePath)
                when (index) {
                    0 -> {
                        val duration = effectiveDurationMs(segment)
                        val startUs = ((startTime - segment.startTime) * 1000).coerceAtLeast(0)
                        val endUs = duration * 1000
                        trimUseCase.trim(inputFile, tempFile, startUs, endUs)
                    }
                    segments.lastIndex -> {
                        val duration = effectiveDurationMs(segment)
                        val endUs = ((endTime - segment.startTime) * 1000).coerceAtMost(duration * 1000)
                        trimUseCase.trim(inputFile, tempFile, 0, endUs)
                    }
                    else -> {
                        inputFile.copyTo(tempFile, overwrite = true)
                    }
                }
            }

            // Concatenate all temp files into final output
            val mergedFile = File(context.cacheDir, "merged_${System.currentTimeMillis()}.m4a")
            try {
                concatenateM4aFiles(tempFiles, mergedFile)
                copyToUri(mergedFile, outputUri)
            } finally {
                mergedFile.delete()
            }
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    private fun concatenateM4aFiles(inputFiles: List<File>, outputFile: File) {
        if (inputFiles.isEmpty()) return
        if (inputFiles.size == 1) {
            inputFiles[0].copyTo(outputFile, overwrite = true)
            return
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var timeOffset = 0L

        for (inputFile in inputFiles) {
            val extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            val trackIndex = findAudioTrack(extractor)
            if (trackIndex == null) {
                extractor.release()
                continue
            }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            if (muxerTrackIndex == -1) {
                muxerTrackIndex = muxer.addTrack(format)
                muxer.start()
            }

            val bufferSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var lastSampleTime = 0L

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime + timeOffset
                bufferInfo.flags = extractor.sampleFlags
                lastSampleTime = extractor.sampleTime

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            // Add a small gap to avoid timestamp collision
            timeOffset += lastSampleTime + 1000

            extractor.release()
        }

        if (muxerTrackIndex != -1) {
            muxer.stop()
        }
        muxer.release()
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun copyToUri(sourceFile: File, destUri: Uri) {
        context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
            sourceFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }
}
