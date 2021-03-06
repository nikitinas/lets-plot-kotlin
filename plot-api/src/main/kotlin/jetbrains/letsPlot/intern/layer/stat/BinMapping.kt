/*
 * Copyright (c) 2019. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package jetbrains.letsPlot.intern.layer.stat

class BinMapping(
    override var x: Any? = null,
    override var weight: Any? = null
) : BinAesthetics {
    override fun seal() = super.seal()
}
