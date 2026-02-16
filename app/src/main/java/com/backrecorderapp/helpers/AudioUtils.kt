package com.backrecorderapp.helpers

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "AudioUtils"

fun concatAudioFiles(inputFiles: List<File>, outputFile: File) {

    Log.d(TAG, "Concat: Start")

    if (inputFiles.isEmpty()) return

    var muxer: MediaMuxer? = null
    var muxerAudioTrack = -1
    var totalOffsetUs = 0L
    var isMuxerStarted = false

    val buffer = ByteBuffer.allocate(256 * 1024)
    val bufferInfo = MediaCodec.BufferInfo()

    try {
        muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        inputFiles.forEachIndexed { index, file ->

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)

                // --- Find audio track ---
                val trackIndex = (0 until extractor.trackCount)
                    .firstOrNull {
                        extractor.getTrackFormat(it)
                            .getString(MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    } ?: return@forEachIndexed

                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)

                // --- Add track only once ---
                if (index == 0) {
                    muxerAudioTrack = muxer.addTrack(format)
                    muxer.start()
                    isMuxerStarted = true
                }


                // --- Copy samples ---
                buffer.clear()
                var lastSampleTimeUs = 0L

                while (true) {
                    bufferInfo.size = extractor.readSampleData(buffer, 0)

                    if (bufferInfo.size < 0) break

                    bufferInfo.presentationTimeUs =
                        extractor.sampleTime + totalOffsetUs

                    bufferInfo.offset = 0

                    bufferInfo.flags =
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        else
                            0

                    muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)

                    lastSampleTimeUs = extractor.sampleTime
                    extractor.advance()
                }

                totalOffsetUs += lastSampleTimeUs

            } catch (e: Exception) {
                Log.e(TAG, "Concat failed for file ${file.name}", e)
            } finally {
                extractor.release()
            }
        }

    } catch (e: Exception) {
        Log.e(TAG, "Concat failed", e)
    } finally {
        try {
            if (isMuxerStarted) muxer?.stop()
        } catch (_: Exception) {}
        muxer?.release()
    }

    Log.d(TAG, "Concat: End")
}
