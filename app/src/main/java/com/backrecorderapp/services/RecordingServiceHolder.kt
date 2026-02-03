package com.backrecorderapp.services

object RecordingServiceHolder {
    private var service: RecordingService? = null

    fun set(service: RecordingService) {
        this.service = service
    }

    fun get(): RecordingService? = service
}