/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import org.rust.RsTask.TaskType.*
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.crate.crateGraph
import org.rust.lang.core.psi.rustPsiManager
import org.rust.openapiext.fileId
import org.rust.openapiext.testAssert
import java.util.concurrent.Executor
import kotlin.system.measureTimeMillis

/**
 * Possible modifications:
 * - After IDE restart: full recheck (for each crate compare [CrateMetaData] and `modificationStamp` of each file).
 *   Tasks [CARGO_SYNC] and [MACROS_UNPROCESSED] are executed.
 * - File changed: calculate hash and compare with hash stored in [CrateDefMap.fileInfos].
 *   Task [MACROS_WORKSPACE] is executed.
 * - File added: check whether [DefMapService.missedFiles] contains file path
 *   No task executed => we will schedule [MACROS_WORKSPACE]
 * - File deleted: todo
 *   No task executed => we will schedule [MACROS_WORKSPACE]
 * - Unknown file changed: full recheck
 *   No task executed => we will schedule [MACROS_WORKSPACE]
 * - Crate workspace changed: full recheck
 *   Tasks [CARGO_SYNC] and [MACROS_UNPROCESSED] are executed.
 */

fun updateDefMapForAllCrates(
    project: Project,
    pool: Executor,
    indicator: ProgressIndicator
) {
    if (!IS_NEW_RESOLVE_ENABLED) return
    val defMapService = project.defMapService
    val topSortedCrates = runReadAction { project.crateGraph.topSortedCrates }
    if (topSortedCrates.isEmpty()) return
    indicator.checkCanceled()

    val isFirstTime = defMapService.isFirstTime()
    if (isFirstTime) {
        buildDefMapForAllCrates(defMapService, topSortedCrates, pool, indicator)
        defMapService.resetIsFirstTime()
        return
    }

    indicator.checkCanceled()
    checkIfShouldRecheckAllCrates(defMapService, topSortedCrates, indicator)
    buildDefMapForChangedCrates(defMapService, topSortedCrates, indicator)
}

private fun checkIfShouldRecheckAllCrates(
    defMapService: DefMapService,
    topSortedCrates: List<Crate>,
    indicator: ProgressIndicator
) {
    val shouldRecheckAllCrates = defMapService.shouldRecheckAllCrates()
    if (shouldRecheckAllCrates) {
        println("\trecheckAllCrates")
        // ignore any pending files - we anyway will recheck all crates
        defMapService.takeChangedFiles()

        val changedCrates = topSortedCrates
            .filter { isCrateChanged(it, defMapService, indicator) }
            .mapNotNull { it.id }
        defMapService.addChangedCrates(changedCrates)
        defMapService.resetShouldRecheckAllCrates()
    }
}

/** For tests */
fun Project.buildDefMapForAllCrates(pool: Executor, indicator: ProgressIndicator, async: Boolean = true) {
    val topSortedCrates = runReadAction { crateGraph.topSortedCrates }
    buildDefMapForAllCrates(defMapService, topSortedCrates, pool, indicator, async)
}

private fun buildDefMapForAllCrates(
    defMapService: DefMapService,
    topSortedCrates: List<Crate>,
    pool: Executor,
    indicator: ProgressIndicator,
    async: Boolean = true
) {
    indicator.checkCanceled()
    println("\tbuildDefMapForAllCrates")
    defMapService.defMaps.clear()
    val time = measureTimeMillis {
        if (async) {
            AsyncDefMapBuilder(pool, topSortedCrates, indicator).build()
        } else {
            for (crate in topSortedCrates) {
                crate.updateDefMap(indicator)
            }
        }
    }
    timesBuildDefMaps += time
    RESOLVE_LOG.info("Created DefMap for all crates in $time milliseconds")
    if (!async) println("wallTime: $time")

    indicator.checkCanceled()
    defMapService.project.resetCacheAndHighlighting()
}

