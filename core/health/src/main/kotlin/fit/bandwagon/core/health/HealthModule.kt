package fit.bandwagon.core.health

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fit.bandwagon.core.health.store.HealthStoreDao
import fit.bandwagon.core.health.store.HealthStoreDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthModule {
    @Binds
    @Singleton
    abstract fun healthDataSource(impl: HealthConnectSource): HealthDataSource

    companion object {
        @Provides
        @Singleton
        fun healthStore(@ApplicationContext context: Context): HealthStoreDatabase =
            Room.databaseBuilder(context, HealthStoreDatabase::class.java, "health_store.db")
                .fallbackToDestructiveMigration()
                .build()

        @Provides
        fun healthStoreDao(db: HealthStoreDatabase): HealthStoreDao = db.dao()
    }
}
