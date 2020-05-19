/*
 * Copyright (c) 2020. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package jetbrains.letsPlot.api

import jetbrains.datalore.plot.base.Aes
import jetbrains.datalore.plot.config.Option
import jetbrains.datalore.plot.config.Option.Meta.Kind.PLOT
import jetbrains.datalore.plot.config.Option.Scale.AES
import jetbrains.datalore.plot.config.Option.Scale.DATE_TIME
import jetbrains.letsPlot.Pos
import jetbrains.letsPlot.Stat
import jetbrains.letsPlot.ggsize
import jetbrains.letsPlot.intern.*
import jetbrains.letsPlot.intern.layer.PosOptions
import jetbrains.letsPlot.intern.layer.StatOptions
import kotlin.reflect.KProperty

typealias Getter<C, T> = C.(C) -> T

typealias Mapping<T> = Getter<T, Any?>

class DataBindings<T>(val data: Iterable<T>, val owner: BindingsManager) {

    private val names = mutableMapOf<Mapping<T>, String>()

    private var isFinalized = false

    fun getDataName(mapping: Mapping<T>) =
        names.getOrPut(mapping, { if (isFinalized) throw Exception() else "list${names.size}" })

    fun <C> getManager(values: Iterable<C>) = owner.getManager(values)

    val dataSource by lazy {
        names.map { it.value to data.map { v -> it.key(v, v) } }.toMap().also { isFinalized = true }
    }
}

class BindingsManager {

    private val bindingsMap = mutableMapOf<Iterable<*>, DataBindings<*>>()
    fun <T> getManager(data: Iterable<T>) =
        bindingsMap.getOrPut(data, { DataBindings(data, this) }) as DataBindings<T>

}

fun <C,T,P: BindableProperty<C,T>> P.set(values: Iterable<T>): P {
    this.values = values
    return this
}

fun <C,T,P: BindableProperty<C,T>> P.map(mapping: Getter<C, T>): P {
    this.mapping = mapping
    return this
}

operator fun <C,T,P: BindableProperty<C,T>> P.invoke(mapping: Getter<C, T>) = map(mapping)

infix fun <C,T,P: BindableProperty<C,T>> P.to(mapping: Getter<C, T>) = map(mapping)

infix fun <C,T,P: BindableProperty<C,T>> P.of(mapping: Getter<C, T>) = map(mapping)

operator fun <C,T,P: BindableProperty<C,T>> P.invoke(values: Iterable<T>) = set(values)

operator fun <C,T,P: BindableProperty<C,T>> P.minus(mapping: Getter<C, T>) = map(mapping)

open class BindableProperty<C, T>(val name: String) {

    var mapping: Getter<C, T>? = null

    var values: Iterable<T>? = null
}

class ScaleableProperty<C,T>(name: String, val aes: Aes<*>) : BindableProperty<C,T>(name) {

    var scale: Scale? = null

    fun datetime(
        name: String? = null, breaks: List<Any>? = null,
        labels: List<String>? = null,
        limits: List<Any>? = null,
        expand: Any? = null,
        na_value: Any? = null
    ) {
        scale = Scale(
            aes,
            name, breaks, labels, limits, expand, na_value,
            otherOptions = Options(
                mapOf(
                    DATE_TIME to true
                )
            )
        )
    }


}

class WriteableProperty<C, T>(name: String) : BindableProperty<C, T>(name) {

    var constValue: T? = null

    operator fun invoke(value: T) = set(value)

    infix fun to(value: T) = set(value)

    infix fun of(value: T) = set(value)

    operator fun minus(value: T) = set(value)

    operator fun plusAssign(value: T) {
        set(value)
    }

    operator fun compareTo(value: T): Int{
        set(value)
        return 0
    }

    fun set(value: T) {
        this.constValue = value
    }
}

open class BuilderBase<T>(val bindings: DataBindings<T>) {

    class PropertyProvider<C, T, P: BindableProperty<C,T>>(private val owner: BuilderBase<C>, val create: (String)->P) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): P {
            return owner.properties.getOrPut(property.name) { create(property.name) } as P
        }
    }

    val data: Iterable<T> get() = bindings.data

    internal val properties = mutableMapOf<String, BindableProperty<T, *>>()

    protected fun <P> prop() = PropertyProvider<T, P, WriteableProperty<T,P>>(this){WriteableProperty(it)}

    protected fun <P> bindProp() = PropertyProvider<T, P, BindableProperty<T,P>>(this){BindableProperty(it)}

    protected fun <P> scaleProp(aes: Aes<*>) = PropertyProvider<T, P, ScaleableProperty<T,P>>(this){ ScaleableProperty(it, aes) }

    fun <P> map(selector: Getter<T, P>) = selector

    internal open fun getSpec(): Map<String, Any> = collectParameters() + (Option.Plot.MAPPING to collectMappings())

    private fun collectParameters() =
        properties.mapValues { (it.value as? WriteableProperty<T,*>)?.constValue }
            .filterNonNullValues()

    private fun collectMappings() =
        properties.mapValues {
            it.value.mapping?.let(bindings::getDataName)
        }.filterNonNullValues()

}

class PlotBuilder<T>(data: DataBindings<T>) : GenericBuilder<T>(data) {

    val x by scaleProp<Any>(Aes.X)
    val y by scaleProp<Any>(Aes.Y)

    private val layers = mutableListOf<BuilderBase<*>>()

    private val otherFeatures = mutableListOf<OtherPlotFeature>()

    private fun collectScales() =
        properties.mapNotNull { (it.value as? ScaleableProperty<T,*>)?.scale }

    internal fun <C, B : BuilderBase<C>> addLayer(builder: B, body: B.() -> Unit) {
        layers.add(builder)
        body(builder)
    }

    internal fun <C> createContext(data: Iterable<C>) = LayerContext<C>(bindings.getManager(data), this)

    override fun getSpec() = super.getSpec() + mapOf(
        Option.Meta.KIND to PLOT,
        Option.Plot.LAYERS to layers.map { it.getSpec() },
        Option.Plot.DATA to bindings.dataSource,
        Option.Plot.SCALES to collectScales().map { it.toSpec() }
    ) + otherFeatures.map { it.kind to it.toSpec()}

    operator fun OtherPlotFeature.unaryPlus() {otherFeatures.add(this)}
}

fun PlotBuilder<*>.size(width: Int, height: Int) =
    +ggsize(width, height)

fun <T> PlotBuilder<*>.line(data: Iterable<T>, body: LinesLayer<T>.() -> Unit) =
    addLayer(LinesLayer(createContext(data)), body)

fun <T> PlotBuilder<T>.line(body: LinesLayer<T>.() -> Unit = {}) = line(data, body)

fun <T> PlotBuilder<*>.points(data: Iterable<T>, body: PointsLayer<T>.() -> Unit) =
    addLayer(PointsLayer(createContext(data)), body)

fun <T> PlotBuilder<T>.points(body: PointsLayer<T>.() -> Unit = {}) = points(data, body)

fun <T> PlotBuilder<*>.vlines(data: Iterable<T>, body: VLinesLayer<T>.() -> Unit) =
    addLayer(VLinesLayer(createContext(data)), body)

fun <T> PlotBuilder<T>.vlines(body: VLinesLayer<T>.() -> Unit = {}) = vlines(data, body)

fun <T> PlotBuilder<*>.hlines(data: Iterable<T>, body: HLinesLayer<T>.() -> Unit) =
    addLayer(HLinesLayer(createContext(data)), body)

fun <T> PlotBuilder<T>.hlines(body: HLinesLayer<T>.() -> Unit = {}) = hlines(data, body)

fun <T> PlotBuilder<*>.bars(data: Iterable<T>, body: BarsLayer<T>.() -> Unit) =
    addLayer(BarsLayer(createContext(data)), body)

fun <T> PlotBuilder<T>.bars(body: BarsLayer<T>.() -> Unit = {}) = bars(data, body)

fun <T> PlotBuilder<*>.area(data: Iterable<T>, body: AreaLayer<T>.() -> Unit) =
    addLayer(AreaLayer(createContext(data)), body)

fun <T> PlotBuilder<T>.area(body: AreaLayer<T>.() -> Unit = {}) = area(data, body)

fun <T> PlotBuilder<*>.density(data: Iterable<T>, body: DensityLayer<T>.() -> Unit) =
    addLayer(DensityLayer(createContext(data)), body)

fun <T> PlotBuilder<T>.density(body: DensityLayer<T>.() -> Unit = {}) = density(data, body)

fun <T> PlotBuilder<*>.histogram(data: Iterable<T>, body: HistogramLayer<T>.() -> Unit) =
    addLayer(HistogramLayer(createContext(data)), body)

fun <T> PlotBuilder<T>.histogram(body: HistogramLayer<T>.() -> Unit = {}) = histogram(data, body)

data class LayerContext<T>(val bindingsManager: DataBindings<T>, val plot: PlotBuilder<*>)

open class LayerBuilder<T>(
    context: LayerContext<T>,
    private val geomKind: GeomKind,
    var stat: StatOptions = Stat.identity,
    var position: PosOptions = Pos.identity
) : GenericBuilder<T>(context.bindingsManager) {

    private val plot: PlotBuilder<*> = context.plot
    override fun getSpec() = (super.getSpec() +
            mapOf<String, Any>(
                Option.Layer.GEOM to geomKind.optionName(),
                Option.Layer.STAT to stat.kind.optionName(),
                Option.Layer.POS to position.kind.optionName()
            ) + stat.parameters.map).let {
        if (bindings.data != plot.bindings.data) it + (Option.Layer.DATA to bindings.dataSource)
        else it
    }

    val density get() = Stat.density()
    val count get() = Stat.count()
    val bin get() = Stat.bin()
    val boxplot get() = Stat.boxplot()
}

open class GenericBuilder<T>(bindings: DataBindings<T>) : BuilderBase<T>(bindings) {
    val alpha by prop<Double>()
    val color by prop<String>()
    val fill by prop<String>()
}

open class XYNumbersLayer<T>(
    context: LayerContext<T>, geomKind: GeomKind, stat: StatOptions = Stat.identity,
    position: PosOptions = Pos.identity
) :
    LayerBuilder<T>(context, geomKind, stat, position) {

    val x by bindProp<Any>()
    val y by bindProp<Any>()
}

class LinesLayer<T>(context: LayerContext<T>) : XYNumbersLayer<T>(context, GeomKind.LINE) {
    val linetype by prop<String>()
    val size by prop<Number>()
}

class PointsLayer<T>(context: LayerContext<T>) : XYNumbersLayer<T>(context, GeomKind.POINT) {
    val shape by prop<String>()
    val stroke by prop<String>()
    val size by prop<Number>()
}

class VLinesLayer<T>(context: LayerContext<T>) : LayerBuilder<T>(context, GeomKind.V_LINE) {
    val xintercept by prop<Number>()
    val x = xintercept
    val linetype by prop<String>()
}

class HLinesLayer<T>(context: LayerContext<T>) : LayerBuilder<T>(context, GeomKind.H_LINE) {
    val yintercept by prop<Number>()
    val y = yintercept
    val linetype by prop<String>()
}

class BarsLayer<T>(context: LayerContext<T>) : LayerBuilder<T>(context, GeomKind.BAR, stat = Stat.count(), position = Pos.stack) {
    val x by bindProp<Any>()
    val y by bindProp<Any>()
    val width by prop<Double>()
    val size by prop<Double>()
}

class AreaLayer<T>(context: LayerContext<T>) : LayerBuilder<T>(context, GeomKind.AREA, position = Pos.stack) {
    val x by bindProp<Double>()
    val y by bindProp<Double>()
    val linetype by prop<String>()
    val size by prop<Double>()
}

class DensityLayer<T>(context: LayerContext<T>) :
    LayerBuilder<T>(context, GeomKind.DENSITY, stat = Stat.density()) {
    val x by bindProp<Double>()
    val y by bindProp<Double>()
    val width by prop<Double>()
    val size by prop<Double>()
}

class HistogramLayer<T>(context: LayerContext<T>) :
    LayerBuilder<T>(context, GeomKind.HISTOGRAM, stat = Stat.bin(), position = Pos.stack) {
    val x by bindProp<Double>()
    val y by bindProp<Double>()
    val width by prop<Double>()
    val size by prop<Double>()
}

fun <T> Iterable<T>.lets_plot(body: PlotBuilder<T>.() -> Unit): PlotSpec {
    val bindings = BindingsManager()
    val builder = PlotBuilder(bindings.getManager(this))
    body(builder)
    return PlotSpec(builder.getSpec().toMutableMap())
}

data class PlotSpec(val spec: MutableMap<String, Any>)

fun test() {
    val data = listOf(1 to 2, 2 to 3)
    data.lets_plot {
        x { first }.datetime("date")
        line {
            x { first }
            y to "a"
        }
        bars {
            stat = density
        }
        +ggsize(100,10)
    }
}