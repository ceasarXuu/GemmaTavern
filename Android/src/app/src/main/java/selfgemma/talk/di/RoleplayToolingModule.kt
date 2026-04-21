package selfgemma.talk.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.usecase.NoOpRoleplayToolOrchestrator
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolOrchestrator

@Module
@InstallIn(SingletonComponent::class)
abstract class RoleplayToolingModule {
  @Binds
  @Singleton
  abstract fun bindRoleplayToolOrchestrator(
    implementation: NoOpRoleplayToolOrchestrator,
  ): RoleplayToolOrchestrator
}
