package com.controldmanager.app.share

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedHostsTest {
    @Test fun linkFromBrowser() =
        assertEquals(listOf("news.example.com"), SharedHosts.from("Some headline\nhttps://news.example.com/2026/11/story?id=1#top"))

    @Test fun lowercaseWithoutCredentialsPortOrTrailingDot() =
        assertEquals(listOf("example.com"), SharedHosts.from("http://user:secret@Example.COM.:8080/path"))

    @Test fun keepsSubdomainsAsShared() =
        assertEquals(listOf("www.example.com"), SharedHosts.from("https://www.example.com"))

    @Test fun internationalNamesBecomePunycode() =
        assertEquals(listOf("xn--bcher-kva.example"), SharedHosts.from("https://bücher.example/"))

    @Test fun severalLinksOnceEach() =
        assertEquals(
            listOf("a.example.com", "b.example.org"),
            SharedHosts.from("https://a.example.com/x https://b.example.org/y https://a.example.com/z"),
        )

    @Test fun bareDomainWhenThereIsNoLink() =
        assertEquals(listOf("tracker.example.net"), SharedHosts.from("block tracker.example.net please"))

    @Test fun ipAddressesAndJunkAreSkipped() {
        assertEquals(emptyList<String>(), SharedHosts.from("http://192.168.1.1/admin"))
        assertEquals(emptyList<String>(), SharedHosts.from("http://[2001:db8::1]/"))
        assertEquals(emptyList<String>(), SharedHosts.from("just some words"))
        assertEquals(emptyList<String>(), SharedHosts.from(null))
        assertEquals(emptyList<String>(), SharedHosts.from("https://-bad-.example/"))
    }

    @Test fun emailAddressIsNotADomainToBlock() =
        assertEquals(emptyList<String>(), SharedHosts.from("write to me@example.com"))
}
