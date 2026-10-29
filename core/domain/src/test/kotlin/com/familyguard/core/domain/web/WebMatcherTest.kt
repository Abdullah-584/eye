package com.familyguard.core.domain.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMatcherTest {

    @Test
    fun `exact domain and all its subdomains are blocked`() {
        val list = listOf("example.com")
        assertTrue(WebMatcher.shouldBlock("example.com", list))
        assertTrue(WebMatcher.shouldBlock("www.example.com", list))
        assertTrue(WebMatcher.shouldBlock("a.b.example.com", list))
    }

    @Test
    fun `suffix must match a label boundary`() {
        val list = listOf("example.com")
        assertFalse(WebMatcher.shouldBlock("notexample.com", list))
        assertFalse(WebMatcher.shouldBlock("example.com.evil.net", list))
    }

    @Test
    fun `explicit wildcard prefix behaves like the bare domain`() {
        val list = listOf("*.example.com")
        assertTrue(WebMatcher.shouldBlock("example.com", list))
        assertTrue(WebMatcher.shouldBlock("shop.example.com", list))
    }

    @Test
    fun `allowlist mode blocks everything not allowed and wins over blocklist`() {
        val block = listOf("bad.example")
        val allow = listOf("school.edu")
        assertTrue(WebMatcher.shouldBlock("anything.net", block, allow))
        assertFalse(WebMatcher.shouldBlock("portal.school.edu", block, allow))
        assertTrue(WebMatcher.shouldBlock("sub.bad.example", block, allow))
    }

    @Test
    fun `IDN hosts match their punycode entries and vice versa`() {
        assertTrue(WebMatcher.shouldBlock("bücher.de", listOf("bücher.de")))
        assertTrue(WebMatcher.shouldBlock("xn--bcher-kva.de", listOf("bücher.de")))
        assertTrue(WebMatcher.shouldBlock("BÜCHER.DE", listOf("xn--bcher-kva.de")))
    }

    @Test
    fun `case, trailing dots and whitespace are ignored`() {
        assertTrue(WebMatcher.shouldBlock(" Example.COM. ", listOf("example.com")))
    }

    @Test
    fun `empty query never blocks`() {
        assertFalse(WebMatcher.shouldBlock("", listOf("example.com")))
        assertFalse(WebMatcher.shouldBlock("   ", listOf("example.com")))
    }
}
