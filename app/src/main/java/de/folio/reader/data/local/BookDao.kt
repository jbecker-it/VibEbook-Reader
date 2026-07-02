package de.folio.reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(book: BookEntity)

    @Query("UPDATE books SET downloaded = :downloaded, spineJson = :spineJson, coverPath = :cover, title = :title, author = :author WHERE id = :id")
    suspend fun markDownloaded(id: String, downloaded: Boolean, spineJson: String, cover: String?, title: String, author: String)

    @Query("UPDATE books SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM books WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<String>)
}
