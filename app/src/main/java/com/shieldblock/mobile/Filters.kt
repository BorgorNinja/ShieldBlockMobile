package com.shieldblock.mobile

import java.io.Reader

/**
 * Domain-level filter set. Only rules that a DNS/VPN blocker can honour are kept:
 *   ||example.com^            (optionally with $third-party / $important / $all)
 *   @@||example.com^          (exceptions)
 *   0.0.0.0 example.com       (hosts format)
 *   example.com               (domain-only lists)
 * Cosmetic filters, scriptlets, URL-path rules and resource-type rules cannot work without
 * inspecting HTTPS traffic and are skipped.
 */
class FilterEngine(private val blocked: HashSet<String>, private val allowed: HashSet<String>) {
    val blockCount: Int get() = blocked.size

    fun isBlocked(host: String): Boolean {
        var s = host.lowercase().trimEnd('.')
        var hit = false
        while (true) {
            if (s in allowed) return false
            if (s in blocked) hit = true
            val i = s.indexOf('.')
            if (i < 0) break
            s = s.substring(i + 1)
        }
        return hit
    }
}

class FilterBuilder {
    private val blocked = HashSet<String>(262_144)
    private val allowed = HashSet<String>(4_096)

    fun addList(reader: Reader) = reader.forEachLine { parseLine(it) }

    fun allow(host: String) {
        val h = host.trim().lowercase()
        if (validHost(h)) allowed.add(h)
    }

    fun build() = FilterEngine(blocked, allowed)

    fun parseLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        val c = line[0]
        if (c == '!' || c == '[' || c == '#') return
        when {
            line.startsWith("@@||") -> networkHost(line.substring(4), true)?.let { allowed.add(it) }
            c == '|' -> if (line.startsWith("||")) networkHost(line.substring(2), false)?.let { blocked.add(it) }
            c == '@' || c == '/' || c == '*' || c == '-' || c == '.' || c == '&' || c == '?' || c == '_' -> Unit
            else -> plainOrHosts(line)
        }
    }

    private fun plainOrHosts(line: String) {
        // cosmetic / scriptlet / HTML-filter rules contain "##", "#@#", "#?#", "#$#", "#%#"
        val h = line.indexOf('#')
        if (h > 0 && h + 1 < line.length && line[h + 1] in "#@?$%") return
        val body = if (h >= 0) line.substring(0, h).trim() else line
        val parts = body.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        if (parts.size >= 2) {
            val ip = parts[0]
            if (ip == "0.0.0.0" || ip == "127.0.0.1" || ip == "::" || ip == "::1") {
                for (i in 1 until parts.size) addHost(parts[i])
            }
        } else if (body.indexOf('$') < 0 && body.indexOf('/') < 0 && body.indexOf('*') < 0) {
            addHost(parts[0])
        }
    }

    private fun addHost(h: String) {
        val l = h.lowercase()
        if (l == "localhost.localdomain" || l == "local" || l == "broadcasthost") return
        if (validHost(l)) blocked.add(l)
    }

    private fun networkHost(body: String, exception: Boolean): String? {
        val d = body.indexOf('$')
        val pattern = if (d >= 0) body.substring(0, d) else body
        val opts = if (d >= 0) body.substring(d + 1) else ""
        if (!pattern.endsWith("^")) return null
        val host = pattern.dropLast(1).lowercase()
        if (!validHost(host)) return null
        if (opts.isNotEmpty()) {
            val ok = if (exception) EXC_OPTS else BLOCK_OPTS
            if (!opts.split(',').all { it in ok }) return null
        }
        return host
    }

    private fun validHost(h: String): Boolean {
        if (h.length < 4 || h.length > 253 || h.indexOf('.') < 1) return false
        if (h.last() == '.' || h[0] == '-' || h[0] == '.') return false
        for (ch in h) {
            if (!(ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_')) return false
        }
        return true
    }

    private companion object {
        val BLOCK_OPTS = setOf("third-party", "3p", "important", "all")
        val EXC_OPTS = setOf("important", "all", "document", "doc")
    }
}
