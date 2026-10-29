package com.familyguard.core.domain.web

import java.net.IDN
import java.util.Locale

/**
 * Domain matcher for the local DNS filter (PLAN M6). Pure Kotlin so the
 * suffix / wildcard / IDN behaviour is unit-tested on the JVM.
 *
 * Rules:
 *  - entries match on the *label* boundary: "example.com" blocks
 *    "example.com" and "sub.example.com" but NOT "notexample.com";
 *  - a leading "*." is equivalent to the bare domain (explicit wildcard);
 *  - a leading "*." plus more labels (e.g. "*.shop.example.com") matches
 *    exactly that subdomain space;
 *  - everything is compared as ASCII punycode (IDN folded with IDN.toASCII),
 *    lower-cased, trailing dot ignored.
 */
object WebMatcher {

    /**
     * @param blocklist domains to block (blocklist mode)
     * @param allowlist when non-null the filter runs in allowlist mode:
     *   only these domains (+ their subdomains) resolve, everything else is
     *   blocked. Allowlist always wins over blocklist.
     */
    fun shouldBlock(
        host: String,
        blocklist: Collection<String>,
        allowlist: Collection<String> = emptyList(),
    ): Boolean {
        val q = canonical(host)
        if (q.isEmpty()) return false

        if (allowlist.isNotEmpty()) {
            return allowlist.none { canonical(it).let { e -> matches(q, e) } }
        }
        return blocklist.any { matches(q, canonical(it)) }
    }

    /** Host canonicalisation: ASCII punycode, lowercase, no trailing dot. */
    fun canonical(host: String): String {
        val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (h.isEmpty()) return ""
        return runCatching { IDN.toASCII(h, IDN.USE_STD3_ASCII_RULES) }.getOrDefault(h)
    }

    private fun matches(query: String, entry: String): Boolean {
        if (entry.isEmpty()) return false
        val pattern = entry.removePrefix("*.")
        return query == pattern ||
            query.endsWith(".$pattern")
    }
}
