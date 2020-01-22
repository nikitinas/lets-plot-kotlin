/*
 * Copyright (c) 2020. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package jetbrains.letsPlot.jupyter

import jetbrains.datalore.base.jsObject.JsObjectSupport
import jetbrains.datalore.plot.server.config.PlotConfigServerSide
import jetbrains.letsPlot.api.PlotSpec
import jetbrains.letsPlot.intern.Plot
import jetbrains.letsPlot.intern.toSpec
import tmp.LetsPlotHtml

fun Plot.getHtml(): String {
    val plotSpec = PlotConfigServerSide.processTransform(this.toSpec())
    val plotSpecJs = JsObjectSupport.mapToJsObjectInitializer(plotSpec)
    return LetsPlotHtml.getStaticScriptPlotDisplayHtml(plotSpecJs)
}

fun PlotSpec.getHtml(): String {
    val plotSpec= PlotConfigServerSide.processTransform(spec)
    val plotSpecJs = JsObjectSupport.mapToJsObjectInitializer(plotSpec)
    return LetsPlotHtml.getStaticScriptPlotDisplayHtml(plotSpecJs)
}