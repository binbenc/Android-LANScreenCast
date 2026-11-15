package com.benchan.wireless.screencasting.protocol.upnp

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XmlException(message: String) : Exception(message)

/**
 * XML 工具。解析统一走 [parse]：限制长度、拒绝 DOCTYPE / ENTITY（防 XXE 与“十亿笑声”），命名空间感知。
 * javax.xml DOM 在 Android 与 JVM 上都有，不引入依赖。
 */
object Xml {
    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    fun parse(text: String, maxChars: Int): Document {
        if (text.length > maxChars) throw XmlException("xml too large")
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
            throw XmlException("DTD not allowed")
        }
        // 部分电视在 XML 前带 BOM 或空白
        val clean = text.trimStart('﻿', ' ', '\t', '\r', '\n')
        return try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }.newDocumentBuilder().parse(ByteArrayInputStream(clean.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw XmlException("malformed xml")
        }
    }

    fun children(e: Element): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        return out
    }

    /** 本地名（忽略命名空间与大小写）匹配的第一个直接子元素。 */
    fun child(e: Element, local: String): Element? = children(e).firstOrNull { localName(it).equals(local, ignoreCase = true) }

    fun childText(e: Element, local: String): String? = child(e, local)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    fun localName(e: Element): String = e.localName ?: e.nodeName.substringAfter(':')

    /** 文档中第一个本地名匹配的元素（任意命名空间）。 */
    fun first(doc: Document, local: String): Element? {
        val list = doc.getElementsByTagNameNS("*", local)
        return if (list.length > 0) list.item(0) as Element else null
    }
}
