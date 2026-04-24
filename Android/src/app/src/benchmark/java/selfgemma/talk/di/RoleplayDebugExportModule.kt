package selfgemma.talk.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import selfgemma.talk.data.roleplay.repository.AndroidRoleplayDebugExportRepository
import selfgemma.talk.domain.roleplay.repository.RoleplayDebugExportRepository
import selfgemma.talk.domain.roleplay.usecase.AndroidRoleplayDebugBundleExportLauncher
import selfgemma.talk.domain.roleplay.usecase.RoleplayDebugBundleExportLauncher

@Module
@InstallIn(SingletonComponent::class)
abstract class RoleplayDebugExportModule {
  @Binds
  @Singleton
  abstract fun bindRoleplayDebugExportRepository(
    implementation: AndroidRoleplayDebugExportRepository,
  ): RoleplayDebugExportRepository

  @Binds
  @Singleton
  abstract fun bindRoleplayDebugBundleExportLauncher(
    implementation: AndroidRoleplayDebugBundleExportLauncher,
  ): RoleplayDebugBundleExportLauncher
}
