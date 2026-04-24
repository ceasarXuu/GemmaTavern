package selfgemma.talk.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import selfgemma.talk.data.roleplay.db.RoleplayDatabase
import selfgemma.talk.data.roleplay.db.RoleplayDatabaseMigrations
import selfgemma.talk.data.roleplay.db.dao.CompactionCacheDao
import selfgemma.talk.data.roleplay.db.dao.ExternalFactDao
import selfgemma.talk.data.roleplay.db.dao.MemoryDao
import selfgemma.talk.data.roleplay.db.dao.MemoryAtomDao
import selfgemma.talk.data.roleplay.db.dao.MessageDao
import selfgemma.talk.data.roleplay.db.dao.OpenThreadDao
import selfgemma.talk.data.roleplay.db.dao.RoleDao
import selfgemma.talk.data.roleplay.db.dao.RuntimeStateSnapshotDao
import selfgemma.talk.data.roleplay.db.dao.SessionDao
import selfgemma.talk.data.roleplay.db.dao.SessionEventDao
import selfgemma.talk.data.roleplay.db.dao.SessionSummaryDao
import selfgemma.talk.data.roleplay.db.dao.ToolInvocationDao
import selfgemma.talk.data.roleplay.repository.RoomConversationRepository
import selfgemma.talk.data.roleplay.repository.AndroidRoleplayInteropDocumentRepository
import selfgemma.talk.data.roleplay.repository.RoomCompactionCacheRepository
import selfgemma.talk.data.roleplay.repository.RoomExternalFactRepository
import selfgemma.talk.data.roleplay.repository.RoomMemoryAtomRepository
import selfgemma.talk.data.roleplay.repository.RoomMemoryRepository
import selfgemma.talk.data.roleplay.repository.RoomOpenThreadRepository
import selfgemma.talk.data.roleplay.repository.RoomRoleRepository
import selfgemma.talk.data.roleplay.repository.RoomRuntimeStateRepository
import selfgemma.talk.data.roleplay.repository.RoomToolInvocationRepository
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.ExternalFactRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository
import selfgemma.talk.domain.roleplay.usecase.AndroidRoleplaySeedCatalog
import selfgemma.talk.domain.roleplay.usecase.RoleplaySeedCatalog

private const val ROLEPLAY_DATABASE_NAME = "selfgemma_talk.db"

@Module
@InstallIn(SingletonComponent::class)
object RoleplayDatabaseModule {
  @Provides
  @Singleton
  fun provideRoleplayDatabase(@ApplicationContext context: Context): RoleplayDatabase {
    return Room.databaseBuilder(context, RoleplayDatabase::class.java, ROLEPLAY_DATABASE_NAME)
      .addMigrations(RoleplayDatabaseMigrations.MIGRATION_9_10)
      .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
      .build()
  }

  @Provides
  fun provideRoleDao(database: RoleplayDatabase): RoleDao {
    return database.roleDao()
  }

  @Provides
  fun provideSessionDao(database: RoleplayDatabase): SessionDao {
    return database.sessionDao()
  }

  @Provides
  fun provideMessageDao(database: RoleplayDatabase): MessageDao {
    return database.messageDao()
  }

  @Provides
  fun provideSessionSummaryDao(database: RoleplayDatabase): SessionSummaryDao {
    return database.sessionSummaryDao()
  }

  @Provides
  fun provideMemoryDao(database: RoleplayDatabase): MemoryDao {
    return database.memoryDao()
  }

  @Provides
  fun provideSessionEventDao(database: RoleplayDatabase): SessionEventDao {
    return database.sessionEventDao()
  }

  @Provides
  fun provideRuntimeStateSnapshotDao(database: RoleplayDatabase): RuntimeStateSnapshotDao {
    return database.runtimeStateSnapshotDao()
  }

  @Provides
  fun provideMemoryAtomDao(database: RoleplayDatabase): MemoryAtomDao {
    return database.memoryAtomDao()
  }

  @Provides
  fun provideOpenThreadDao(database: RoleplayDatabase): OpenThreadDao {
    return database.openThreadDao()
  }

  @Provides
  fun provideCompactionCacheDao(database: RoleplayDatabase): CompactionCacheDao {
    return database.compactionCacheDao()
  }

  @Provides
  fun provideToolInvocationDao(database: RoleplayDatabase): ToolInvocationDao {
    return database.toolInvocationDao()
  }

  @Provides
  fun provideExternalFactDao(database: RoleplayDatabase): ExternalFactDao {
    return database.externalFactDao()
  }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RoleplayRepositoryModule {
  @Binds
  @Singleton
  abstract fun bindConversationRepository(
    implementation: RoomConversationRepository
  ): ConversationRepository

  @Binds
  @Singleton
  abstract fun bindRoleRepository(implementation: RoomRoleRepository): RoleRepository

  @Binds
  @Singleton
  abstract fun bindMemoryRepository(implementation: RoomMemoryRepository): MemoryRepository

  @Binds
  @Singleton
  abstract fun bindRuntimeStateRepository(
    implementation: RoomRuntimeStateRepository
  ): RuntimeStateRepository

  @Binds
  @Singleton
  abstract fun bindMemoryAtomRepository(
    implementation: RoomMemoryAtomRepository
  ): MemoryAtomRepository

  @Binds
  @Singleton
  abstract fun bindOpenThreadRepository(
    implementation: RoomOpenThreadRepository
  ): OpenThreadRepository

  @Binds
  @Singleton
  abstract fun bindCompactionCacheRepository(
    implementation: RoomCompactionCacheRepository
  ): CompactionCacheRepository

  @Binds
  @Singleton
  abstract fun bindRoleplayInteropDocumentRepository(
    implementation: AndroidRoleplayInteropDocumentRepository
  ): RoleplayInteropDocumentRepository

  @Binds
  @Singleton
  abstract fun bindRoleplaySeedCatalog(
    implementation: AndroidRoleplaySeedCatalog
  ): RoleplaySeedCatalog

  @Binds
  @Singleton
  abstract fun bindToolInvocationRepository(
    implementation: RoomToolInvocationRepository,
  ): ToolInvocationRepository

  @Binds
  @Singleton
  abstract fun bindExternalFactRepository(
    implementation: RoomExternalFactRepository,
  ): ExternalFactRepository
}
