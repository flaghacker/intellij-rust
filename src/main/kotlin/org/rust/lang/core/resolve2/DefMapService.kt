/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.psi.RsFile
import org.rust.openapiext.pathAsPath
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// todo разделить interface и impl
// todo @Synchronized
@Service
class DefMapService(val project: Project) {

    // todo `ConcurrentHashMap` does not support null values
    // `null` value means there was attempt to build DefMap
    val defMaps: MutableMap<CratePersistentId, CrateDefMap?> = ConcurrentHashMap()

    // todo save DefMap to disk
    private val isFirstTime: AtomicBoolean = AtomicBoolean(true)

    fun isFirstTime(): Boolean = isFirstTime.get()

    fun resetIsFirstTime() = isFirstTime.set(false)

    private val shouldRecheckAllCrates: AtomicBoolean = AtomicBoolean(false)

    fun shouldRecheckAllCrates(): Boolean = shouldRecheckAllCrates.get()

    fun resetShouldRecheckAllCrates() = shouldRecheckAllCrates.set(false)

    /**
     * todo store [FileInfo] as values ?
     * See [FileInfo.modificationStamp].
     */
    val fileModificationStamps: MutableMap<FileId, Pair<Long, CratePersistentId>> = ConcurrentHashMap()

    /** Merged map of [CrateDefMap.missedFiles] for all crates */
    private val missedFiles: MutableMap<Path, CratePersistentId> = hashMapOf()

    // todo name ?
    @Volatile
    private var changedFiles: MutableSet<RsFile> = hashSetOf()

    private val changedCrates: MutableSet<CratePersistentId> = hashSetOf()

    @Synchronized  // todo
    fun afterDefMapBuilt(defMap: CrateDefMap) {
        val crate = defMap.crate

        // todo ?
        fileModificationStamps.entries.removeIf { it.value.second == crate }
        fileModificationStamps += defMap.fileInfos
            .mapValues { (_, info) -> info.modificationStamp to crate }

        // todo придумать что-нибудь получше вместо removeIf
        //  мб хранить в ключах defMap.modificationStamp и сравнивать его после .get() ?
        missedFiles.entries.removeIf { it.value == crate }
        missedFiles += defMap.missedFiles.associateWith { crate }
    }

    fun onCargoWorkspaceChanged() {
        // todo как-нибудь найти изменённый крейт и делать updateDefMap только для него ?
        shouldRecheckAllCrates.set(true)
    }

    @Synchronized
    fun onFileAdded(file: RsFile) {
        val path = file.virtualFile.pathAsPath
        val crate = missedFiles[path] ?: return
        changedCrates.add(crate)
    }

    @Synchronized
    fun onFileChanged(file: RsFile) {
        changedFiles.add(file)
    }

    @Synchronized
    fun takeChangedFiles(): Set<RsFile> {
        // todo сделать как в takeChangedCrates ?
        val changedFiles = changedFiles
        this.changedFiles = hashSetOf()
        return changedFiles
    }

    @Synchronized
    fun hasChangedFiles(): Boolean = changedFiles.isNotEmpty()

    @Synchronized  // todo use different locks for files and crates
    fun addChangedCrates(crates: Collection<CratePersistentId>) {
        changedCrates.addAll(crates)
    }

    @Synchronized
    fun takeChangedCrates(): Set<CratePersistentId> =
        changedCrates.toHashSet()
            .also { changedCrates.clear() }

    @Synchronized
    fun getChangedCrates(): Set<CratePersistentId> = changedCrates.toHashSet()
}

val Project.defMapService: DefMapService
    get() = service()
