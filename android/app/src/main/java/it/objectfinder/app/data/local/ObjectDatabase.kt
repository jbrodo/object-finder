package it.objectfinder.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ObjectEntity::class, PhotoEntity::class, IdentifierEntity::class,
        LocationHistoryEntity::class, ObjectConflictEntity::class, PhotoEmbeddingEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class ObjectDatabase : RoomDatabase() {
    abstract fun objectDao(): ObjectDao
    abstract fun photoDao(): PhotoDao
    abstract fun identifierDao(): IdentifierDao
    abstract fun locationHistoryDao(): LocationHistoryDao
    abstract fun conflictDao(): ObjectConflictDao
    abstract fun photoEmbeddingDao(): PhotoEmbeddingDao

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

        /** v4 → v5: storico posizioni; ogni oggetto con coordinate riceve la posizione attuale come primo punto. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS location_history (" +
                        "id TEXT NOT NULL PRIMARY KEY, objectId TEXT NOT NULL, latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, accuracyMeters REAL, recordedAt INTEGER NOT NULL, " +
                        "source TEXT NOT NULL, syncStatus TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_history_objectId ON location_history(objectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_history_syncStatus ON location_history(syncStatus)")
                // Id deterministico dall'id oggetto: SQLite non ha UUID; il formato resta un UUID valido.
                db.execSQL(
                    "INSERT INTO location_history (id, objectId, latitude, longitude, accuracyMeters, recordedAt, source, syncStatus) " +
                        "SELECT substr(id, 1, 14) || '5' || substr(id, 16), id, latitude, longitude, accuracyMeters, " +
                        "updatedAt, 'phone', 'pending_upload' FROM objects WHERE latitude IS NOT NULL AND longitude IS NOT NULL"
                )
            }
        }

        /** v5 → v6: versione remota (concorrenza ottimistica) e conflitti da risolvere. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE objects ADD COLUMN remoteVersion INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS object_conflicts (" +
                        "objectId TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, serverJson TEXT, detectedAt INTEGER NOT NULL)"
                )
            }
        }

        /** v6 → v7: campo "Dove" (descrizione libera del punto). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE objects ADD COLUMN place TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v7 → v8: origine della posizione (GPS o indicata a mano sulla mappa). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE objects ADD COLUMN locationSource TEXT NOT NULL DEFAULT 'gps'")
            }
        }

        /** v8 → v9: impronte delle foto per il riconoscimento visivo (solo locali). */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS photo_embeddings (" +
                        "photoId TEXT NOT NULL PRIMARY KEY, objectId TEXT NOT NULL, vector BLOB NOT NULL, " +
                        "model TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )
            }
        }

        /** v9 → v10: prestiti (a chi, da quando, scadenza). */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE objects ADD COLUMN loanedTo TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE objects ADD COLUMN loanedAt INTEGER")
                db.execSQL("ALTER TABLE objects ADD COLUMN loanDue TEXT")
            }
        }

        fun create(context: Context): ObjectDatabase =
            Room.databaseBuilder(context, ObjectDatabase::class.java, "object-finder.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
    }
}
