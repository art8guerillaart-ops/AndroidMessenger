package com.example.messenger.data.signal

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocalIdentityEntity::class,
        RemoteIdentityEntity::class,
        PreKeyEntity::class,
        SignedPreKeyEntity::class,
        SessionEntity::class,
    ],
    version = 2, // добавлены nextPreKeyId/currentSignedPreKeyId в LocalIdentityEntity
    exportSchema = false
)
abstract class SignalDatabase : RoomDatabase() {
    abstract fun signalKeyDao(): SignalKeyDao

    companion object {
        @Volatile private var instance: SignalDatabase? = null

        fun getInstance(context: Context): SignalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SignalDatabase::class.java,
                    "signal_store.db"
                )
                    // Фича ещё в разработке, релизных пользователей с этой БД нет —
                    // при смене схемы проще пересоздать локальный ключевой стор
                    // (это лишь копия наших собственных ключей и сессий), чем писать
                    // миграции на каждый шаг.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
