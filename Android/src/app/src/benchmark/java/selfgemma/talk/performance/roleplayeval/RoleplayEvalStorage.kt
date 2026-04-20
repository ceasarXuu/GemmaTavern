package selfgemma.talk.performance.roleplayeval

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

internal object RoleplayEvalStorage {
  val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

  fun rootDir(context: Context): File {
    val mediaDir = context.externalMediaDirs.firstOrNull()?.takeIf { it.exists() || it.mkdirs() }
    val baseDir = mediaDir ?: context.getExternalFilesDir(null) ?: context.filesDir
    return File(baseDir, ROLEPLAY_EVAL_ROOT_DIR).apply { mkdirs() }
  }

  fun runDir(context: Context, runId: String): File {
    return File(rootDir(context), "$ROLEPLAY_EVAL_RUNS_DIR/$runId").apply { mkdirs() }
  }

  fun caseDir(context: Context, runId: String, caseId: String): File {
    return File(runDir(context, runId), "$ROLEPLAY_EVAL_CASES_DIR/$caseId").apply { mkdirs() }
  }

  fun readManifest(manifestPath: String): RoleplayEvalManifest {
    return File(manifestPath).reader(Charsets.UTF_8).use { reader ->
      gson.fromJson(reader, RoleplayEvalManifest::class.java)
    }
  }

  fun copyManifest(manifestPath: String, runDir: File) {
    File(manifestPath).copyTo(File(runDir, ROLEPLAY_EVAL_MANIFEST_FILE), overwrite = true)
  }

  fun writeJson(file: File, value: Any?) {
    file.parentFile?.mkdirs()
    file.writer(Charsets.UTF_8).use { writer ->
      gson.toJson(value, writer)
    }
  }

  fun statusFile(runDir: File): File = File(runDir, ROLEPLAY_EVAL_STATUS_FILE)

  fun summaryFile(runDir: File): File = File(runDir, ROLEPLAY_EVAL_SUMMARY_FILE)

  fun errorFile(runDir: File): File = File(runDir, ROLEPLAY_EVAL_ERROR_FILE)
}
