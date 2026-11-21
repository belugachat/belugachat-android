package com.belugachat.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.belugachat.app.data.AppDatabase
import com.belugachat.app.data.MessageEntity
import com.belugachat.app.data.ProviderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AnalyticsTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() {
        db.close()
    }

    private fun message(
        id: String,
        conversation: String,
        provider: String,
        model: String,
        at: Long,
        input: Long,
        output: Long,
        status: String = "complete",
    ) = MessageEntity(
        id = id,
        conversationId = conversation,
        parentId = null,
        role = "assistant",
        partsJson = "[]",
        plainText = "",
        createdAt = at,
        status = status,
        providerId = provider,
        modelId = model,
        inputTokens = input,
        outputTokens = output,
    )

    @Test fun aggregatesTotalsModelsAndDays() = runBlocking {
        db.providers().upsertProvider(
            ProviderEntity("p1", "OpenAI", "openai", "openai", "https://api.openai.com", "x", 0),
        )
        val base = 1_700_000_000_000L
        db.chats().upsertMessage(message("m1", "c1", "p1", "gpt-4o", base, 100, 20))
        db.chats().upsertMessage(message("m2", "c1", "p1", "gpt-4o", base + 1_000, 50, 10, status = "error"))
        db.chats().upsertMessage(message("m3", "c2", "p1", "gpt-4o-mini", base + 2_000, 30, 5))

        val totals = db.chats().analyticsTotals(0, Long.MAX_VALUE)
        assertEquals(3, totals.answers)
        assertEquals(180, totals.inputTokens)
        assertEquals(35, totals.outputTokens)
        assertEquals(1, totals.errors)
        assertEquals(2, totals.conversations)
        assertEquals(1, totals.activeDays)

        val models = db.chats().analyticsByModel(0, Long.MAX_VALUE)
        assertEquals(2, models.size)
        assertEquals("gpt-4o", models[0].modelId)
        assertEquals("OpenAI", models[0].providerName)
        assertEquals(2, models[0].answers)
        assertEquals(1, models[0].errors)
        assertEquals(150, models[0].inputTokens)
        assertEquals(30, models[0].outputTokens)

        val daily = db.chats().analyticsDaily(0, Long.MAX_VALUE)
        assertEquals(1, daily.size)
        assertEquals(3, daily[0].answers)
        assertEquals(180, daily[0].inputTokens)

        val hourly = db.chats().analyticsHourly(0, Long.MAX_VALUE)
        assertTrue(hourly.isNotEmpty())
        assertEquals(3, hourly.sumOf { it.answers })
    }
}
