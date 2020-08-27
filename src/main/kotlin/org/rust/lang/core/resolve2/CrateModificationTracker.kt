/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import org.rust.cargo.CfgOptions
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.macros.shouldIndexFile
import org.rust.lang.core.psi.rustFile
import org.rust.openapiext.toPsiFile

data class CrateMetaData(
    val edition: CargoWorkspace.Edition,
    private val features: Collection<CargoWorkspace.Feature>,
    private val cfgOptions: CfgOptions,
    private val env: Map<String, String>,
    // todo store modificationStamp of DefMap for each dependency ?
    private val dependencies: Set<CratePersistentId>
) {
    constructor(crate: Crate) : this(
        edition = crate.edition,
        features = crate.features,
        cfgOptions = crate.cfgOptions,
        env = crate.env,
        dependencies = crate.flatDependencies.mapNotNull { it.id }.toSet()
    )
}

// todo добавить после построение DefMap `testAssert { !isCrateChanged(...) }`
fun isCrateChanged(crate: Crate, defMapService: DefMapService, indicator: ProgressIndicator): Boolean {
    indicator.checkCanceled()  // todo call more often ?
    val id = crate.id ?: return false

    // todo ?
    val crateRootFile = crate.rootModFile ?: return false
    if (!shouldIndexFile(defMapService.project, crateRootFile)) return false

    val defMap = defMapService.defMaps[id] ?: return true
    if (defMap.metaData != CrateMetaData(crate)) return true
    // todo read action ?
    val hasAnyFileChanged = runReadAction { defMap.hasAnyFileChanged(defMapService.project) }
    return hasAnyFileChanged || defMap.hasAnyMissedFileCreated()
}

private fun CrateDefMap.hasAnyFileChanged(project: Project): Boolean {
    val persistentFS = PersistentFS.getInstance()
    return fileInfos.keys.any { fileId ->
        val file = persistentFS
            .findFileById(fileId)
            ?.toPsiFile(project)
            ?.rustFile
            ?: return true  // file was deleted - should rebuilt DefMap
        isFileChanged(file, this)
    }
}

private fun CrateDefMap.hasAnyMissedFileCreated(): Boolean {
    val fileManager = VirtualFileManager.getInstance()
    return missedFiles.any { fileManager.findFileByNioPath(it) != null }
}
