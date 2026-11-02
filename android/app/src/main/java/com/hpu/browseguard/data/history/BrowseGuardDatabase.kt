package com.hpu.browseguard.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [DownloadRecord::class], version = 1, exportSchema = false)
@TypeConverters(RecordConverters::class)
abstract class BrowseGuardDatabase : RoomDatabase() {
    abstract fun downloadRecords(): DownloadRecordDao

    companion object {
        fun build(context: Context): BrowseGuardDatabase =
            Room.databaseBuilder(context, BrowseGuardDatabase::class.java, "browseguard.db").build()
    }
}
