package com.belugachat.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Wire protocol: [ProviderKind]. */
    val kind: String,
    val preset: String,
    val baseUrl: String,
    val encryptedKey: String,
    val createdAt: Long,
)

object ProviderKind {
    const val ANTHROPIC = "anthropic"
    const val OPENAI = "openai"
}

@Entity(tableName = "models", primaryKeys = ["providerId", "modelId"])
data class ModelEntity(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val enabled: Boolean,
    val sort: Int,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val activeLeafId: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    /** Null means "use the default system prompt from Settings". */
    val systemPrompt: String? = null,
    val reasoning: String = Reasoning.DEFAULT,
)

object Reasoning {
    const val DEFAULT = "default"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val MAX = "max"
    val all = listOf(DEFAULT, LOW, MEDIUM, HIGH, MAX)
}

@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("parentId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String?,
    val role: String,
    val partsJson: String,
    val plainText: String,
    val createdAt: Long,
    val status: String = MessageStatus.COMPLETE,
    val error: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    val parts: List<Part> get() = decodeParts(partsJson)
}

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val encryptedHeaders: String?,
    val enabled: Boolean,
    val status: String = "new",
    val lastError: String? = null,
    /** Cached `tools/list` result: JSON array of {name, description, inputSchema}. */
    val toolsJson: String = "[]",
    val installedItemId: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "skills", indices = [Index(value = ["name"], unique = true)])
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val body: String,
    /** JSON array of supporting file paths. */
    val filesJson: String = "[]",
    val enabled: Boolean = true,
    val source: String = "import",
    val installedItemId: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "installed_items")
data class InstalledItemEntity(
    @PrimaryKey val id: String,
    val source: String,
    val itemId: String,
    val marketplace: String,
    val name: String,
    val version: String?,
    val installedAt: Long,
)

@Entity(tableName = "tool_prefs")
data class ToolPrefEntity(
    @PrimaryKey val toolKey: String,
    val enabled: Boolean,
    val autoApprove: Boolean,
)

data class ModelUsage(val modelId: String, val messages: Int, val inputTokens: Long, val outputTokens: Long)

