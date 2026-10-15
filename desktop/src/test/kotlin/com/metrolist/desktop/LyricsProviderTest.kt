package com.metrolist.desktop

import com.metrolist.lrclib.LrcLib
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class LyricsProviderTest {
    @Test fun `exact lookup uses recording metadata and prefers timed lyrics`() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/get", request.url.encodedPath)
            assertEquals("Original fixture", request.url.parameters["track_name"])
            assertEquals("Fixture artist", request.url.parameters["artist_name"])
            assertEquals("120", request.url.parameters["duration"])
            respond("""{"id":1,"trackName":"Original fixture","artistName":"Fixture artist","duration":120.0,"plainLyrics":"Original fixture line","syncedLyrics":"[00:01.00]Original fixture line"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            expectSuccess = true
            defaultRequest { url("https://lrclib.net") }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try { assertEquals("[00:01.00]Original fixture line", LrcLib.exactLyrics(client, "Original fixture", "Fixture artist", 120, null)) }
        finally { client.close() }
    }

    @Test fun `rate limit is not hidden as a missing lyric and not retried in a loop`() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            expectSuccess = true
            defaultRequest { url("https://lrclib.net") }
        }
        try { assertFailsWith<ClientRequestException> { LrcLib.exactLyrics(client, "Original", "Fixture", 120, null) }; assertEquals(1, requests) }
        finally { client.close() }
    }
}
