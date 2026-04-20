/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package selfgemma.talk.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import selfgemma.talk.common.cleanUpMediapipeTaskErrorMessage
import selfgemma.talk.data.Accelerator
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.DEFAULT_MAX_TOKEN
import selfgemma.talk.data.DEFAULT_TEMPERATURE
import selfgemma.talk.data.DEFAULT_TOPK
import selfgemma.talk.data.DEFAULT_TOPP
import selfgemma.talk.data.DEFAULT_VISION_ACCELERATOR
import selfgemma.talk.data.Model
import selfgemma.talk.runtime.CleanUpListener
import selfgemma.talk.runtime.LlmModelHelper
import selfgemma.talk.runtime.ResultListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGLlmChatModelHelper"

data class LlmConversationSessionConfig(
  val systemInstructionText: String = "",
  val tools: List<ToolProvider> = listOf(),
  val enableConversationConstrainedDecoding: Boolean = false,
)

data class LlmModelInstance(
  val engine: Engine,
  var conversation: Conversation,
  var sessionConfig: LlmConversationSessionConfig = LlmConversationSessionConfig(),
  val supportImage: Boolean = false,
  val supportAudio: Boolean = false,
)

object LlmChatModelHelper : LlmModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun initialize(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val visionAccelerator =
      model.getStringConfigValue(
        key = ConfigKeys.VISION_ACCELERATOR,
        defaultValue = DEFAULT_VISION_ACCELERATOR.label,
      )
    val visionBackend =
      when (visionAccelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.GPU()
      }
    val shouldEnableImage = supportImage
    val shouldEnableAudio = supportAudio
    val preferredBackend =
      when (accelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.CPU()
      }
    Log.d(TAG, "Preferred backend: $preferredBackend")

    val modelPath = model.getPath(context = context)
    purgeImportedCpuWeightCacheIfPresent(
      model = model,
      accelerator = accelerator,
      modelPath = modelPath,
    )
    val engineConfig =
      EngineConfig(
        modelPath = modelPath,
        backend = preferredBackend,
        visionBackend = if (shouldEnableImage) visionBackend else null, // must be GPU for Gemma 3n
        audioBackend = if (shouldEnableAudio) Backend.CPU() else null, // must be CPU for Gemma 3n
        maxNumTokens = maxTokens,
        cacheDir =
          if (modelPath.startsWith("/data/local/tmp"))
            context.getExternalFilesDir(null)?.absolutePath
          else null,
      )

    // Create an instance of LiteRT LM engine and conversation.
    var engine: Engine? = null
    try {
      engine = Engine(engineConfig)
      engine.initialize()

      val sessionConfig =
        buildSessionConfig(
          systemInstruction = systemInstruction,
          tools = tools,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
        )
      val conversation =
        createConversation(
          engine = engine,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          config =
            buildConversationConfig(
              samplerConfig =
                if (preferredBackend is Backend.NPU) {
                  null
                } else {
                  SamplerConfig(
                    topK = topK,
                    topP = topP.toDouble(),
                    temperature = temperature.toDouble(),
                  )
                },
              systemInstruction = systemInstruction,
              tools = tools,
            ),
        )
      model.instance =
        LlmModelInstance(
          engine = engine,
          conversation = conversation,
          sessionConfig = sessionConfig,
          supportImage = shouldEnableImage,
          supportAudio = shouldEnableAudio,
        )
    } catch (e: Exception) {
      try {
        engine?.close()
      } catch (closeException: Exception) {
        Log.w(TAG, "Failed to close engine after initialize failure", closeException)
      }
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
    onDone("")
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
  ) {
    try {
      Log.d(TAG, "Resetting conversation for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      val previousConversation = instance.conversation
      val previousSessionConfig = instance.sessionConfig

      val engine = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val shouldEnableImage = supportImage
      val shouldEnableAudio = supportAudio
      Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")

      val accelerator =
        model.getStringConfigValue(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = Accelerator.GPU.label,
        )
      val samplerConfig =
        if (accelerator == Accelerator.NPU.label) {
          null
        } else {
          SamplerConfig(
            topK = topK,
            topP = topP.toDouble(),
            temperature = temperature.toDouble(),
          )
        }
      val targetSessionConfig =
        buildSessionConfig(
          systemInstruction = systemInstruction,
          tools = tools,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
        )

      previousConversation.close()

      try {
        val newConversation =
          createConversation(
            engine = engine,
            enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
            config =
              buildConversationConfig(
                samplerConfig = samplerConfig,
                systemInstruction = systemInstruction,
                tools = tools,
              ),
          )
        instance.conversation = newConversation
        instance.sessionConfig = targetSessionConfig
      } catch (createException: Exception) {
        Log.w(
          TAG,
          "Failed to create replacement conversation after closing the previous session. Trying to restore a fallback conversation.",
          createException,
        )
        val restoredConversation =
          restoreConversationAfterResetFailure(
            engine = engine,
            samplerConfig = samplerConfig,
            previousSessionConfig = previousSessionConfig,
          )
        if (restoredConversation != null) {
          instance.conversation = restoredConversation
          instance.sessionConfig = previousSessionConfig
        }
        throw createException
      }

      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset conversation", e)
      throw e
    }
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as LlmModelInstance

    try {
      instance.conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }

    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the engine: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.instance = null

    onDone()
    Log.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? LlmModelInstance ?: return
    runCatching {
      instance.conversation.cancelProcess()
    }.onFailure { error ->
      if (shouldIgnoreStopResponseFailure(error)) {
        Log.w(
          TAG,
          "Ignoring stopResponse failure for model='${model.name}' because the conversation is already dead: ${error.message}",
        )
      } else {
        throw error
      }
    }
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      onError("LlmModelInstance is not initialized.")
      return
    }

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val conversation = instance.conversation

    val contents = mutableListOf<Content>()
    for (image in images) {
      contents.add(Content.ImageBytes(image.toPngByteArray()))
    }
    for (audioClip in audioClips) {
      contents.add(Content.AudioBytes(audioClip))
    }
    // add the text after image and audio for the accurate last token
    if (input.trim().isNotEmpty()) {
      contents.add(Content.Text(input))
    }

    conversation.sendMessageAsync(
      Contents.of(contents),
      object : MessageCallback {
        override fun onMessage(message: Message) {
          resultListener(message.toString(), false, message.channels["thought"])
        }

        override fun onDone() {
          resultListener("", true, null)
        }

        override fun onError(throwable: Throwable) {
          if (throwable is CancellationException) {
            Log.i(TAG, "The inference is cancelled.")
            resultListener("", true, null)
          } else {
            Log.e(TAG, "onError", throwable)
            onError("Error: ${throwable.message}")
          }
        }
      },
      extraContext ?: emptyMap(),
    )
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }

  @OptIn(ExperimentalApi::class)
  private fun createConversation(
    engine: Engine,
    enableConversationConstrainedDecoding: Boolean,
    config: ConversationConfig,
  ): Conversation {
    return withConversationConstrainedDecoding(
      enableConversationConstrainedDecoding = enableConversationConstrainedDecoding
    ) {
      engine.createConversation(config)
    }
  }
}

