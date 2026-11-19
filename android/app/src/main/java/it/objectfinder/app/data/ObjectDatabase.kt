package it.objectfinder.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ObjectEntity::class], version = 1, exportSchema = false)
abstract class ObjectDatabase : RoomDatabase() {
    abstract fun objectDao(): ObjectDao

    companion object {
        fun create(context: Context): ObjectDatabase =
            Room.databaseBuilder(context, ObjectDatabase::class.java, "object-finder.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
