package selfgemma.talk.domain.roleplay.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.repository.RoleRepository

class EnsureRoleplaySeedDataUseCaseTest {
  @Test
  fun invoke_savesLocalizedBuiltInRoleWhenRepositoryIsEmpty() = runBlocking {
    val repository = SeedSyncFakeRoleRepository()
    val useCase =
      EnsureRoleplaySeedDataUseCase(
        roleRepository = repository,
        roleplaySeedCatalog = FakeSeedCatalog(name = "Gemma", summary = "English summary"),
      )

    useCase()

    val roles = repository.snapshot()
    assertEquals(1, roles.size)
    assertEquals("builtin_gemma_tavern_hostess", roles.single().id)
    assertEquals("Gemma", roles.single().name)
    assertTrue(roles.single().builtIn)
  }

  @Test
  fun invoke_refreshesBuiltInRoleContentForCurrentLocale() = runBlocking {
    val repository =
      SeedSyncFakeRoleRepository(
        roles =
          listOf(
            fakeRole(
              id = "builtin_gemma_tavern_hostess",
              name = "Gemma",
              summary = "English summary",
              builtIn = true,
              createdAt = 5L,
              updatedAt = 6L,
            )
          )
      )
    val useCase =
      EnsureRoleplaySeedDataUseCase(
        roleRepository = repository,
        roleplaySeedCatalog = FakeSeedCatalog(name = "Gemma-ZH", summary = "Localized summary"),
      )

    useCase()
    val localizedRole = repository.requireRole("builtin_gemma_tavern_hostess")

    assertEquals("Gemma-ZH", localizedRole.name)
    assertEquals("Localized summary", localizedRole.summary)
    assertEquals(5L, localizedRole.createdAt)
    assertTrue(localizedRole.updatedAt >= localizedRole.createdAt)
  }

  @Test
  fun invoke_deletesLegacyBuiltInRolesAndKeepsCustomRoles() = runBlocking {
    val repository =
      SeedSyncFakeRoleRepository(
        roles =
          listOf(
            fakeRole(id = "builtin_astra_captain", name = "Captain Astra", builtIn = true),
            fakeRole(id = "builtin_iris_archivist", name = "Iris Vale", builtIn = true),
            fakeRole(id = "custom_keeper", name = "Custom Keeper", builtIn = false),
          )
      )
    val useCase =
      EnsureRoleplaySeedDataUseCase(
        roleRepository = repository,
        roleplaySeedCatalog = FakeSeedCatalog(name = "Gemma", summary = "English summary"),
      )

    useCase()
    useCase()

    val roles = repository.snapshot()
    assertEquals(2, roles.size)
    assertFalse(roles.any { it.id == "builtin_astra_captain" })
    assertFalse(roles.any { it.id == "builtin_iris_archivist" })
    assertTrue(roles.any { it.id == "builtin_gemma_tavern_hostess" && it.builtIn })
    assertTrue(roles.any { it.id == "custom_keeper" && !it.builtIn })
    assertEquals(listOf("builtin_astra_captain", "builtin_iris_archivist"), repository.deletedRoleIds)
  }
}

private class FakeSeedCatalog(
  private val name: String,
  private val summary: String,
) : RoleplaySeedCatalog {
  override fun defaultRoles(now: Long, defaultModelId: String?): List<RoleCard> {
    return listOf(
      fakeRole(
        id = "builtin_gemma_tavern_hostess",
        name = name,
        summary = summary,
        builtIn = true,
        createdAt = now,
        updatedAt = now,
        defaultModelId = defaultModelId,
      )
    )
  }
}

private class SeedSyncFakeRoleRepository(
  roles: List<RoleCard> = emptyList(),
) : RoleRepository {
  private val state = MutableStateFlow(roles.associateBy { it.id }.toMutableMap())
  val deletedRoleIds = mutableListOf<String>()

  override fun observeRoles(): Flow<List<RoleCard>> = state.map { it.values.sortedBy(RoleCard::id) }

  override suspend fun getRole(roleId: String): RoleCard? = state.value[roleId]

  override suspend fun saveRole(role: RoleCard) {
    val next = state.value.toMutableMap()
    next[role.id] = role
    state.value = next
  }

  override suspend fun deleteRole(roleId: String) {
    val next = state.value.toMutableMap()
    if (next.remove(roleId) != null) {
      deletedRoleIds += roleId
    }
    state.value = next
  }

  fun snapshot(): List<RoleCard> = state.value.values.sortedBy { it.id }

  fun requireRole(roleId: String): RoleCard {
    return checkNotNull(state.value[roleId]) { "Missing roleId=$roleId" }
  }
}

private fun fakeRole(
  id: String,
  name: String,
  summary: String = "",
  builtIn: Boolean,
  createdAt: Long = 1L,
  updatedAt: Long = 1L,
  defaultModelId: String? = null,
): RoleCard {
  return RoleCard(
    id = id,
    name = name,
    summary = summary,
    systemPrompt = "Stay in character.",
    defaultModelId = defaultModelId,
    builtIn = builtIn,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
}
