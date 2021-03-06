/*
 * Copyright (c) 2019. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package jetbrains.letsPlot.intern.layer.stat

import jetbrains.letsPlot.intern.Options
import jetbrains.letsPlot.intern.layer.geom.AreaAesthetics

interface DensityAesthetics : AreaAesthetics {
    val weight: Any?

    override fun seal(): Options {
        return super.seal() +
                Options.of("weight" to weight)
    }
}