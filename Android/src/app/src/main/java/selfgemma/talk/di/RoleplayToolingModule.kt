package selfgemma.talk.di

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.usecase.DefaultRoleplayToolOrchestrator
import selfgemma.talk.domain.roleplay.usecase.DeviceSystemTimeTool
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolHandler
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolOrchestrator

@Module
@InstallIn(SingletonComponent::class)
abstract class RoleplayToolingModule {
  @Binds
  @Singleton
  abstract fun bindRoleplayToolOrchestrator(
    implementation: DefaultRoleplayToolOrchestrator,
  ): RoleplayToolOrchestrator

  @Binds
  @IntoSet
  abstract fun bindDeviceSystemTimeTool(
    implementation: DeviceSystemTimeTool,
  ): RoleplayToolHandler
}
