package fit.bandwagon

import fit.bandwagon.core.model.AppEnvironment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun appEnvironment() = AppEnvironment(useEmulators = BuildConfig.USE_FIREBASE_EMULATORS)
}
