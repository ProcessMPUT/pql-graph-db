package com.processm.processminterpreter.infrastructure.xes

import com.processm.processminterpreter.application.ports.BundledLogResourceReader
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.io.InputStream

@Component
class ClasspathBundledLogResourceReader : BundledLogResourceReader {
    override fun open(resourcePath: String): InputStream? {
        val resource = ClassPathResource(resourcePath.removePrefix("/"))
        return if (resource.exists()) resource.inputStream else null
    }
}
