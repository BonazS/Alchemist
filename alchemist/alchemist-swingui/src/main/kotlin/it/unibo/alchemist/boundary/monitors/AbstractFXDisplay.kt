/*
 * Copyright (C) 2010-2018, Danilo Pianini and contributors listed in the main
 * project's alchemist/build.gradle file.
 *
 * This file is part of Alchemist, and is distributed under the terms of the
 * GNU General Public License, with a linking exception, as described in the file
 * LICENSE in the Alchemist distribution's top directory.
 */

package it.unibo.alchemist.boundary.monitors

import it.unibo.alchemist.boundary.gui.effects.EffectGroup
import it.unibo.alchemist.boundary.gui.utility.DataFormatFactory
import it.unibo.alchemist.boundary.interfaces.DrawCommand
import it.unibo.alchemist.boundary.interfaces.FXOutputMonitor
import it.unibo.alchemist.boundary.wormhole.implementation.Wormhole2D
import it.unibo.alchemist.boundary.wormhole.interfaces.BidimensionalWormhole
import it.unibo.alchemist.model.implementations.times.DoubleTime
import it.unibo.alchemist.model.interfaces.Concentration
import it.unibo.alchemist.model.interfaces.Environment
import it.unibo.alchemist.model.interfaces.Node
import it.unibo.alchemist.model.interfaces.Position
import it.unibo.alchemist.model.interfaces.Position2D
import it.unibo.alchemist.model.interfaces.Reaction
import it.unibo.alchemist.model.interfaces.Time
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.collections.ObservableMap
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Base abstract class for each display able to graphically represent a 2D space and simulation.
 *
 * @param <T> The type which describes the [Concentration] of a molecule
 * @param <P> The type of position
</P></T> */
abstract class AbstractFXDisplay<T>
/**
 * Main constructor. It lets the developer specify the number of steps.
 *
 * @param steps the number of steps
 * @see .setStep
 */
