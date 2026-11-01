package fit.bandwagon.core.data.di

import fit.bandwagon.core.data.remote.FirestoreRemoteDataSource
import fit.bandwagon.core.data.remote.RemoteDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteModule {
    @Binds
    @Singleton
    abstract fun remoteDataSource(impl: FirestoreRemoteDataSource): RemoteDataSource
}
