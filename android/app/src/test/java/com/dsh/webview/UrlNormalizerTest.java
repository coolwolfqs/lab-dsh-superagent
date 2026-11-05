package com.dsh.webview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlNormalizerTest {

    @Test
    public void acceptsFullLauncherLine() {
        assertEquals("http://192.168.1.10:3082/?token=abc",
                UrlNormalizer.normalize("dsh web: http://192.168.1.10:3082/?token=abc"));
    }

    @Test
    public void acceptsLauncherLineWithoutSpace() {
        assertEquals("http://127.0.0.1:3080/?token=x",
                UrlNormalizer.normalize("dsh web:http://127.0.0.1:3080/?token=x"));
    }

    @Test
    public void trimsWhitespace() {
        assertEquals("https://dsh.example.com/ui/",
                UrlNormalizer.normalize("  https://dsh.example.com/ui/  "));
    }

    @Test
    public void stripsQuotes() {
        assertEquals("http://host:3082/", UrlNormalizer.normalize("\"http://host:3082/\""));
        assertEquals("http://host:3082/", UrlNormalizer.normalize("'http://host:3082/'"));
    }

    @Test
    public void addsHttpSchemeForBareHostAndPort() {
        assertEquals("http://192.168.1.10:3082", UrlNormalizer.normalize("192.168.1.10:3082"));
        assertEquals("http://localhost:3080", UrlNormalizer.normalize("localhost:3080"));
        assertEquals("http://example.com/path", UrlNormalizer.normalize("example.com/path"));
    }

    @Test
    public void keepsHttps() {
        assertEquals("https://dsh.example.com/", UrlNormalizer.normalize("https://dsh.example.com/"));
    }

    @Test
    public void rejectsNonHttpUrls() {
        assertNull(UrlNormalizer.normalize("ftp://example.com/"));
        assertNull(UrlNormalizer.normalize("javascript:alert(1)"));
        assertNull(UrlNormalizer.normalize("mailto:someone@example.com"));
    }

    @Test
    public void rejectsGarbage() {
        assertNull(UrlNormalizer.normalize(null));
        assertNull(UrlNormalizer.normalize(""));
        assertNull(UrlNormalizer.normalize("   "));
        assertNull(UrlNormalizer.normalize("not a url"));
        assertNull(UrlNormalizer.normalize("http://"));
        assertNull(UrlNormalizer.normalize("mailto:someone@example.com"));
    }

    @Test
    public void rejectsOutOfRangePort() {
        assertNull(UrlNormalizer.normalize("http://example.com:70000/"));
    }

    @Test
    public void authorityOfFillsDefaultPorts() {
        assertEquals("192.168.1.10:3082", UrlNormalizer.authorityOf("http://192.168.1.10:3082/?token=x"));
        assertEquals("dsh.example.com:443", UrlNormalizer.authorityOf("https://dsh.example.com/"));
        assertEquals("dsh.example.com:80", UrlNormalizer.authorityOf("http://dsh.example.com/ui/"));
        assertEquals("localhost:3080", UrlNormalizer.authorityOf("http://localhost:3080/"));
        assertNull(UrlNormalizer.authorityOf(null));
        assertNull(UrlNormalizer.authorityOf("not a url"));
    }

    @Test
    public void sameAuthorityIgnoresPathAndDefaultPort() {
        assertTrue(UrlNormalizer.sameAuthority(
                "http://192.168.1.10:3082/", "http://192.168.1.10:3082/?token=abc"));
        assertTrue(UrlNormalizer.sameAuthority(
                "http://dsh.example.com/", "http://dsh.example.com:80/ui/"));
        assertTrue(UrlNormalizer.sameAuthority(
                "http://192.168.1.10:3082/", "https://192.168.1.10:3082/"));
        assertFalse(UrlNormalizer.sameAuthority(
                "http://192.168.1.10:3082/", "http://192.168.1.10:3083/"));
        assertFalse(UrlNormalizer.sameAuthority(
                "http://192.168.1.10:3082/", "http://192.168.1.11:3082/"));
        assertFalse(UrlNormalizer.sameAuthority(null, "http://192.168.1.10:3082/"));
    }

    @Test
    public void stripsLaunchToken() {
        assertEquals("http://host:3082/",
                UrlNormalizer.stripLaunchToken("http://host:3082/?token=abc"));
        assertEquals("http://host:3082/?a=1",
                UrlNormalizer.stripLaunchToken("http://host:3082/?a=1&token=abc"));
        assertEquals("http://host:3082/?a=1",
                UrlNormalizer.stripLaunchToken("http://host:3082/?token=abc&a=1"));
        assertEquals("http://host:3082/?a=1&b=2",
                UrlNormalizer.stripLaunchToken("http://host:3082/?a=1&token=abc&b=2"));
        assertEquals("http://host:3082/",
                UrlNormalizer.stripLaunchToken("http://host:3082/"));
        assertEquals("http://host:3082/#frag",
                UrlNormalizer.stripLaunchToken("http://host:3082/?token=abc#frag"));
        assertNull(UrlNormalizer.stripLaunchToken(null));
    }
}
