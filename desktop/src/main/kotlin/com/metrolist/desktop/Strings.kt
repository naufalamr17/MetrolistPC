package com.metrolist.desktop

import javax.xml.parsers.DocumentBuilderFactory

object Strings {
    private val values: Map<String, String> by lazy {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val document = requireNotNull(javaClass.getResourceAsStream("/metrolist_strings.xml")).use { factory.newDocumentBuilder().parse(it) }
        val nodes = document.getElementsByTagName("string")
        buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                put(node.attributes.getNamedItem("name").nodeValue, node.textContent.replace("\\'", "'").replace("\\n", "\n"))
            }
        }
    }
    operator fun get(key: String): String = values[key] ?: key
}

fun s(key: String): String = Strings[key]

fun clock(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.toInt().coerceAtLeast(0) else 0
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
