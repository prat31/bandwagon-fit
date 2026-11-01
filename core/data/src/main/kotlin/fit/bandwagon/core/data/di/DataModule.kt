package fit.bandwagon.core.data.di

import android.content.Context
import androidx.room.Room
import fit.bandwagon.core.data.local.DailyScoreDao
import fit.bandwagon.core.data.local.DailySummaryDao
import fit.bandwagon.core.data.local.BandwagonDatabase
import fit.bandwagon.core.data.local.GoalDao
import fit.bandwagon.core.data.local.MIGRATION_2_3
import fit.bandwagon.core.data.local.LocalDailySummaryStore
import fit.bandwagon.core.data.local.RoomDailySummaryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BandwagonDatabase =
        Room.databaseBuilder(context, BandwagonDatabase::class.java, "bandwagon.db")
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun dailySummaryDao(database: BandwagonDatabase): DailySummaryDao = database.dailySummaryDao()

    @Provides
    fun dailyScoreDao(database: BandwagonDatabase): DailyScoreDao = database.dailyScoreDao()

    @Provides
    fun goalDao(database: BandwagonDatabase): GoalDao = database.goalDao()

    @Provides
    @Singleton
    fun localStore(dao: DailySummaryDao): LocalDailySummaryStore = RoomDailySummaryStore(dao)

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()
}
