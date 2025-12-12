package com.backrecorderapp

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

const val TAG = "AudioUtils"

fun concatAudioFiles(inputFiles: List<File>, outputFile: File) {

    if (inputFiles.isEmpty()) return

    val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    var muxerAudioTrack = -1
    var totalOffsetUs = 0L
    var isMuxerStarted = false

    val buffer = ByteBuffer.allocate(1024 * 1024)
    val bufferInfo = MediaCodec.BufferInfo()

    inputFiles.forEachIndexed { index, file ->

        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        // --- Find audio track ---
        val trackIndex = (0 until extractor.trackCount)
            .firstOrNull {
                extractor.getTrackFormat(it)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: run {
            extractor.release()
            return@forEachIndexed
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)

        // --- Add track only once ---
        if (index == 0) {
            // Clean CSD if needed (usually not needed for first file)
            muxerAudioTrack = muxer.addTrack(format)
            muxer.start()
            isMuxerStarted = true
        }

        // --- For next files: ignore their CSD ---
        if (index > 0) {
            format.setByteBuffer("csd-0", null)
            format.setByteBuffer("csd-1", null)
        }

        // --- Copy samples ---
        buffer.clear()

        while (true) {
            bufferInfo.size = extractor.readSampleData(buffer, 0)

            if (bufferInfo.size < 0) break

            bufferInfo.presentationTimeUs =
                extractor.sampleTime + totalOffsetUs

            bufferInfo.offset = 0

            @SuppressLint("WrongConstant")
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)

            extractor.advance()
        }

        // Increase offset for next file
        val duration = format.getLong(MediaFormat.KEY_DURATION)
        totalOffsetUs += duration

        extractor.release()
    }

    if (isMuxerStarted) muxer.stop()
    muxer.release()
}