data class AnalyticsTotals(
    val answers: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val errors: Int,
    val conversations: Int,
    val activeDays: Int,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class ModelAnalytics(
    val modelId: String,
    val providerId: String?,
    val providerName: String?,
    val answers: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val errors: Int,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class DailyAnalytics(
    val day: String,
    val answers: Int,
    val inputTokens: Long,
    val outputTokens: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class HourlyAnalytics(val hour: Int, val answers: Int)

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY createdAt")
    fun observeProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun provider(id: String): ProviderEntity?

    @Upsert
    suspend fun upsertProvider(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteProvider(id: String)

    @Query("SELECT * FROM models ORDER BY providerId, sort")
    fun observeModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY sort")
    suspend fun models(providerId: String): List<ModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<ModelEntity>)

    @Query("DELETE FROM models WHERE providerId = :providerId")
    suspend fun deleteModels(providerId: String)

    @Query("UPDATE models SET enabled = :enabled WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun setModelEnabled(providerId: String, modelId: String, enabled: Boolean)

    @Transaction
    suspend fun replaceModels(providerId: String, models: List<ModelEntity>) {
        deleteModels(providerId)
        insertModels(models)
    }
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("UPDATE conversations SET activeLeafId = :leafId, updatedAt = :now WHERE id = :id")
    suspend fun setActiveLeaf(id: String, leafId: String?, now: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt")
    suspend fun messages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: String): MessageEntity?

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Insert
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: String)

    @Query("UPDATE messages SET status = :status, error = :error WHERE status = :from")
    suspend fun markInterrupted(from: String = MessageStatus.STREAMING, status: String = MessageStatus.STOPPED, error: String? = "Interrupted when the app closed.")

    @Query(
        """SELECT DISTINCT c.* FROM conversations c LEFT JOIN messages m ON m.conversationId = c.id
        WHERE c.title LIKE '%' || :query || '%' OR m.plainText LIKE '%' || :query || '%'
        ORDER BY c.updatedAt DESC LIMIT 50""",
    )
    suspend fun search(query: String): List<ConversationEntity>

    @Query(
        """SELECT modelId, COUNT(*) AS messages, SUM(inputTokens) AS inputTokens, SUM(outputTokens) AS outputTokens
        FROM messages WHERE role = 'assistant' AND modelId IS NOT NULL AND createdAt >= :since
        GROUP BY modelId ORDER BY outputTokens DESC""",
    )
    suspend fun usageSince(since: Long): List<ModelUsage>

    @Query(
        """SELECT COUNT(*) AS answers,
        COALESCE(SUM(inputTokens), 0) AS inputTokens,
        COALESCE(SUM(outputTokens), 0) AS outputTokens,
        COALESCE(SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END), 0) AS errors,
        COUNT(DISTINCT conversationId) AS conversations,
        COUNT(DISTINCT date(createdAt / 1000, 'unixepoch', 'localtime')) AS activeDays
        FROM messages
        WHERE role = 'assistant' AND modelId IS NOT NULL AND createdAt >= :from AND createdAt < :to""",
    )
    suspend fun analyticsTotals(from: Long, to: Long): AnalyticsTotals

    @Query(
        """SELECT strftime('%Y-%m-%d', createdAt / 1000, 'unixepoch', 'localtime') AS day,
        COUNT(*) AS answers,
        COALESCE(SUM(inputTokens), 0) AS inputTokens,
        COALESCE(SUM(outputTokens), 0) AS outputTokens
        FROM messages
        WHERE role = 'assistant' AND modelId IS NOT NULL AND createdAt >= :from AND createdAt < :to
        GROUP BY day ORDER BY day""",
    )
    suspend fun analyticsDaily(from: Long, to: Long): List<DailyAnalytics>

    @Query(
        """SELECT m.modelId AS modelId,
        m.providerId AS providerId,
        COALESCE(p.name, m.providerId, 'Unknown provider') AS providerName,
        COUNT(*) AS answers,
        COALESCE(SUM(m.inputTokens), 0) AS inputTokens,
        COALESCE(SUM(m.outputTokens), 0) AS outputTokens,
        COALESCE(SUM(CASE WHEN m.status = 'error' THEN 1 ELSE 0 END), 0) AS errors
        FROM messages m
        LEFT JOIN providers p ON p.id = m.providerId
        WHERE m.role = 'assistant' AND m.createdAt >= :from AND m.createdAt < :to AND m.modelId IS NOT NULL
        GROUP BY m.modelId, m.providerId, providerName
        ORDER BY (SUM(m.inputTokens) + SUM(m.outputTokens)) DESC""",
    )
    suspend fun analyticsByModel(from: Long, to: Long): List<ModelAnalytics>

    @Query(
        """SELECT CAST(strftime('%H', createdAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
        COUNT(*) AS answers
        FROM messages
        WHERE role = 'assistant' AND modelId IS NOT NULL AND createdAt >= :from AND createdAt < :to
        GROUP BY hour ORDER BY hour""",
    )
    suspend fun analyticsHourly(from: Long, to: Long): List<HourlyAnalytics>

    @Transaction
    suspend fun deleteConversationWithMessages(id: String) {
        deleteMessages(id)
        deleteConversation(id)
    }
}

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM mcp_servers ORDER BY createdAt")
    fun observeServers(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY createdAt")
    suspend fun servers(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun server(id: String): McpServerEntity?

    @Upsert
    suspend fun upsertServer(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteServer(id: String)

    @Query("SELECT * FROM skills ORDER BY name")
    fun observeSkills(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY name")
    suspend fun skills(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun skill(id: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE name = :name")
    suspend fun skillByName(name: String): SkillEntity?

    @Upsert
    suspend fun upsertSkill(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteSkill(id: String)

    @Query("SELECT * FROM installed_items ORDER BY installedAt DESC")
    fun observeInstalled(): Flow<List<InstalledItemEntity>>

    @Query("SELECT * FROM installed_items WHERE id = :id")
    suspend fun installed(id: String): InstalledItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstalled(item: InstalledItemEntity)

    @Query("DELETE FROM installed_items WHERE id = :id")
    suspend fun deleteInstalled(id: String)

    @Query("DELETE FROM skills WHERE installedItemId = :itemId")
    suspend fun deleteSkillsForItem(itemId: String)

    @Query("DELETE FROM mcp_servers WHERE installedItemId = :itemId")
    suspend fun deleteServersForItem(itemId: String)

    @Query("SELECT * FROM tool_prefs")
    fun observeToolPrefs(): Flow<List<ToolPrefEntity>>

    @Query("SELECT * FROM tool_prefs")
    suspend fun toolPrefs(): List<ToolPrefEntity>

    @Upsert
    suspend fun upsertToolPref(pref: ToolPrefEntity)
}

@Database(
    entities = [
        ProviderEntity::class, ModelEntity::class, ConversationEntity::class, MessageEntity::class,
        McpServerEntity::class, SkillEntity::class, InstalledItemEntity::class, ToolPrefEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providers(): ProviderDao
    abstract fun chats(): ChatDao
    abstract fun extensions(): ExtensionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "beluga-chat.db").build()
    }
}
