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

    /** Finalizes the output file and releases the underlying resources. */
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