internal fun shouldIgnoreStopResponseFailure(error: Throwable): Boolean =
  error is IllegalStateException && error.message == "Conversation is not alive."

@OptIn(ExperimentalApi::class)
internal inline fun <T> withConversationConstrainedDecoding(
  enableConversationConstrainedDecoding: Boolean,
  block: () -> T,
): T {
  val previousValue = ExperimentalFlags.enableConversationConstrainedDecoding
  ExperimentalFlags.enableConversationConstrainedDecoding =
    enableConversationConstrainedDecoding
  return try {
    block()
  } finally {
    ExperimentalFlags.enableConversationConstrainedDecoding = previousValue
  }
}

internal fun buildSessionConfig(
  systemInstruction: Contents?,
  tools: List<ToolProvider>,
  enableConversationConstrainedDecoding: Boolean,
): LlmConversationSessionConfig {
  return LlmConversationSessionConfig(
    systemInstructionText = systemInstruction?.toString()?.trim().orEmpty(),
    tools = tools.toList(),
    enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
  )
}

internal fun resolveImportedCpuWeightCacheFile(
  model: Model,
  accelerator: String,
  modelPath: String,
): File? {
  if (!model.imported || accelerator != Accelerator.CPU.label || modelPath.isBlank()) {
    return null
  }

  val cacheFile = File("${modelPath}.xnnpack_cache")
  return cacheFile.takeIf { it.exists() }
}

