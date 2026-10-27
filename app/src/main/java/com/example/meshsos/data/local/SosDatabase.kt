package com.example.meshsos.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SosMessageEntity::class,
        MeshPeerDeliveryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class SosDatabase : RoomDatabase() {

    abstract fun sosMessageDao(): SosMessageDao

    companion object {
        @Volatile
        private var INSTANCE: SosDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sos_messages ADD COLUMN senderName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sos_messages ADD COLUMN isAcknowledged INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sos_messages ADD COLUMN acknowledgedAt INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sos_messages_isAcknowledged ON sos_messages(isAcknowledged)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sos_messages ADD COLUMN originDeviceId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sos_messages ADD COLUMN hopLimit INTEGER NOT NULL DEFAULT 5")
                db.execSQL("CREATE TABLE IF NOT EXISTS mesh_peer_deliveries (messageId TEXT NOT NULL, peerId TEXT NOT NULL, deliveredAt INTEGER NOT NULL, PRIMARY KEY(messageId, peerId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mesh_peer_deliveries_messageId ON mesh_peer_deliveries(messageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mesh_peer_deliveries_peerId ON mesh_peer_deliveries(peerId)")
            }
        }

        fun getInstance(context: Context): SosDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SosDatabase::class.java,
                    "offgrid_sos.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                 .fallbackToDestructiveMigration()
                 .build()
                 .also { INSTANCE = it }
            }
        }
    }
}
