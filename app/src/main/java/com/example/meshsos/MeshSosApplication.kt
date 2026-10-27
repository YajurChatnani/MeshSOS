package com.example.meshsos

import android.app.Application
import com.example.meshsos.data.local.SosDatabase
import com.example.meshsos.data.preferences.DevicePreferences
import com.example.meshsos.data.repository.SosRepository
import com.example.meshsos.location.SosLocationManager
import com.example.meshsos.transport.TransportManager

class MeshSosApplication : Application() {

    lateinit var preferences: DevicePreferences
        private set

    lateinit var database: SosDatabase
        private set

    lateinit var transportManager: TransportManager
        private set

    lateinit var repository: SosRepository
        private set

    lateinit var locationManager: SosLocationManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        preferences = DevicePreferences(this)
        database = SosDatabase.getInstance(this)
        transportManager = TransportManager(this, preferences)
        repository = SosRepository(this, database.sosMessageDao(), transportManager, preferences)
        locationManager = SosLocationManager(this)
    }

    companion object {
        lateinit var instance: MeshSosApplication
            private set
    }
}
