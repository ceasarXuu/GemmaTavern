package selfgemma.talk.data.roleplay.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object RoleplayDatabaseMigrations {
  val MIGRATION_9_10 =
    object : Migration(9, 10) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `external_facts` (
            `id` TEXT NOT NULL,
            `sessionId` TEXT NOT NULL,
            `turnId` TEXT NOT NULL,
            `toolInvocationId` TEXT,
            `sourceToolName` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `factKey` TEXT NOT NULL,
            `factType` TEXT NOT NULL,
            `structuredValueJson` TEXT,
            `ephemeral` INTEGER NOT NULL,
            `summaryEligible` INTEGER NOT NULL,
            `capturedAt` INTEGER NOT NULL,
            `freshnessTtlMillis` INTEGER,
            `expiresAt` INTEGER,
            `confidence` REAL NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
          )
          """.trimIndent(),
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_external_facts_sessionId` ON `external_facts` (`sessionId`)",
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_external_facts_sessionId_turnId` ON `external_facts` (`sessionId`, `turnId`)",
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_external_facts_sessionId_capturedAt` ON `external_facts` (`sessionId`, `capturedAt`)",
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_external_facts_sessionId_factKey` ON `external_facts` (`sessionId`, `factKey`)",
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_external_facts_toolInvocationId` ON `external_facts` (`toolInvocationId`)",
        )
      }
    }
}
