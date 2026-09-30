package com.echotracks.app.data

import androidx.room.*

@Entity(tableName = "echo_tx")
data class EchoEntity(
    @PrimaryKey val code: String,
    val amount: Double, val who: String, val date: Long,
    val source: String, val direction: String,
    val category: String, val spendType: String, val raw: String
)

@Dao interface EchoDao {
    @Query("SELECT * FROM echo_tx ORDER BY date DESC LIMIT 10000")
    suspend fun all(): List<EchoEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<EchoEntity>)
    @Query("DELETE FROM echo_tx") suspend fun clear()
}

@Database(entities = [EchoEntity::class], version = 1, exportSchema = false)
abstract class EchoDb : RoomDatabase() { abstract fun dao(): EchoDao }
