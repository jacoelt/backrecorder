package com.backrecorderapp.services

object RecordingServiceHolder {
    private var service: AudioRecordingService? = null

    fun set(service: AudioRecordingService) {
        this.service = service
    }

    fun get(): AudioRecordingService? = service
}