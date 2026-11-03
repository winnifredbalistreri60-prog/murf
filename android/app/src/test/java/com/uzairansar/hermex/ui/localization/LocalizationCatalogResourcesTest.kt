package com.uzairansar.hermex.ui.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocalizationCatalogResourcesTest {
    @Test
    fun catalogEntriesHaveMatchingEnglishAndRussianPlaceholders() {
        val source = File("src/main/java/com/uzairansar/hermex/ui/localization/AndroidLocalizationCatalog.kt").readText()
        val references = Regex("R\\.(string|plurals)\\.(\\w+)").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }.toSet()
        assertTrue("No catalog references found", references.isNotEmpty())
        val english = resources("values")
        val russian = resources("values-ru")
        for (key in references) {
            val en = english[key]
            val ru = russian[key]
            assertNotNull("Missing English resource $key", en)
            assertNotNull("Missing Russian resource $key", ru)
            if (en == null || ru == null) continue
            if (key.first == "string") {
                assertEquals("Placeholders differ for $key", placeholders(en.textContent), placeholders(ru.textContent))
            } else {
                val enItems = items(en)
                for ((quantity, text) in items(ru)) {
                    val enText = enItems[quantity] ?: enItems["other"]
                    assertNotNull("Missing English plural $key/$quantity", enText)
                    assertEquals("Placeholders differ for $key/$quantity", placeholders(enText.orEmpty()), placeholders(text))
                }
                assertTrue("Missing Russian plural items for $key", items(ru).isNotEmpty())
            }
        }
    }

    private fun resources(directory: String): Map<Pair<String, String>, Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$directory/localization_catalog.xml"))
        val children = document.documentElement.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
            .filter { it.tagName == "string" || it.tagName == "plurals" }
            .associateBy { it.tagName to it.getAttribute("name") }
    }

    private fun items(plural: Element): Map<String, String> {
        val children = plural.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
            .filter { it.tagName == "item" }.associate { it.getAttribute("quantity") to it.textContent }
    }

    private fun placeholders(text: String): List<Pair<Int, String>> {
        var next = 0
        return Regex("%(?:(\\d+)\\$)?(lld|ld|[sd@])").findAll(text).map {
            next++
            (it.groupValues[1].toIntOrNull() ?: next) to it.groupValues[2]
        }.sortedWith(compareBy({ it.first }, { it.second })).toList()
    }
}
