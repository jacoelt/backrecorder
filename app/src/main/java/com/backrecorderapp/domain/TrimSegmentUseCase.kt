package com.backrecorderapp.domain

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

class TrimSegmentUseCase @Inject constructor() {

    fun trim(inputFile: File, outputFile: File, startUs: Long, endUs: Long) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        val trackIndex = findAudioTrack(extractor)
            ?: run {
                extractor.release()
                throw IllegalStateException(
                    "No audio track found in ${inputFile.name} (file may be incomplete or corrupt)"
                )
            }
        extractor.selectTrack(trackIndex)

        val format = extractor.getTrackFormat(trackIndex)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(format)
        muxer.start()

        val bufferSize = format.getInteger(
            MediaFormat.KEY_MAX_INPUT_SIZE,
            1024 * 1024
        )
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        // Seek to start position
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val sampleTime = extractor.sampleTime
            if (sampleTime > endUs) break

            if (sampleTime >= startUs) {
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = sampleTime - startUs
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            }

            extractor.advance()
        }

        muxer.stop()
        muxer.release()
        extractor.release()
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }
}
