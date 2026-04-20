package selfgemma.talk.data.roleplay.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import selfgemma.talk.data.roleplay.db.converter.RoleplayConverters
import selfgemma.talk.data.roleplay.db.dao.CompactionCacheDao
import selfgemma.talk.data.roleplay.db.dao.MemoryDao
import selfgemma.talk.data.roleplay.db.dao.MemoryAtomDao
import selfgemma.talk.data.roleplay.db.dao.MessageDao
import selfgemma.talk.data.roleplay.db.dao.OpenThreadDao
import selfgemma.talk.data.roleplay.db.dao.RoleDao
import selfgemma.talk.data.roleplay.db.dao.RuntimeStateSnapshotDao
import selfgemma.talk.data.roleplay.db.dao.SessionDao
import selfgemma.talk.data.roleplay.db.dao.SessionEventDao
import selfgemma.talk.data.roleplay.db.dao.SessionSummaryDao
import selfgemma.talk.data.roleplay.db.entity.CompactionCacheEntity
import selfgemma.talk.data.roleplay.db.entity.MemoryAtomEntity
import selfgemma.talk.data.roleplay.db.entity.MemoryAtomFtsEntity
import selfgemma.talk.data.roleplay.db.entity.MemoryEntity
import selfgemma.talk.data.roleplay.db.entity.MessageEntity
import selfgemma.talk.data.roleplay.db.entity.OpenThreadEntity
import selfgemma.talk.data.roleplay.db.entity.RoleEntity
import selfgemma.talk.data.roleplay.db.entity.RuntimeStateSnapshotEntity
import selfgemma.talk.data.roleplay.db.entity.SessionEntity
import selfgemma.talk.data.roleplay.db.entity.SessionEventEntity
import selfgemma.talk.data.roleplay.db.entity.SessionSummaryEntity

@Database(
  entities = [
    RoleEntity::class,
    SessionEntity::class,
    MessageEntity::class,
    SessionSummaryEntity::class,
    MemoryEntity::class,
    SessionEventEntity::class,
    RuntimeStateSnapshotEntity::class,
    MemoryAtomEntity::class,
    MemoryAtomFtsEntity::class,
    OpenThreadEntity::class,
    CompactionCacheEntity::class,
  ],
  version = 8,
  exportSchema = true,
)
@TypeConverters(RoleplayConverters::class)
abstract class RoleplayDatabase : RoomDatabase() {
  abstract fun roleDao(): RoleDao

  abstract fun sessionDao(): SessionDao

  abstract fun messageDao(): MessageDao

  abstract fun sessionSummaryDao(): SessionSummaryDao

  abstract fun memoryDao(): MemoryDao

  abstract fun sessionEventDao(): SessionEventDao

  abstract fun runtimeStateSnapshotDao(): RuntimeStateSnapshotDao

  abstract fun memoryAtomDao(): MemoryAtomDao

  abstract fun openThreadDao(): OpenThreadDao

  abstract fun compactionCacheDao(): CompactionCacheDao
}
