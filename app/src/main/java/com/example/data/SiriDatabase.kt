package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "voice_commands")
data class VoiceCommandLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandText: String,
    val detectedIntent: String,
    val executionResult: String,
    val timestamp: Long = System.currentTimeMillis(),
    val successfullyExecuted: Boolean
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String, // "user" or "siri"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "notification_logs")
data class NotificationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appName: String,
    val packageName: String,
    val senderName: String,
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface SiriDao {
    @Query("SELECT * FROM voice_commands ORDER BY timestamp DESC LIMIT 100")
    fun getAllCommandLogs(): Flow<List<VoiceCommandLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommandLog(log: VoiceCommandLog): Long

    @Query("DELETE FROM voice_commands")
    suspend fun clearCommandLogs()

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllChatMessages(): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessage): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatMessages()

    @Query("SELECT * FROM notification_logs ORDER BY timestamp DESC LIMIT 100")
    fun getAllNotificationLogs(): Flow<List<NotificationLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationLog(log: NotificationLog): Long

    @Query("DELETE FROM notification_logs")
    suspend fun clearNotificationLogs()

    // --- HEALTH TRACKING QUERIES ---
    @Query("SELECT * FROM daily_health_stats ORDER BY date DESC LIMIT 30")
    fun getAllWeeklyHealthStats(): Flow<List<DailyHealthStats>>

    @Query("SELECT * FROM daily_health_stats WHERE date = :date")
    suspend fun getHealthStatsForDate(date: String): DailyHealthStats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHealthStats(stats: DailyHealthStats)

    @Query("SELECT * FROM location_points ORDER BY timestamp DESC LIMIT 100")
    fun getRecentLocationPoints(): Flow<List<LocationPoint>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationPoint(point: LocationPoint)

    @Query("DELETE FROM location_points")
    suspend fun clearLocationPoints()
}

@Entity(tableName = "daily_health_stats")
data class DailyHealthStats(
    @PrimaryKey val date: String, // format "yyyy-MM-dd"
    val steps: Int,
    val distanceKm: Double,
    val walkingTimeMinutes: Int,
    val caloriesBurned: Int
)

@Entity(tableName = "location_points")
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Database(entities = [VoiceCommandLog::class, ChatMessage::class, NotificationLog::class, DailyHealthStats::class, LocationPoint::class], version = 3, exportSchema = false)
abstract class SiriDatabase : RoomDatabase() {
    abstract val siriDao: SiriDao
}

