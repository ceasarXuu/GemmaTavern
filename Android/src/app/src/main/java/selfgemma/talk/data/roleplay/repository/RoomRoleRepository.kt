package selfgemma.talk.data.roleplay.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import selfgemma.talk.data.roleplay.db.dao.RoleDao
import selfgemma.talk.data.roleplay.mapper.toDomain
import selfgemma.talk.data.roleplay.mapper.toEntity
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.repository.RoleRepository

@Singleton
class RoomRoleRepository @Inject constructor(private val roleDao: RoleDao) : RoleRepository {
  override fun observeRoles(): Flow<List<RoleCard>> {
    return roleDao.observeActiveRoles().map { roles -> roles.map { it.toDomain() } }
  }

  override suspend fun getRole(roleId: String): RoleCard? {
    return roleDao.getById(roleId)?.toDomain()
  }

  override suspend fun saveRole(role: RoleCard) {
    roleDao.upsert(role.toEntity())
  }

  override suspend fun deleteRole(roleId: String) {
    roleDao.delete(roleId)
  }
}