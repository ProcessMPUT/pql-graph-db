package com.processm.processminterpreter.xes.io

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.io.InputStream

@Component
class ClasspathBundledLogResourceReader {
    fun open(resourcePath: String): InputStream? {
        val resource = ClassPathResource(resourcePath.removePrefix("/"))
        return if (resource.exists()) resource.inputStream else null
    }
}
