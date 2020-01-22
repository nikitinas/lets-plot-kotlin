/*
 * Copyright (c) 2020. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package jetbrains.letsPlot.api

import jetbrains.datalore.plot.config.Option
import jetbrains.datalore.plot.config.Option.Meta.Kind.PLOT
import jetbrains.letsPlot.intern.GeomKind
import jetbrains.letsPlot.intern.PosKind
import jetbrains.letsPlot.intern.StatKind
import jetbrains.letsPlot.intern.filterNonNullValues
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

open class BindableProperty<C, T>(val name: String) {

    var mapping: Getter<C, T>? = null

    operator fun invoke(mapping: Getter<C, T>) = map(mapping)

    fun map(mapping: Getter<C, T>) {
        this.mapping = mapping
    }
}

class WriteableProperty<C, T>(name: String) : BindableProperty<C, T>(name) {

    var constValue: T? = null

    operator fun invoke(value: T) = set(value)

    fun set(value: T) {
        this.constValue = value
    }
}

open class BuilderBase<T>(val bindings: DataBindings<T>) {

    class WriteablePropertyProvider<C, T>(private val owner: BuilderBase<C>) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): WriteableProperty<C, T> {
            return owner.writeableProperties.getOrPut(property.name) { WriteableProperty<C, T>(property.name) } as WriteableProperty<C, T>
        }
    }

    class BindablePropertyProvider<C, T>(private val owner: BuilderBase<C>) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): BindableProperty<C, T> {
            return owner.bindableProperties.getOrPut(property.name) { BindableProperty<C, T>(property.name) } as BindableProperty<C, T>
        }
    }

    val data: Iterable<T> get() = bindings.data

    internal val writeableProperties = mutableMapOf<String, WriteableProperty<T, *>>()

    internal val bindableProperties = mutableMapOf<String, BindableProperty<T, *>>()

    protected fun <P> prop() = WriteablePropertyProvider<T, P>(this)

    protected fun <P> bindProp() = BindablePropertyProvider<T, P>(this)

    fun <P> map(selector: T.() -> P) = selector

    internal open fun getSpec(): Map<String, Any> = collectParameters() + (Option.Plot.MAPPING to collectMappings())

    private fun collectParameters() =
        writeableProperties.mapValues {
            it.value.constValue
        }.filterNonNullValues()

    private fun collectMappings() =
        (writeableProperties + bindableProperties).mapValues {
            it.value.mapping?.let(bindings::getDataName)
        }.filterNonNullValues()
}

class PlotBuilder<T>(data: DataBindings<T>) : GenericBuilder<T>(data) {

    val x by bindProp<Any>()
    val y by bindProp<Any>()

    private val layers = mutableListOf<BuilderBase<*>>()

    internal fun <C, B : BuilderBase<C>> addLayer(builder: B, body: B.() -> Unit) {
        layers.add(builder)
        body(builder)
    }

    internal fun <C> createContext(data: Iterable<C>) = LayerContext<C>(bindings.getManager(data), this)

    override fun getSpec() = super.getSpec() + mapOf(
        Option.Meta.KIND to PLOT,
        Option.Plot.LAYERS to layers.map { it.getSpec() },
        Option.Plot.DATA to bindings.dataSource
    )

}

fun <T> PlotBuilder<*>.lines(data: Iterable<T>, body: LinesLayer<T>.() -> Unit) =
    addLayer(LinesLayer(createContext(data)), body)

fun <T> PlotBuilder<T>.lines(body: LinesLayer<T>.() -> Unit = {}) = lines(data, body)

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

data class LayerContext<T>(val bindingsManager: DataBindings<T>, val plot: PlotBuilder<*>)

open class LayerBuilder<T>(
    context: LayerContext<T>,
    private val geomKind: GeomKind,
    var statKind: StatKind = StatKind.IDENTITY,
    var posKind: PosKind = PosKind.IDENTITY
) : GenericBuilder<T>(context.bindingsManager) {

    private val plot: PlotBuilder<*> = context.plot
    override fun getSpec() = super.getSpec() +
            mapOf<String, Any>(
                Option.Layer.GEOM to geomKind.optionName(),
                Option.Layer.STAT to statKind.optionName(),
                Option.Layer.POS to posKind.optionName()
            ).let {
                if (bindings.data != plot.bindings.data) it + (Option.Layer.DATA to bindings.dataSource)
                else it
            }
}

open class GenericBuilder<T>(bindings: DataBindings<T>) : BuilderBase<T>(bindings) {
    val alpha by prop<Double>()
    val color by prop<String>()
    val fill by prop<String>()
}

open class XYNumbersLayer<T>(
    context: LayerContext<T>, geomKind: GeomKind, statKind: StatKind = StatKind.IDENTITY,
    posKind: PosKind = PosKind.IDENTITY
) :
    LayerBuilder<T>(context, geomKind, statKind, posKind) {

    val x by prop<Number>()
    val y by prop<Number>()
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

class BarsLayer<T>(context: LayerContext<T>) : LayerBuilder<T>(context, GeomKind.BAR, statKind = StatKind.COUNT, posKind = PosKind.STACK) {
    val x by prop<Any>()
    val y by prop<Any>()
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