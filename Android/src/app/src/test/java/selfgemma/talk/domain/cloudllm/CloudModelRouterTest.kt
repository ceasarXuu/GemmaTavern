package selfgemma.talk.domain.cloudllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudModelRouterTest {
  private val router = CloudModelRouter()

  @Test
  fun `routes to local when cloud is disabled`() {
    val decision = router.route(baseRequest(config = baseConfig().copy(enabled = false)))

    assertFalse(decision.usesCloud)
    assertEquals(CloudRouteReason.CLOUD_DISABLED, decision.reason)
  }

  @Test
  fun `routes to local when api key is missing`() {
    val decision = router.route(baseRequest(apiKeyAvailable = false))

    assertFalse(decision.usesCloud)
    assertEquals(CloudRouteReason.MISSING_API_KEY, decision.reason)
  }

  @Test
  fun `routes to local when network is unavailable`() {
    val decision = router.route(baseRequest(networkAvailable = false))

    assertFalse(decision.usesCloud)
    assertEquals(CloudRouteReason.OFFLINE, decision.reason)
  }

  @Test
  fun `routes to cloud when config key network and capability are available`() {
    val decision = router.route(baseRequest())

    assertTrue(decision.usesCloud)
    assertEquals(CloudRouteReason.CLOUD_READY, decision.reason)
  }

  @Test
  fun `routes to cloud with bridge when media capability is missing but bridge exists`() {
    val decision =
      router.route(
        baseRequest(
          capability = CloudModelCapability(supportsImageInput = false),
          requiredCapability = CloudRequiredCapability(imageInput = true),
        )
      )

    assertTrue(decision.usesCloud)
    assertTrue(decision.mediaBridgeRequired)
    assertEquals(CloudRouteReason.CLOUD_READY_WITH_MEDIA_BRIDGE, decision.reason)
  }

  @Test
  fun `routes to local when media capability is missing and bridge is unavailable`() {
    val decision =
      router.route(
        baseRequest(
          capability = CloudModelCapability(supportsImageInput = false),
          requiredCapability =
            CloudRequiredCapability(imageInput = true, localMediaBridgeAvailable = false),
        )
      )

    assertFalse(decision.usesCloud)
    assertEquals(CloudRouteReason.CAPABILITY_MISMATCH, decision.reason)
  }

  private fun baseRequest(
    config: CloudModelConfig = baseConfig(),
    apiKeyAvailable: Boolean = true,
    networkAvailable: Boolean = true,
    capability: CloudModelCapability = CloudModelCapability(supportsToolCalling = true),
    requiredCapability: CloudRequiredCapability = CloudRequiredCapability(),
  ): CloudRouteRequest {
    return CloudRouteRequest(
      config = config,
      apiKeyAvailable = apiKeyAvailable,
      networkAvailable = networkAvailable,
      capability = capability,
      requiredCapability = requiredCapability,
    )
  }

  private fun baseConfig(): CloudModelConfig {
    return CloudModelConfig(
      enabled = true,
      providerId = CloudProviderId.DEEPSEEK,
      modelName = "deepseek-v4-flash",
    )
  }
}
