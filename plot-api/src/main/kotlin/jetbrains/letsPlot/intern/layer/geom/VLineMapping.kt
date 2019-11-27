package jetbrains.letsPlot.intern.layer.geom

import jetbrains.letsPlot.intern.layer.WithGroupOption

class VLineMapping(
    override var xintercept: Any? = null,
    override var alpha: Any? = null,
    override var color: Any? = null,
    override var linetype: Any? = null,
    override var size: Any? = null,
    override var group: Any? = null
) : VLineAesthetics, WithGroupOption {
    override fun seal() = super.seal() + group()
}


