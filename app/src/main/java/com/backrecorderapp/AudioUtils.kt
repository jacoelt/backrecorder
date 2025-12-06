package com.backrecorderapp

import java.io.File

fun concatAudioFiles(files: List<File>, output: File) {
    return remuxOggOpusFiles(files, output)
}

