package it.objectfinder.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ObjectEntity::class, PhotoEntity::class, IdentifierEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class ObjectDatabase : RoomDatabase() {
    abstract fun objectDao(): ObjectDao
    abstract fun photoDao(): PhotoDao
    abstract fun identifierDao(): IdentifierDao

    companion object {
        /** v1 → v2: colonna remoteUpdatedAt e indici su projectId/syncStatus. Nessuna perdita di dati. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE objects ADD COLUMN remoteUpdatedAt TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_objects_projectId ON objects(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_objects_syncStatus ON objects(syncStatus)")
            }
        }

        /** v2 → v3: tabella foto. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS object_photos (" +
                        "id TEXT NOT NULL PRIMARY KEY, objectId TEXT NOT NULL, localPath TEXT, " +
                        "storagePath TEXT, syncStatus TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_object_photos_objectId ON object_photos(objectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_object_photos_syncStatus ON object_photos(syncStatus)")
            }
        }

        /** v3 → v4: codici collegati (barcode, seriali). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS object_identifiers (" +
                        "id TEXT NOT NULL PRIMARY KEY, objectId TEXT NOT NULL, type TEXT NOT NULL, " +
                        "value TEXT NOT NULL, syncStatus TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_object_identifiers_objectId ON object_identifiers(objectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_object_identifiers_value ON object_identifiers(value)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_object_identifiers_syncStatus ON object_identifiers(syncStatus)")
            }
        }

        fun create(context: Context): ObjectDatabase =
            Room.databaseBuilder(context, ObjectDatabase::class.java, "object-finder.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
