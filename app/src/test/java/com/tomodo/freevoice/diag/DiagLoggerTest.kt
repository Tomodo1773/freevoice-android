package com.tomodo.freevoice.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagLoggerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun logKeepsCompleteNewestLinesWithinByteLimit() {
        val file = temporaryFolder.newFile()
        val logger = DiagLogger(file, maxBytes = 180, now = { Date(0) })

        repeat(12) { logger.log("日本語ログ-$it") }

        val bytes = file.readBytes()
        assertTrue(bytes.size <= 180)
        assertTrue(file.readText().endsWith("\n"))
        logger.read().lineSequence().filter(String::isNotEmpty).forEach {
            assertTrue(it.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} 日本語ログ-\d+""")))
        }
        assertTrue(logger.read().contains("日本語ログ-11"))
        assertFalse(logger.read().contains("日本語ログ-0\n"))
    }

    @Test
    fun instancesWritingConcurrentlyDoNotLoseEntries() {
        val file = temporaryFolder.newFile()
        val first = DiagLogger(file, now = { Date(0) })
        val second = DiagLogger(file, now = { Date(0) })
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        executor.submit { start.await(); repeat(100) { first.log("first-$it") } }
        executor.submit { start.await(); repeat(100) { second.log("second-$it") } }
        start.countDown()
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(200, first.read().lineSequence().count(String::isNotEmpty))
    }

    @Test
    fun readLatestLimitsTheUiAndSecretsAreRedacted() {
        val file = temporaryFolder.newFile()
        val logger = DiagLogger(file, now = { Date(0) })
        logger.log("api-key=very-secret")
        logger.log("middle")
        logger.log("Authorization: Bearer-token")

        val latest = logger.readLatest(2)

        assertFalse(logger.read().contains("very-secret"))
        assertFalse(logger.read().contains("Bearer-token"))
        assertFalse(latest.contains("api-key"))
        assertTrue(latest.contains("middle"))
        assertTrue(latest.contains("Authorization: <redacted>"))
    }
}
