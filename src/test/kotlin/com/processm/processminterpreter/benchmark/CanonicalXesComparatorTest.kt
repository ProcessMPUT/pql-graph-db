package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class CanonicalXesComparatorTest {
    @Test
    fun `comparator accepts equivalent canonical XES`() {
        val expected = CanonicalXesParser.parse(xesWithEventAttributes("""<string key="attr_1" value="a"/>"""))
        val actual = CanonicalXesParser.parse(xesWithEventAttributes("""<string key="attr_1" value="a"/>"""))

        assertTrue(CanonicalXesComparator.compare(expected, actual).isEmpty())
    }

    @Test
    fun `comparator detects missing attributes`() {
        val expected = CanonicalXesParser.parse(xesWithEventAttributes("""<string key="attr_1" value="a"/>"""))
        val actual = CanonicalXesParser.parse(xesWithEventAttributes(""))

        val differences = CanonicalXesComparator.compare(expected, actual)

        assertFalse(differences.isEmpty())
        assertTrue(differences.any { it.contains("attributes") })
    }

    @Test
    fun `parser reports duplicate keys`() {
        val parsed = CanonicalXesParser.parse(
            xesWithEventAttributes(
                """
                <string key="attr_1" value="a"/>
                <string key="attr_1" value="b"/>
                """.trimIndent(),
            ),
        )

        assertTrue(parsed.duplicateKeys.any { it.contains("attr_1") })
    }

    private fun xesWithEventAttributes(attributes: String): ByteArray =
        """
        <?xml version="1.0" encoding="UTF-8" ?>
        <log xes.version="1.0">
          <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
          <classifier name="Activity" keys="concept:name"/>
          <string key="concept:name" value="test"/>
          <trace>
            <string key="concept:name" value="trace-1"/>
            <event>
              <string key="concept:name" value="activity-1"/>
              $attributes
            </event>
          </trace>
        </log>
        """.trimIndent().trim().toByteArray(StandardCharsets.UTF_8)
}
