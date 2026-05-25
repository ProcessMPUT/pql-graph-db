package com.processm.processminterpreter.application.ports

import java.io.InputStream

fun interface BundledLogResourceReader {
    fun open(resourcePath: String): InputStream?
}
