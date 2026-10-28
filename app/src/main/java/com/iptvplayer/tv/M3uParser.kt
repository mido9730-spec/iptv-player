package com.iptvplayer.tv

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String
)

object M3uParser {

    private val attrRegex = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var name = ""
        var group = ""
        var logo = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attrs = HashMap<String, String>()
                for (m in attrRegex.findAll(line)) {
                    attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
                }
                group = attrs["group-title"]?.trim().orEmpty()
                logo = attrs["tvg-logo"].orEmpty()

                // The channel name follows the first comma after the last quoted attribute.
                val lastQuote = line.lastIndexOf('"')
                val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                if (name.isEmpty()) name = attrs["tvg-name"].orEmpty()
            } else if (line.startsWith("#EXTGRP", ignoreCase = true)) {
                if (group.isEmpty()) group = line.substringAfter(':', "").trim()
            } else if (line.startsWith("#")) {
                continue
            } else if (line.contains("://")) {
                val finalName = if (name.isNotEmpty()) name else line.substringAfterLast('/').substringBefore('?')
                result.add(Channel(finalName, line, if (group.isEmpty()) "Other" else group, logo))
                name = ""
                group = ""
                logo = ""
            }
        }
        return result
    }
}
