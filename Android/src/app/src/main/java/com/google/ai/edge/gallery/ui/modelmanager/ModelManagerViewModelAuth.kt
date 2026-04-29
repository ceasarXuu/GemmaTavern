/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package selfgemma.talk.ui.modelmanager

import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.ResponseTypeValues
import selfgemma.talk.common.ProjectConfig
import selfgemma.talk.data.ModelDownloadStatusType

private const val TAG = "AGModelManagerVMAuth"

internal fun ModelManagerViewModel.getTokenStatusAndDataExt(): TokenStatusAndData {
  var tokenStatus = TokenStatus.NOT_STORED
  Log.d(TAG, "Reading token data from data store...")
  val tokenData = dataStoreRepository.readAccessTokenData()

  if (tokenData != null && tokenData.accessToken.isNotEmpty()) {
    Log.d(TAG, "Token exists and loaded.")
    val curTs = System.currentTimeMillis()
    val expirationTs = tokenData.expiresAtMs - 5 * 60
    Log.d(
      TAG,
      "Checking whether token has expired or not. Current ts: $curTs, expires at: $expirationTs",
    )
    if (curTs >= expirationTs) {
      Log.d(TAG, "Token expired!")
      tokenStatus = TokenStatus.EXPIRED
    } else {
      Log.d(TAG, "Token not expired.")
      tokenStatus = TokenStatus.NOT_EXPIRED
      curAccessToken = tokenData.accessToken
    }
  } else {
    Log.d(TAG, "Token doesn't exists.")
  }

  return TokenStatusAndData(status = tokenStatus, data = tokenData)
}

internal fun ModelManagerViewModel.getAuthorizationRequestExt(): AuthorizationRequest? {
  if (!ProjectConfig.isHuggingFaceAuthConfigured) return null
  return AuthorizationRequest.Builder(
      ProjectConfig.authServiceConfig,
      ProjectConfig.clientId,
      ResponseTypeValues.CODE,
      ProjectConfig.redirectUri.toUri(),
    )
    .setScope("read-repos")
    .build()
}

internal fun ModelManagerViewModel.handleAuthResultExt(
  result: ActivityResult,
  onTokenRequested: (TokenRequestResult) -> Unit,
) {
  val dataIntent = result.data
  if (dataIntent == null) {
    onTokenRequested(
      TokenRequestResult(
        status = TokenRequestResultType.FAILED,
        errorMessage = "Empty auth result",
      )
    )
    return
  }

  val response = AuthorizationResponse.fromIntent(dataIntent)
  val exception = AuthorizationException.fromIntent(dataIntent)

  when {
    response?.authorizationCode != null -> {
      var errorMessage: String? = null
      authService.performTokenRequest(response.createTokenExchangeRequest()) {
        tokenResponse,
        tokenEx ->
        if (tokenResponse != null) {
          if (tokenResponse.accessToken == null) {
            errorMessage = "Empty access token"
          } else if (tokenResponse.refreshToken == null) {
            errorMessage = "Empty refresh token"
          } else if (tokenResponse.accessTokenExpirationTime == null) {
            errorMessage = "Empty expiration time"
          } else {
            Log.d(TAG, "Token exchange successful. Storing tokens...")
            saveAccessToken(
              accessToken = tokenResponse.accessToken!!,
              refreshToken = tokenResponse.refreshToken!!,
              expiresAt = tokenResponse.accessTokenExpirationTime!!,
            )
            curAccessToken = tokenResponse.accessToken!!
            Log.d(TAG, "Token successfully saved.")
          }
        } else if (tokenEx != null) {
          errorMessage = "Token exchange failed: ${tokenEx.message}"
        } else {
          errorMessage = "Token exchange failed"
        }
        if (errorMessage == null) {
          onTokenRequested(TokenRequestResult(status = TokenRequestResultType.SUCCEEDED))
        } else {
          onTokenRequested(
            TokenRequestResult(
              status = TokenRequestResultType.FAILED,
              errorMessage = errorMessage,
            )
          )
        }
      }
    }

    exception != null -> {
      onTokenRequested(
        TokenRequestResult(
          status =
            if (exception.message == "User cancelled flow") TokenRequestResultType.USER_CANCELLED
            else TokenRequestResultType.FAILED,
          errorMessage = exception.message,
        )
      )
    }

    else -> {
      onTokenRequested(TokenRequestResult(status = TokenRequestResultType.USER_CANCELLED))
    }
  }
}

internal fun ModelManagerViewModel.saveAccessTokenExt(
  accessToken: String,
  refreshToken: String,
  expiresAt: Long,
) {
  dataStoreRepository.saveAccessTokenData(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresAt = expiresAt,
  )
}

internal fun ModelManagerViewModel.clearAccessTokenExt() {
  dataStoreRepository.clearAccessTokenData()
}

internal fun ModelManagerViewModel.processPendingDownloadsExt() {
  val vm = this
  downloadRepository.cancelAll {
    Log.d(TAG, "All workers are cancelled.")
    vm.viewModelScope.launch(Dispatchers.Main) {
      val checkedModelNames = mutableSetOf<String>()
      val tokenStatusAndData = vm.getTokenStatusAndData()
      for (task in vm.uiState.value.tasks) {
        for (model in task.models) {
          if (checkedModelNames.contains(model.name)) continue
          val downloadStatus = vm.uiState.value.modelDownloadStatus[model.name]?.status
          if (downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED) {
            if (
              tokenStatusAndData.status == TokenStatus.NOT_EXPIRED &&
                tokenStatusAndData.data != null
            ) {
              model.accessToken = tokenStatusAndData.data.accessToken
            }
            Log.d(TAG, "Sending a new download request for '${model.name}'")
            vm.downloadRepository.downloadModel(
              task = task,
              model = model,
              onStatusUpdated = vm::setDownloadStatus,
            )
          }
          checkedModelNames.add(model.name)
        }
      }
    }
  }
}
