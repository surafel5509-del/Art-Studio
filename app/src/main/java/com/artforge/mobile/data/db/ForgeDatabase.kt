package com.artforge.mobile.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.artforge.mobile.core.model.ProjectKind
import kotlinx.coroutines.flow.Flow

/** Metadata row for a project; pixels live in the project directory, not the DB. */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val layerCount: Int,
    val createdAt: Long,
    val modifiedAt: Long,
    val lastOpenedAt: Long,
    val thumbnailPath: String?,
    val filePath: String?,
    val favorite: Boolean = false,
    val archived: Boolean = false,
)

@Entity(tableName = "recovery")
data class RecoveryEntity(
    @PrimaryKey val projectId: String,
    val savedAt: Long,
    val documentJson: String,
    val autoSave: Boolean = true,
)

@Entity(tableName = "versions")
data class VersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val savedAt: Long,
    val label: String,
    val snapshotPath: String,
    val sizeBytes: Long,
)

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val tags: String,
    val filePath: String,
    val previewPath: String?,
    val favorite: Boolean = false,
    val importedAt: Long,
    val builtIn: Boolean = false,
    val folder: String = "Imported",
)

@Entity(tableName = "brushes")
data class BrushEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val userCreated: Boolean = false,
    val updatedAt: Long = 0L,
)

@Entity(tableName = "palettes")
data class PaletteEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val updatedAt: Long = 0L,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY lastOpenedAt DESC")
    fun observeRecent(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY modifiedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE favorite = 1 AND archived = 0 ORDER BY title")
    fun observeFavorites(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun findById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE title LIKE '%' || :query || '%' AND archived = 0 ORDER BY modifiedAt DESC")
    suspend fun search(query: String): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE kind = :kind AND archived = 0 ORDER BY modifiedAt DESC")
    suspend fun byKind(kind: String): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: String, time: Long)

    @Query("UPDATE projects SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE projects SET title = :title, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, modifiedAt: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE projects SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)
}

@Dao
interface RecoveryDao {
    @Query("SELECT * FROM recovery WHERE projectId = :projectId")
    suspend fun find(projectId: String): RecoveryEntity?

    @Query("SELECT * FROM recovery ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<RecoveryEntity>>

    /** One-shot snapshot for background maintenance. */
    @Query("SELECT * FROM recovery")
    suspend fun listAll(): List<RecoveryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: RecoveryEntity)

    @Query("DELETE FROM recovery WHERE projectId = :projectId")
    suspend fun clear(projectId: String)
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM versions WHERE projectId = :projectId ORDER BY savedAt DESC")
    suspend fun list(projectId: String): List<VersionEntity>

    @Insert
    suspend fun insert(entity: VersionEntity): Long

    @Query("DELETE FROM versions WHERE projectId = :projectId AND id NOT IN (SELECT id FROM versions WHERE projectId = :projectId ORDER BY savedAt DESC LIMIT :keep)")
    suspend fun prune(projectId: String, keep: Int)

    @Query("DELETE FROM versions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE category = :category ORDER BY name")
    suspend fun byCategory(category: String): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<AssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: AssetEntity)

    @Query("UPDATE assets SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("DELETE FROM assets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BrushDao {
    @Query("SELECT * FROM brushes")
    fun observeAll(): Flow<List<BrushEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(brush: BrushEntity)

    @Query("DELETE FROM brushes WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PaletteDao {
    @Query("SELECT * FROM palettes")
    fun observeAll(): Flow<List<PaletteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(palette: PaletteEntity)

    @Query("DELETE FROM palettes WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        ProjectEntity::class,
        RecoveryEntity::class,
        VersionEntity::class,
        AssetEntity::class,
        BrushEntity::class,
        PaletteEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun recoveryDao(): RecoveryDao
    abstract fun versionDao(): VersionDao
    abstract fun assetDao(): AssetDao
    abstract fun brushDao(): BrushDao
    abstract fun paletteDao(): PaletteDao

    companion object {
        @Volatile
        private var instance: ForgeDatabase? = null

        fun get(context: Context): ForgeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ForgeDatabase::class.java,
                "artforge.db",
            )
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { instance = it }
        }
    }
}

fun ProjectKind.dbName(): String = name
