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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * App-weiter CoroutineScope, der ViewModels überlebt – z.B. für das Speichern
 * der Leseposition beim Schließen eines Buches (viewModelScope würde dabei
 * abgebrochen und der Schreibvorgang ginge verloren).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FolioDatabase =
        Room.databaseBuilder(context, FolioDatabase::class.java, "folio.db")
            .addMigrations(FolioDatabase.MIGRATION_1_2)
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
