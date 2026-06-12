package de.folio.reader.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.folio.reader.data.epub.EpubParser
import de.folio.reader.data.local.BookDao
import de.folio.reader.data.local.FolioDatabase
import de.folio.reader.data.smb.SmbClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FolioDatabase =
        Room.databaseBuilder(context, FolioDatabase::class.java, "folio.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBookDao(db: FolioDatabase): BookDao = db.bookDao()

    @Provides
    @Singleton
    fun provideSmbClient(): SmbClient = SmbClient()

    @Provides
    @Singleton
    fun provideEpubParser(): EpubParser = EpubParser()
}
