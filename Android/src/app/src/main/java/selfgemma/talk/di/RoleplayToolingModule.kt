package selfgemma.talk.di

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.usecase.ApproximateLocationTool
import selfgemma.talk.domain.roleplay.usecase.CalendarSnapshotTool
import selfgemma.talk.domain.roleplay.usecase.DefaultRoleplayToolOrchestrator
import selfgemma.talk.domain.roleplay.usecase.DeviceBatteryStatusTool
import selfgemma.talk.domain.roleplay.usecase.DeviceContextTool
import selfgemma.talk.domain.roleplay.usecase.DeviceNetworkStatusTool
import selfgemma.talk.domain.roleplay.usecase.DeviceNextAlarmTool
import selfgemma.talk.domain.roleplay.usecase.DeviceSystemTimeTool
import selfgemma.talk.domain.roleplay.usecase.PlaceLookupTool
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolOrchestrator
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolProviderFactory
import selfgemma.talk.domain.roleplay.usecase.WeatherTool
import selfgemma.talk.domain.roleplay.usecase.WikipediaQueryTool

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
  ): RoleplayToolProviderFactory

  @Binds
  @IntoSet
  abstract fun bindDeviceBatteryStatusTool(
    implementation: DeviceBatteryStatusTool,
  ): RoleplayToolProviderFactory

  @Binds
  @IntoSet
  abstract fun bindDeviceNetworkStatusTool(
    implementation: DeviceNetworkStatusTool,
  ): RoleplayToolProviderFactory

  @Binds
  @IntoSet
  abstract fun bindDeviceContextTool(
    implementation: DeviceContextTool,
  ): RoleplayToolProviderFactory

  @Binds
  @IntoSet
  abstract fun bindDeviceNextAlarmTool(
    implementation: DeviceNextAlarmTool,
  ): RoleplayToolProviderFactory

  @Binds
  @IntoSet
  abstract fun bindApproximateLocationTool(
    implementation: ApproximateLocationTool,
  ): RoleplayToolProviderFactory

  @Binds
  @IntoSet
  abstract fun bindCalendarSnapshotTool(
    implementation: CalendarSnapshotTool,
  ): RoleplayToolProviderFactory

  @Binds
  @IntoSet
  abstract fun bindWikipediaQueryTool(
    implementation: WikipediaQueryTool,
  ): RoleplayToolProviderFactory

  @Binds
  @IntoSet
  abstract fun bindWeatherTool(
    implementation: WeatherTool,
  ): RoleplayToolProviderFactory

  @Binds
  @IntoSet
  abstract fun bindPlaceLookupTool(
    implementation: PlaceLookupTool,
  ): RoleplayToolProviderFactory
}
