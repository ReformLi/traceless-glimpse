package com.hpu.browseguard.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DownloadRecord::class], version = 5, exportSchema = false)
@TypeConverters(RecordConverters::class)
abstract class BrowseGuardDatabase : RoomDatabase() {
    abstract fun downloadRecords(): DownloadRecordDao

    companion object {
        /**
         * v1 → v2：加容量与进度四列。
         *
         * 老记录一个都不丢——下载管理页现在把历史当作文件清单来管，
         * 清库等于把用户手机里已经下好的视频从列表里抹掉，而文件还留在盘上。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_records ADD COLUMN sizeBytes INTEGER")
                db.execSQL("ALTER TABLE download_records ADD COLUMN downloadedBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_records ADD COLUMN bytesTotal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_records ADD COLUMN speedBps INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 → v3：给失败原因留一句人话。
         *
         * 本机失败没有 §5 码（不借协议码），下载管理页要能解释「为什么没成」就只能存文本；
         * 引擎失败照样有码，这一列存的是引擎自己写的那句 message。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_records ADD COLUMN errorMessage TEXT")
            }
        }

        /**
         * v3 → v4：给**成功**的产物留一句提示键。
         *
         * 转封装降级（产物是 `.ts`）不是失败，但也不能当普通成功报出去——主页瘦身后
         * 唯一能长期说这句话的地方就是下载页那一行，所以这个事实必须进库，不能只留在界面上。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_records ADD COLUMN note TEXT")
            }
        }

        /**
         * v4 → v5：给产物记下「看到哪儿了」。
         *
         * 断点续播是个事实，不是界面状态：进程被杀、划掉任务栏、明天再看都得接得上，
         * 所以这两列进库而不是留在播放器里。默认 0 = 没看过，老记录一条都不必重下。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_records ADD COLUMN lastPlayedPositionMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_records ADD COLUMN mediaDurationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): BrowseGuardDatabase =
            Room.databaseBuilder(context, BrowseGuardDatabase::class.java, "browseguard.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
