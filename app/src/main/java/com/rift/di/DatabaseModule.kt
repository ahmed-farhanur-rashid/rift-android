package com.rift.di

import android.content.Context
import androidx.room.Room
import com.rift.core.data.ApReadingDao
import com.rift.core.data.ScanPointDao
import com.rift.core.data.SessionDao
import com.rift.core.data.ThreatReportDao
import com.rift.core.data.WifiSourceDao
import com.rift.core.data.RiftDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RiftDatabase {
        return Room.databaseBuilder(
            context,
            RiftDatabase::class.java,
            "rift.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideSessionDao(db: RiftDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideScanPointDao(db: RiftDatabase): ScanPointDao = db.scanPointDao()

    @Provides
    fun provideApReadingDao(db: RiftDatabase): ApReadingDao = db.apReadingDao()

    @Provides
    fun provideThreatReportDao(db: RiftDatabase): ThreatReportDao = db.threatReportDao()

    @Provides
    fun provideWifiSourceDao(db: RiftDatabase): WifiSourceDao = db.wifiSourceDao()
}
