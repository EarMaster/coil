package app.coilforphoniebox.data.repository

import app.coilforphoniebox.data.db.BoxDao
import app.coilforphoniebox.data.db.toDomain
import app.coilforphoniebox.data.db.toEntity
import app.coilforphoniebox.data.settings.SettingsStore
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionTestResult
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.transport.BoxProbe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BoxRepositoryImpl @Inject constructor(
    private val dao: BoxDao,
    private val settings: SettingsStore,
    private val probe: BoxProbe,
) : BoxRepository {

    override val boxes: Flow<List<Box>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /**
     * Falls back to the first configured box when no active id is stored, which covers
     * a fresh install, an imported settings file and a restored backup alike — all
     * cases where a box exists but nothing has selected one yet.
     */
    override val activeBox: Flow<Box?> =
        combine(boxes, settings.settings.map { it.activeBoxId }.distinctUntilChanged()) { list, activeId ->
            list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
        }.distinctUntilChanged()

    override suspend fun box(boxId: String): Box? = dao.find(boxId)?.toDomain()

    override suspend fun add(displayName: String, host: String, rpcPort: Int, pubPort: Int): Box {
        val box = Box(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            host = host,
            rpcPort = rpcPort,
            pubPort = pubPort,
            addedAt = System.currentTimeMillis(),
            sortIndex = dao.maxSortIndex() + 1,
        )
        dao.insert(box.toEntity())

        // The first box added becomes the active one; later ones do not steal focus.
        if (settings.current().activeBoxId == null) settings.setActiveBoxId(box.id)
        return box
    }

    override suspend fun update(box: Box) = dao.update(box.toEntity())

    override suspend fun delete(boxId: String) {
        dao.delete(boxId)
        if (settings.current().activeBoxId == boxId) {
            // Point at whatever is left rather than leaving a dangling id behind.
            settings.setActiveBoxId(boxes.first().firstOrNull()?.id)
        }
    }

    override suspend fun setActive(boxId: String) = settings.setActiveBoxId(boxId)

    override suspend fun markSeen(boxId: String) =
        dao.markSeen(boxId, System.currentTimeMillis())

    override suspend fun testConnection(host: String, rpcPort: Int): ConnectionTestResult =
        probe.probe(host, rpcPort)
}
