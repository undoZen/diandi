package com.mianbizhe.diandiji.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NotificationEntity::class, SpendingEntity::class, CorrectionEntity::class],
    version = 2,
    exportSchema = true, // schema JSON 输出到 app/schemas/，随代码入库
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun spendingDao(): SpendingDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1→v2：纯增量（spending / category_correction 两张新表 + 索引），
         * notification_history 不动。SQL 与 KSP 生成的 schemas/2.json createSql 一致。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `spending` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`notificationId` INTEGER NOT NULL, " +
                        "`sourcePackage` TEXT NOT NULL, " +
                        "`txTime` INTEGER NOT NULL, " +
                        "`amountCents` INTEGER NOT NULL, " +
                        "`merchant` TEXT, " +
                        "`channel` TEXT, " +
                        "`rawText` TEXT NOT NULL, " +
                        "`category` TEXT, " +
                        "`confidence` REAL NOT NULL, " +
                        "`uncertain` INTEGER NOT NULL, " +
                        "`corrected` INTEGER NOT NULL, " +
                        "`hidden` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spending_txTime` ON `spending` (`txTime`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spending_notificationId` ON `spending` (`notificationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spending_amountCents_txTime` ON `spending` (`amountCents`, `txTime`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `category_correction` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`spendingId` INTEGER NOT NULL, " +
                        "`merchant` TEXT, " +
                        "`rawText` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "diandi.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
