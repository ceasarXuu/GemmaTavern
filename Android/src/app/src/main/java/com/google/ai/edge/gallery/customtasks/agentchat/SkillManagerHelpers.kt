package selfgemma.talk.customtasks.agentchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlin.io.encoding.Base64
import selfgemma.talk.proto.Skill

/**
 * Converts the content of a skill.md file to a [Skill] proto.
 *
 * The expected format is:
 * ```
 * ---
 * name: name-of-the-skill
 * description: description of the skill
 * metadata:
 *   key: value
 * ---
 *
 * other instructions text
 * ```
 *
 * @return A [Pair] containing the parsed [Skill] proto (or null if errors occurred) and a list of
 *   error messages.
 */
fun convertSkillMdToProto(
  mdContent: String,
  builtIn: Boolean,
  selected: Boolean,
  skillUrl: String = "",
  importDir: String = "",
): Pair<Skill?, List<String>> {
  val parts = mdContent.split("---")
  val errors = mutableListOf<String>()

  if (parts.size < 3) {
    errors.add("Invalid format: Expected at least two '---' sections.")
    return Pair(null, errors)
  }

  // Part 1: Header (index 1)
  val header = parts[1].trim()
  var name: String? = null
  var description: String? = null
  var requireSecret = false
  var requireSecretDescription = ""
  var homepage: String? = null

  var startMetadata = false
  for (line in header.lines()) {
    val trimmedLine = line.trim()
    if (trimmedLine == "metadata:") {
      startMetadata = true
      continue
    }
    if (!startMetadata) {
      when {
        trimmedLine.startsWith("name:") -> name = trimmedLine.substringAfter("name:").trim()
        trimmedLine.startsWith("description:") ->
          description = trimmedLine.substringAfter("description:").trim()
      }
    } else {
      when {
        trimmedLine.startsWith("require-secret:") ->
          requireSecret = trimmedLine.substringAfter("require-secret:").trim().toBoolean()
        trimmedLine.startsWith("require-secret-description:") ->
          requireSecretDescription =
            trimmedLine.substringAfter("require-secret-description:").trim()
        trimmedLine.startsWith("homepage:") ->
          homepage = trimmedLine.substringAfter("homepage:").trim()
      }
    }
  }

  if (name.isNullOrEmpty()) {
    errors.add("Missing or empty 'name' in the header.")
  }
  if (description.isNullOrEmpty()) {
    errors.add("Missing or empty 'description' in the header.")
  }

  // Part 2: Instructions (index 2 onwards)
  val instructions = parts.drop(2).joinToString("---").trim()

  if (errors.isNotEmpty()) {
    return Pair(null, errors)
  }

  val skill =
    Skill.newBuilder()
      .setName(name!!)
      .setDescription(description!!)
      .setInstructions(instructions)
      .setBuiltIn(builtIn)
      .setSelected(selected)
      .setSkillUrl(skillUrl)
      .setRequireSecret(requireSecret)
      .setRequireSecretDescription(requireSecretDescription)
      .setHomepage(homepage ?: "")
      .setImportDirName(importDir)
      .build()

  return Pair(skill, emptyList())
}

fun getDisplayName(context: Context, uri: Uri): String {
  var name = ""
  try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (nameIndex != -1 && cursor.moveToFirst()) {
        name = cursor.getString(nameIndex)
      }
    }
  } catch (e: Exception) {
    // Ignore
  }
  return name.ifEmpty { uri.path?.substringAfterLast('/') ?: "Unknown" }
}

fun decodeBase64ToBitmap(base64String: String): Bitmap? {
  return try {
    // 1. Clean the string (remove headers if present)
    val pureBase64 = base64String.substringAfter(",")

    // 2. Decode the Base64 string into a byte array
    val imageBytes = Base64.decode(pureBase64)

    // 3. Convert the byte array into a Bitmap
    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
  } catch (e: java.lang.Exception) {
    e.printStackTrace()
    null
  }
}