fun buildDefMap(crate: Crate, indicator: ProgressIndicator): CrateDefMap? {
    RESOLVE_LOG.info("Building DefMap for $crate")
    val project = crate.cargoProject.project
    val context = CollectorContext(crate, indicator)
    val defMap = runReadAction {
        buildDefMapContainingExplicitItems(context)
    } ?: return null
    DefCollector(project, defMap, context).collect()
    project.defMapService.afterDefMapBuilt(defMap)
    return defMap
}

private fun buildDefMapForChangedCrates(
    defMapService: DefMapService,
    topSortedCrates: List<Crate>,
    indicator: ProgressIndicator
) {
    val changedCratesNew = getChangedCratesNew(defMapService)
    defMapService.addChangedCrates(changedCratesNew)
    indicator.checkCanceled()

    // `changedCrates` will be processed in next task
    if (defMapService.hasChangedFiles()) return

    // todo если будет ProcessCancelledException, то changedCrates потеряются ?
    val changedCrates = defMapService.takeChangedCrates()
    if (changedCrates.isEmpty()) return
    val changedCratesAll = topSortCratesAndAddReverseDependencies(changedCrates, topSortedCrates)
    println("\tbuildDefMapForChangedCrates: $changedCratesAll")
    buildDefMapForCrates(changedCratesAll, defMapService, indicator)
    defMapService.project.resetCacheAndHighlighting()
}

private fun buildDefMapForCrates(crates: List<Crate>, defMapService: DefMapService, indicator: ProgressIndicator) {
    for (crate in crates) {
        defMapService.defMaps.remove(crate.id)
    }
    // todo async
    for (crate in crates) {
        crate.updateDefMap(indicator)
    }
}

private fun getChangedCratesNew(defMapService: DefMapService): Set<CratePersistentId> {
    val changedFiles = defMapService.takeChangedFiles()
    val changedCratesCurr = defMapService.getChangedCrates()
    val changedCratesNew = hashSetOf<CratePersistentId>()
    for (file in changedFiles) {
        // todo зачем проверять modificationStamp?
        //  если файл был добавлен в changedFiles, то modificationStamp гарантированно изменился
        val (modificationStampPrev, crate) = defMapService.fileModificationStamps[file.virtualFile.fileId]
        // todo может быть такое, что defMap ещё не была посчитана самый первый раз ?
            ?: continue
        val modificationStampCurr = file.viewProvider.modificationStamp
        testAssert { modificationStampCurr >= modificationStampPrev }
        if (modificationStampCurr == modificationStampPrev) continue

        // can skip hash comparison if we already scheduled building [CrateDefMap] for [crate]
        if (crate in changedCratesNew || crate in changedCratesCurr) continue

        // todo
        runReadAction {
            // todo
            val defMap = file.project.crateGraph.findCrateById(crate)?.defMap ?: return@runReadAction
            if (isFileChanged(file, defMap)) {
                changedCratesNew += crate
            }
        }
    }
    return changedCratesNew
}

private fun topSortCratesAndAddReverseDependencies(
    crateIds: Set<CratePersistentId>,
    topSortedCrates: List<Crate>
): List<Crate> {
    // todo
    return runReadAction {
        val crates = topSortedCrates.filter {
            val id = it.id ?: return@filter false
            id in crateIds
        }
        val cratesAll = crates.withReversedDependencies()
        topSortedCrates.filter { it in cratesAll }
    }
}

private fun List<Crate>.withReversedDependencies(): Set<Crate> {
    val result = hashSetOf<Crate>()
    fun processCrate(crate: Crate) {
        if (crate in result || crate.id == null) return
        result += crate
        for (reverseDependency in crate.reverseDependencies) {
            processCrate(reverseDependency)
        }
    }
    for (crate in this) {
        processCrate(crate)
    }
    return result
}

private fun Project.resetCacheAndHighlighting() {
    rustPsiManager.incRustStructureModificationCount()
    DaemonCodeAnalyzer.getInstance(this).restart()
}

// todo remove
val timesBuildDefMaps: MutableList<Long> = mutableListOf()
