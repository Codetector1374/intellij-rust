/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.model

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.TestOnly
import org.rust.cargo.project.workspace.CargoWorkspace
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * [CargoProjectsService] stores a list of `Cargo.toml` file,
 * registered with the current IDE project. Each `Cargo.toml`
 * is represented by a [CargoProject], whose main attribute is
 * `workspace`: a description of a Cargo project acquired from
 * Cargo itself via `cargo metadata` command.
 */
interface CargoProjectsService {
    fun findProjectForFile(file: VirtualFile): CargoProject?
    val allProjects: Collection<CargoProject>
    val hasAtLeastOneValidProject: Boolean

    fun attachCargoProject(manifest: Path): Boolean
    fun detachCargoProject(cargoProject: CargoProject)
    fun refreshAllProjects(): CompletableFuture<List<CargoProject>>
    fun discoverAndRefresh(): CompletableFuture<List<CargoProject>>?

    @TestOnly
    fun createTestProject(rootDir: VirtualFile, ws: CargoWorkspace)

    @TestOnly
    fun discoverAndRefreshSync(): List<CargoProject> {
        val projects = discoverAndRefresh()!!.get(1, TimeUnit.MINUTES)
            ?: error("Timeout when refreshing a test Cargo project")
        if (projects.isEmpty()) error("Failed to update a test Cargo project")
        return projects
    }

    companion object {
        val CARGO_PROJECTS_TOPIC: Topic<CargoProjectsListener> = Topic(
            "cargo projects changes",
            CargoProjectsListener::class.java
        )
    }

    interface CargoProjectsListener {
        fun cargoProjectsUpdated(projects: Collection<CargoProject>)
    }
}

val Project.cargoProjects get() = service<CargoProjectsService>()

interface CargoProject {
    val manifest: Path
    val rootDir: VirtualFile?

    val presentableName: String
    val workspace: CargoWorkspace?

    val workspaceStatus: UpdateStatus
    val stdlibStatus: UpdateStatus

    val mergedStatus: UpdateStatus
        get() = when {
            workspaceStatus is UpdateStatus.UpdateFailed -> workspaceStatus
            stdlibStatus is UpdateStatus.UpdateFailed -> stdlibStatus
            workspaceStatus is UpdateStatus.NeedsUpdate -> workspaceStatus
            else -> stdlibStatus
        }

    sealed class UpdateStatus {
        object NeedsUpdate : UpdateStatus()
        object UpToDate : UpdateStatus()
        class UpdateFailed(val reason: String) : UpdateStatus()
    }
}