@JvmOverloads constructor(steps: Int = DEFAULT_NUMBER_OF_STEPS) : Canvas(), FXOutputMonitor<T, Position2D<*>> {

    private val effectStack: ObservableList<EffectGroup> = FXCollections.observableArrayList()
    private val mutex = Semaphore(1)
    private val mayRender = AtomicBoolean(true)
    private var step: Int = 0
    @Volatile private var firstTime: Boolean = false
    private var realTime: Boolean = false
    @Volatile private var commandQueue: ConcurrentLinkedQueue<() -> Unit> = ConcurrentLinkedQueue()
    private var viewStatus = DEFAULT_VIEW_STATUS
    protected lateinit var wormhole: BidimensionalWormhole<Position2D<*>>
        private set
    private var nodes: ObservableMap<Node<T>, Position2D<*>> = FXCollections.observableHashMap()
    protected var interactions: InteractionManager<T>? = null

    /**
     * - layer per highlight
     * - highlight sugli elementi selezionati
     * - highlight sugli elementi nel selectionBox
     * - aggiornare highlight se un elemento entra nel selectionBox
     * - aggiornare highlight al pan
     */

    init {
        firstTime = true
        setStep(steps)
        style = "-fx-background-color: #FFF;"
        isMouseTransparent = true
    }

    protected open fun initMouseListener() { }

    override fun getViewStatus(): FXOutputMonitor.ViewStatus {
        return this.viewStatus
    }

    override fun setViewStatus(viewStatus: FXOutputMonitor.ViewStatus) {
        this.viewStatus = viewStatus
    }

    override fun setModifier(modifier: FXOutputMonitor.KeyboardModifier, active: Boolean) {
        if (active) {
            interactions!!.keyboardModifiers += modifier
        } else {
            interactions!!.keyboardModifiers -= modifier
        }
    }

    override fun getStep(): Int {
        return this.step
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the step is not bigger than 0
     */
    @Throws(IllegalArgumentException::class)
    override fun setStep(step: Int) {
        if (step <= 0) {
            throw IllegalArgumentException("The parameter must be a positive integer")
        }
        this.step = step
    }

    override fun isRealTime(): Boolean {
        return this.realTime
    }

    override fun setRealTime(realTime: Boolean) {
        this.realTime = realTime
    }

    override fun repaint() {
        mutex.acquireUninterruptibly()
        try {
            if (mayRender.get() && isVisible && !isDisabled) {
                mayRender.set(false)
                Platform.runLater {
                    commandQueue.forEach { it() }
                    interactions!!.repaintFeedback()
                    mayRender.set(true)
                }
            }
        } catch (e: UninitializedPropertyAccessException) {
            // wormhole hasn't been initialized
        }
        mutex.release()
    }

    /**
     * Changes the background of the specified [GraphicsContext].
     *
     * @param graphicsContext the graphic component to draw on
     * @param environment the [Environment] that contains the data to pass to [Effects]
     * @return a function of what to do to draw the background
     * @see .repaint
     */
    protected fun drawBackground(graphicsContext: GraphicsContext, environment: Environment<T, Position2D<*>>): () -> Unit {
        return { graphicsContext.clearRect(0.0, 0.0, width, height) }
    }

    override fun addEffects(effects: Collection<EffectGroup>) {
        this.effectStack.addAll(effects)
    }

    override fun addEffectGroup(effects: EffectGroup) {
        this.effectStack.add(effects)
    }

    override fun getEffects(): Collection<EffectGroup> {
        return this.effectStack
    }

    override fun setEffects(effects: Collection<EffectGroup>) {
        this.effectStack.clear()
        this.effectStack.addAll(effects)
    }

    override fun setInteractionCanvas(input: Canvas, highlights: Canvas, selection: Canvas) {
        interactions = SimpleInteractionManager(input, highlights, selection, nodes, this)
        initMouseListener()
    }

    override fun initialized(environment: Environment<T, Position2D<*>>) {
        stepDone(environment, null, DoubleTime(), 0)
    }

    override fun stepDone(environment: Environment<T, Position2D<*>>, reaction: Reaction<T>?, time: Time, step: Long) {
        if (firstTime) {
            synchronized(this) {
                if (firstTime) {
                    init(environment)
                    update(environment, time)
                }
            }
        } else {
            update(environment, time)
        }
    }

    /**
     * The method initializes everything that is not initializable before first step.
     *
     * @param environment the `Environment`
     */
    protected open fun init(environment: Environment<T, Position2D<*>>) {
        wormhole = Wormhole2D(environment, this)
        wormhole.center()
        wormhole.optimalZoom()
        interactions!!.setWormhole(wormhole)
        firstTime = false
        System.currentTimeMillis()
    }

    override fun finished(environment: Environment<T, Position2D<*>>, time: Time, step: Long) {
        update(environment, time)
        firstTime = true
    }

    /**
     * Updates parameter for correct `Environment` representation.
     *
     * @param environment the `Environment`
     * @param time the current `Time` of simulation
     */
    private fun update(environment: Environment<T, Position2D<*>>, time: Time) {
        if (Thread.holdsLock(environment)) {
            environment.nodes.associate { Pair(it, environment.getPosition(it)) }.let { existingNodes ->
                nodes.filterKeys { it !in existingNodes }.forEach { nodes.remove(it.key) }
                existingNodes.filterKeys { it !in nodes.keys }.forEach { nodes[it.key] = it.value }
            }
            time.toDouble()
//            environment.simulation.schedule{ environment.moveNodeToPosition(environment.getNodeByID(0), LatLongPosition(8, 8)) }
            val graphicsContext = this.graphicsContext2D
            val background = Stream.of(drawBackground(graphicsContext, environment))
            val effects = effects
                .stream()
                .map<Queue<DrawCommand>> { group -> group.computeDrawCommands(environment) }
                .flatMap<DrawCommand> { it.stream() }
                .map { cmd -> { cmd.accept(graphicsContext, wormhole) } }
            commandQueue = Stream
                .concat(background, effects)
                .collect(Collectors.toCollection { ConcurrentLinkedQueue<() -> Unit>() })
            if (interactions != null) {
                interactions!!.simulationStep()
            }
            repaint()
        } else {
            throw IllegalStateException("Only the simulation thread can dictate GUI updates")
        }
    }

    companion object {
        /**
         * The default frame rate.
         */
        const val DEFAULT_FRAME_RATE: Byte = 60
        /**
         * The default time per frame.
         */
        const val TIME_STEP = (1 / DEFAULT_FRAME_RATE).toDouble()
        /**
         * Default number of steps.
         */
        const val DEFAULT_NUMBER_OF_STEPS = 1
        /**
         * Position `DataFormat`.
         */
        protected val POSITION_DATA_FORMAT = DataFormatFactory.getDataFormat(Position::class.java)
        /**
         * Default serial version UID.
         */
        private const val serialVersionUID = 1L
        /**
         * The default view status.
         */
        private val DEFAULT_VIEW_STATUS = FXOutputMonitor.ViewStatus.PANNING
    }
}
