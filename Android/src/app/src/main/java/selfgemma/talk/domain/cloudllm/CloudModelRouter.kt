package selfgemma.talk.domain.cloudllm

data class CloudRouteRequest(
  val config: CloudModelConfig,
  val apiKeyAvailable: Boolean,
  val networkAvailable: Boolean,
  val providerHealthy: Boolean = true,
  val capability: CloudModelCapability,
  val requiredCapability: CloudRequiredCapability = CloudRequiredCapability(),
)

data class CloudRequiredCapability(
  val imageInput: Boolean = false,
  val audioInput: Boolean = false,
  val toolCalling: Boolean = false,
  val localMediaBridgeAvailable: Boolean = true,
)

data class CloudRouteDecision(
  val target: CloudRouteTarget,
  val reason: CloudRouteReason,
  val mediaBridgeRequired: Boolean = false,
) {
  val usesCloud: Boolean
    get() = target == CloudRouteTarget.CLOUD
}

enum class CloudRouteTarget {
  CLOUD,
  LOCAL,
}

enum class CloudRouteReason {
  CLOUD_READY,
  CLOUD_READY_WITH_MEDIA_BRIDGE,
  CLOUD_DISABLED,
  MISSING_API_KEY,
  MISSING_MODEL_NAME,
  OFFLINE,
  PROVIDER_COOLDOWN,
  CAPABILITY_MISMATCH,
}

class CloudModelRouter {
  fun route(request: CloudRouteRequest): CloudRouteDecision {
    if (!request.config.enabled) {
      return local(CloudRouteReason.CLOUD_DISABLED)
    }
    if (request.config.modelName.isBlank()) {
      return local(CloudRouteReason.MISSING_MODEL_NAME)
    }
    if (!request.apiKeyAvailable) {
      return local(CloudRouteReason.MISSING_API_KEY)
    }
    if (!request.networkAvailable) {
      return local(CloudRouteReason.OFFLINE)
    }
    if (!request.providerHealthy) {
      return local(CloudRouteReason.PROVIDER_COOLDOWN)
    }
    if (request.requiredCapability.toolCalling && !request.capability.supportsToolCalling) {
      return local(CloudRouteReason.CAPABILITY_MISMATCH)
    }

    val mediaBridgeRequired = mediaBridgeRequired(request)
    if (mediaBridgeRequired == null) {
      return local(CloudRouteReason.CAPABILITY_MISMATCH)
    }
    return if (mediaBridgeRequired) {
      CloudRouteDecision(
        target = CloudRouteTarget.CLOUD,
        reason = CloudRouteReason.CLOUD_READY_WITH_MEDIA_BRIDGE,
        mediaBridgeRequired = true,
      )
    } else {
      CloudRouteDecision(target = CloudRouteTarget.CLOUD, reason = CloudRouteReason.CLOUD_READY)
    }
  }

  private fun mediaBridgeRequired(request: CloudRouteRequest): Boolean? {
    val needsImageBridge = request.requiredCapability.imageInput && !request.capability.supportsImageInput
    val needsAudioBridge = request.requiredCapability.audioInput && !request.capability.supportsAudioInput
    val needsBridge = needsImageBridge || needsAudioBridge
    if (!needsBridge) {
      return false
    }
    return if (request.requiredCapability.localMediaBridgeAvailable) true else null
  }

  private fun local(reason: CloudRouteReason): CloudRouteDecision {
    return CloudRouteDecision(target = CloudRouteTarget.LOCAL, reason = reason)
  }
}