private fun purgeImportedCpuWeightCacheIfPresent(
  model: Model,
  accelerator: String,
  modelPath: String,
) {
  val cacheFile =
    resolveImportedCpuWeightCacheFile(
      model = model,
      accelerator = accelerator,
      modelPath = modelPath,
    ) ?: return

  // Imported CPU models can carry stale XNNPACK sidecar caches across runtime/app upgrades.
  // If LiteRT-LM consumes an incompatible cache, native initialization can abort the process.
  if (cacheFile.delete()) {
    Log.w(TAG, "Purged imported CPU weight cache before init: ${cacheFile.absolutePath}")
  } else {
    Log.w(TAG, "Failed to purge imported CPU weight cache before init: ${cacheFile.absolutePath}")
  }
}

private fun buildConversationConfig(
  samplerConfig: SamplerConfig?,
  systemInstruction: Contents?,
  tools: List<ToolProvider>,
): ConversationConfig {
  return ConversationConfig(
    samplerConfig = samplerConfig,
    systemInstruction = systemInstruction,
    tools = tools,
  )
}

@OptIn(ExperimentalApi::class)
private fun restoreConversationAfterResetFailure(
  engine: Engine,
  samplerConfig: SamplerConfig?,
  previousSessionConfig: LlmConversationSessionConfig,
): Conversation? {
  val fallbackConfigs =
    listOf(
      previousSessionConfig,
      LlmConversationSessionConfig(),
    ).distinct()

  for (fallbackConfig in fallbackConfigs) {
    try {
      val restoredConversation =
        createConversationWithConstrainedDecoding(
          engine = engine,
          enableConversationConstrainedDecoding =
            fallbackConfig.enableConversationConstrainedDecoding,
          config =
            buildConversationConfig(
              samplerConfig = samplerConfig,
              systemInstruction = fallbackConfig.toSystemInstructionContents(),
              tools = fallbackConfig.tools,
            ),
        )
      Log.w(
        TAG,
        "Restored fallback conversation after reset failure systemPromptChars=${fallbackConfig.systemInstructionText.length} tools=${fallbackConfig.tools.size} constrained=${fallbackConfig.enableConversationConstrainedDecoding}",
      )
      return restoredConversation
    } catch (restoreException: Exception) {
      Log.w(
        TAG,
        "Failed to restore fallback conversation systemPromptChars=${fallbackConfig.systemInstructionText.length} tools=${fallbackConfig.tools.size} constrained=${fallbackConfig.enableConversationConstrainedDecoding}",
        restoreException,
      )
    }
  }

  return null
}

@OptIn(ExperimentalApi::class)
private fun createConversationWithConstrainedDecoding(
  engine: Engine,
  enableConversationConstrainedDecoding: Boolean,
  config: ConversationConfig,
): Conversation {
  return withConversationConstrainedDecoding(
    enableConversationConstrainedDecoding = enableConversationConstrainedDecoding
  ) {
    engine.createConversation(config)
  }
}

internal fun LlmConversationSessionConfig.toSystemInstructionContents(): Contents? {
  if (systemInstructionText.isBlank()) {
    return null
  }
  return Contents.of(systemInstructionText)
}
