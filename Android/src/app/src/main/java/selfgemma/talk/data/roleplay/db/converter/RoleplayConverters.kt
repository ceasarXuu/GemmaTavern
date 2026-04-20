package selfgemma.talk.data.roleplay.db.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import selfgemma.talk.domain.roleplay.model.CompactionSummaryType
import selfgemma.talk.domain.roleplay.model.MemoryBranchScope
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MemoryEpistemicStatus
import selfgemma.talk.domain.roleplay.model.MemoryNamespace
import selfgemma.talk.domain.roleplay.model.MemoryPlane
import selfgemma.talk.domain.roleplay.model.MemoryStability
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.OpenThreadOwner
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.OpenThreadType
import selfgemma.talk.domain.roleplay.model.SessionEventType

class RoleplayConverters {
  private val gson = Gson()

  @TypeConverter
  fun fromStringList(value: List<String>?): String {
    return gson.toJson(value ?: emptyList<String>())
  }

  @TypeConverter
  fun toStringList(value: String?): List<String> {
    if (value.isNullOrBlank()) {
      return emptyList()
    }

    val listType = object : TypeToken<List<String>>() {}.type
    return gson.fromJson(value, listType) ?: emptyList()
  }

  @TypeConverter
  fun fromMessageSide(value: MessageSide): String {
    return value.name
  }

  @TypeConverter
  fun toMessageSide(value: String): MessageSide {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromMessageKind(value: MessageKind): String {
    return value.name
  }

  @TypeConverter
  fun toMessageKind(value: String): MessageKind {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromMessageStatus(value: MessageStatus): String {
    return value.name
  }

  @TypeConverter
  fun toMessageStatus(value: String): MessageStatus {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromMemoryCategory(value: MemoryCategory): String {
    return value.name
  }

  @TypeConverter
  fun toMemoryCategory(value: String): MemoryCategory {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromMemoryPlane(value: MemoryPlane): String {
    return value.name
  }

  @TypeConverter
  fun toMemoryPlane(value: String): MemoryPlane {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromMemoryNamespace(value: MemoryNamespace): String {
    return value.name
  }

  @TypeConverter
  fun toMemoryNamespace(value: String): MemoryNamespace {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromMemoryStability(value: MemoryStability): String {
    return value.name
  }

  @TypeConverter
  fun toMemoryStability(value: String): MemoryStability {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromMemoryEpistemicStatus(value: MemoryEpistemicStatus): String {
    return value.name
  }

  @TypeConverter
  fun toMemoryEpistemicStatus(value: String): MemoryEpistemicStatus {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromMemoryBranchScope(value: MemoryBranchScope): String {
    return value.name
  }

  @TypeConverter
  fun toMemoryBranchScope(value: String): MemoryBranchScope {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromOpenThreadType(value: OpenThreadType): String {
    return value.name
  }

  @TypeConverter
  fun toOpenThreadType(value: String): OpenThreadType {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromOpenThreadOwner(value: OpenThreadOwner): String {
    return value.name
  }

  @TypeConverter
  fun toOpenThreadOwner(value: String): OpenThreadOwner {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromOpenThreadStatus(value: OpenThreadStatus): String {
    return value.name
  }

  @TypeConverter
  fun toOpenThreadStatus(value: String): OpenThreadStatus {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromCompactionSummaryType(value: CompactionSummaryType): String {
    return value.name
  }

  @TypeConverter
  fun toCompactionSummaryType(value: String): CompactionSummaryType {
    return enumValueOf(value)
  }

  @TypeConverter
  fun fromSessionEventType(value: SessionEventType): String {
    return value.name
  }

  @TypeConverter
  fun toSessionEventType(value: String): SessionEventType {
    return enumValueOf(value)
  }
}
