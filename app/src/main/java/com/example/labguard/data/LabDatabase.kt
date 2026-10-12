
package com.example.labguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LabRecord::class],
    version = 2,
    exportSchema = false
)
abstract class LabDatabase : RoomDatabase() {

    abstract fun labRecordDao(): LabRecordDao

    companion object {
        @Volatile
        private var INSTANCE: LabDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE lab_records " +
                            "ADD COLUMN faceShield INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE lab_records " +
                            "ADD COLUMN mask INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): LabDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LabDatabase::class.java,
                    "labguard_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
