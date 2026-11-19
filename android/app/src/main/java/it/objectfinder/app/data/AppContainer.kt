package it.objectfinder.app.data

import android.content.Context

class AppContainer(context: Context) {
    private val database = ObjectDatabase.create(context)
    val repository = ObjectRepository(database.objectDao(), SupabaseApi(context))
}
