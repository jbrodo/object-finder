package it.objectfinder.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ObjectEntity::class], version = 2, exportSchema = false)
abstract class ObjectDatabase : RoomDatabase() {
    abstract fun objectDao(): ObjectDao

    companion object {
        /** v1 → v2: colonna remoteUpdatedAt e indici su projectId/syncStatus. Nessuna perdita di dati. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE objects ADD COLUMN remoteUpdatedAt TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_objects_projectId ON objects(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_objects_syncStatus ON objects(syncStatus)")
            }
        }

        fun create(context: Context): ObjectDatabase =
            Room.databaseBuilder(context, ObjectDatabase::class.java, "object-finder.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
