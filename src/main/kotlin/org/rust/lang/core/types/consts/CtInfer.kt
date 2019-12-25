/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.consts

import org.rust.lang.core.types.HAS_CT_INFER_MASK
import org.rust.lang.core.types.infer.Node
import org.rust.lang.core.types.infer.NodeOrValue
import org.rust.lang.core.types.infer.VarValue

sealed class CtInfer : Const(HAS_CT_INFER_MASK) {
    class CtVar(
        val origin: Const? = null,
        override var parent: NodeOrValue = VarValue(null, 0)
    ) : CtInfer(), Node
}

/** Used for caching only */
sealed class FreshCtInfer : Const() {
    data class CtVar(val id: Int) : FreshCtInfer()
}
