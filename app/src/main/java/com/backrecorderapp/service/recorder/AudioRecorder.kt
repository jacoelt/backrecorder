package com.backrecorderapp.service.recorder

import java.io.File

/**
 * The recorder boundary of [com.backrecorderapp.service.RecordingService], extracted so
 * instrumented tests can drive recording without a microphone.
 *
 * Instrumented tests replace [AudioRecorderFactory] through Hilt and supply a fake that writes
 * real MP4 segments.
 *
 * One instance records one segment: [start] once, then [stop] once. The service creates a fresh
 * one per segment.
 */
interface AudioRecorder {

    /**
     * Begins recording into [outputFile] as MPEG-4/AAC. The file must be readable by
     * `MediaExtractor` once [stop] returns — `SaveRecordingUseCase` concatenates segments with it,
     * so a file without a MOOV atom is worse than no file at all.
     */
    fun start(outputFile: File)

    /**
     * Finalizes the output file. Releases the underlying resources either way, including when it
     * throws — which it does when the segment captured no data, as `MediaRecorder.stop()` does on
     * a segment flushed right after it opened. From the moment [stop] begins, [maxAmplitude] reads
     * 0 (#48). Calling it a second time does nothing.
     */
    fun stop()

    /**
     * Peak amplitude since the previous read, in the raw 16-bit range (0..32767) that
     * `MediaRecorder.getMaxAmplitude` reports. The service normalizes it for the waveform.
     */
    fun maxAmplitude(): Int
}

/** Creates one [AudioRecorder] per segment. Replaced wholesale in instrumented tests. */
interface AudioRecorderFactory {
    fun create(): AudioRecorder
}
