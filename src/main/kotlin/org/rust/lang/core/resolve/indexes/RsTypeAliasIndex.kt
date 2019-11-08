/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve.indexes

import com.intellij.openapi.project.Project
import com.intellij.psi.stubs.AbstractStubIndex
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.io.KeyDescriptor
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.ide.search.RsWithMacrosProjectScope
import org.rust.lang.core.psi.RsTypeAlias
import org.rust.lang.core.psi.ext.RsAbstractableOwner
import org.rust.lang.core.psi.ext.containingCargoPackage
import org.rust.lang.core.psi.ext.owner
import org.rust.lang.core.psi.isValidProjectMember
import org.rust.lang.core.resolve.RsProcessor
import org.rust.lang.core.stubs.RsFileStub
import org.rust.lang.core.stubs.RsTypeAliasStub
import org.rust.lang.core.types.TyFingerprint
import org.rust.openapiext.getElements

class RsTypeAliasIndex : AbstractStubIndex<TyFingerprint, RsTypeAlias>() {
    override fun getVersion(): Int = RsFileStub.Type.stubVersion
    override fun getKey(): StubIndexKey<TyFingerprint, RsTypeAlias> = KEY
    override fun getKeyDescriptor(): KeyDescriptor<TyFingerprint> = TyFingerprint.KeyDescriptor

    companion object {
        fun findPotentialAliases(
            project: Project,
            tyf: TyFingerprint,
            processor: RsProcessor<RsTypeAlias>
        ): Boolean {
            val aliases = getElements(KEY, tyf, project, RsWithMacrosProjectScope(project))
                .filter { it.owner is RsAbstractableOwner.Free && it.isValidProjectMember }

            // This is basically a hack to make some crates (winapi 0.2) work in a reasonable amount of time.
            // If the number of aliases exceeds threshold, we prefer ones from stdlib and a workspace over
            // aliases from dependencies
            val filteredAliases = if (aliases.size <= ALIAS_COUNT_THRESHOLD) {
                aliases
            } else {
                val stdlibAliases = mutableListOf<RsTypeAlias>()
                val workspaceAliases = mutableListOf<RsTypeAlias>()
                for (alias in aliases) {
                    val packageOrigin = alias.containingCargoPackage?.origin ?: continue
                    when (packageOrigin) {
                        PackageOrigin.STDLIB -> stdlibAliases += alias
                        PackageOrigin.WORKSPACE -> workspaceAliases += alias
                        else -> Unit
                    }
                }
                when {
                    stdlibAliases.size + workspaceAliases.size < ALIAS_COUNT_THRESHOLD -> {
                        stdlibAliases + workspaceAliases
                    }
                    stdlibAliases.size < ALIAS_COUNT_THRESHOLD -> stdlibAliases
                    else -> return false
                }
            }

            // Note that `getElements` is intentionally used with intermediate collection instead of
            // `StubIndex.processElements` in order to simplify profiling
            return filteredAliases.any { processor(it) }
        }

        fun index(stub: RsTypeAliasStub, sink: IndexSink) {
            val alias = stub.psi
            val typeRef = alias.typeReference ?: return
            TyFingerprint.create(typeRef, emptyList())
                .forEach { sink.occurrence(KEY, it) }
        }

        private const val ALIAS_COUNT_THRESHOLD = 10
        private val KEY: StubIndexKey<TyFingerprint, RsTypeAlias> =
            StubIndexKey.createIndexKey("org.rust.lang.core.stubs.index.RsTypeAliasIndex")
    }
}
