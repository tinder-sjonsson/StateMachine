package com.tinder

import java.util.concurrent.atomic.AtomicReference

fun <STATE : Any, EVENT : Any> transitionTo(
    state: STATE,
    event: EVENT? = null
): Pair<STATE, EVENT?>? {
    return Pair(state, event)
}

fun <STATE : Any, EVENT : Any> invalidTransition(): Pair<STATE, EVENT?>? = null

interface StateMachine<STATE : Any, EVENT : Any, SIDE_EFFECT : Any> {

    val state: STATE
    fun transition(event: EVENT): Transition<STATE, EVENT, SIDE_EFFECT>

    @Suppress("UNUSED")
    sealed class Transition<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> {
        abstract val fromState: STATE
        abstract val event: EVENT

        data class Valid<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> internal constructor(
            override val fromState: STATE,
            override val event: EVENT,
            val toState: STATE,
            val sideEffect: SIDE_EFFECT?
        ) : Transition<STATE, EVENT, SIDE_EFFECT>()

        data class Invalid<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> internal constructor(
            override val fromState: STATE,
            override val event: EVENT
        ) : Transition<STATE, EVENT, SIDE_EFFECT>()
    }

    companion object {

        fun <STATE : Any, EVENT : Any, SIDE_EFFECT : Any> createSimple(
            initialState: STATE,
            onTransition: ((Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit)? = null,
            reducer: (STATE, EVENT) -> STATE?
        ): StateMachine<STATE, EVENT, SIDE_EFFECT> {
            return create(initialState, onTransition, { s, e -> reducer(s, e)?.let { it to null } })
        }

        fun <STATE : Any, EVENT : Any, SIDE_EFFECT : Any> create(
            initialState: STATE,
            onTransition: ((Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit)? = null,
            reducer: (STATE, EVENT) -> Pair<STATE, SIDE_EFFECT?>?
        ): StateMachine<STATE, EVENT, SIDE_EFFECT> {
            return NativeStateMachine(initialState, reducer, onTransition)
        }

        fun <STATE : Any, EVENT : Any, SIDE_EFFECT : Any> create(
            init: GraphStateMachine.GraphBuilder<STATE, EVENT, SIDE_EFFECT>.() -> Unit
        ): StateMachine<STATE, EVENT, SIDE_EFFECT> {
            return create(null, init)
        }

        internal fun <STATE : Any, EVENT : Any, SIDE_EFFECT : Any> create(
            graph: GraphStateMachine.Graph<STATE, EVENT, SIDE_EFFECT>?,
            init: GraphStateMachine.GraphBuilder<STATE, EVENT, SIDE_EFFECT>.() -> Unit
        ): StateMachine<STATE, EVENT, SIDE_EFFECT> {
            return GraphStateMachine(GraphStateMachine.GraphBuilder(graph).apply(init).build())
        }
    }
}

class NativeStateMachine<STATE : Any, EVENT : Any, SIDE_EFFECT : Any>(
    initialState: STATE,
    private val reducer: (STATE, EVENT) -> Pair<STATE, SIDE_EFFECT?>?,
    private val transitionListener: ((StateMachine.Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit)?
) : StateMachine<STATE, EVENT, SIDE_EFFECT> {

    override var state: STATE = initialState
        private set

    override fun transition(event: EVENT): StateMachine.Transition<STATE, EVENT, SIDE_EFFECT> {
        val transition: StateMachine.Transition<STATE, EVENT, SIDE_EFFECT> = synchronized(this) {
            val fromState = state
            reducer(fromState, event)?.let { (toState, sideEffect) ->
                state = toState
                StateMachine.Transition.Valid(fromState, event, toState, sideEffect)
            } ?: StateMachine.Transition.Invalid(fromState, event)
        }
        transitionListener?.invoke(transition)
        return transition
    }
}

class GraphStateMachine<STATE : Any, EVENT : Any, SIDE_EFFECT : Any> internal constructor(
    private val graph: Graph<STATE, EVENT, SIDE_EFFECT>
) : StateMachine<STATE, EVENT, SIDE_EFFECT> {

    private val stateRef = AtomicReference<STATE>(graph.initialState)

    override val state: STATE
        get() = stateRef.get()

    override fun transition(event: EVENT): StateMachine.Transition<STATE, EVENT, SIDE_EFFECT> {
        val transition = synchronized(this) {
            val fromState = stateRef.get()
            val transition = fromState.getTransition(event)
            if (transition is StateMachine.Transition.Valid) {
                stateRef.set(transition.toState)
            }
            transition
        }
        transition.notifyOnTransition()
        if (transition is StateMachine.Transition.Valid) {
            with(transition) {
                with(fromState) {
                    notifyOnExit(event)
                }
                with(toState) {
                    notifyOnEnter(event)
                }
            }
        }
        return transition
    }

    private fun STATE.getTransition(event: EVENT): StateMachine.Transition<STATE, EVENT, SIDE_EFFECT> {
        for ((eventMatcher, createTransitionTo) in getDefinition().transitions) {
            if (eventMatcher.matches(event)) {
                val (toState, sideEffect) = createTransitionTo(this, event)
                return StateMachine.Transition.Valid(this, event, toState, sideEffect)
            }
        }
        return StateMachine.Transition.Invalid(this, event)
    }

    private fun STATE.getDefinition() = graph.stateDefinitions
        .filter { it.key.matches(this) }
        .map { it.value }
        .firstOrNull() ?: error("Missing definition for state ${this.javaClass.simpleName}!")

    private fun STATE.notifyOnEnter(cause: EVENT) {
        getDefinition().onEnterListeners.forEach { it(this, cause) }
    }

    private fun STATE.notifyOnExit(cause: EVENT) {
        getDefinition().onExitListeners.forEach { it(this, cause) }
    }

    private fun StateMachine.Transition<STATE, EVENT, SIDE_EFFECT>.notifyOnTransition() {
        graph.onTransitionListeners.forEach { it(this) }
    }

    data class Graph<STATE : Any, EVENT : Any, SIDE_EFFECT : Any>(
        val initialState: STATE,
        val stateDefinitions: Map<Matcher<STATE, STATE>, State<STATE, EVENT, SIDE_EFFECT>>,
        val onTransitionListeners: List<(StateMachine.Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit>
    ) {

        class State<STATE : Any, EVENT : Any, SIDE_EFFECT : Any> internal constructor() {
            val onEnterListeners = mutableListOf<(STATE, EVENT) -> Unit>()
            val onExitListeners = mutableListOf<(STATE, EVENT) -> Unit>()
            val transitions =
                linkedMapOf<Matcher<EVENT, EVENT>, (STATE, EVENT) -> TransitionTo<STATE, SIDE_EFFECT>>()

            data class TransitionTo<out STATE : Any, out SIDE_EFFECT : Any> internal constructor(
                val toState: STATE,
                val sideEffect: SIDE_EFFECT?
            )
        }
    }

    class Matcher<T : Any, out R : T> private constructor(private val clazz: Class<R>) {

        private val predicates = mutableListOf<(T) -> Boolean>({ clazz.isInstance(it) })

        fun where(predicate: R.() -> Boolean): Matcher<T, R> = apply {
            predicates.add {
                @Suppress("UNCHECKED_CAST")
                (it as R).predicate()
            }
        }

        fun matches(value: T) = predicates.all { it(value) }

        companion object {
            fun <T : Any, R : T> any(clazz: Class<R>): Matcher<T, R> = Matcher(clazz)

            inline fun <T : Any, reified R : T> any(): Matcher<T, R> = any(R::class.java)

            inline fun <T : Any, reified R : T> eq(value: R): Matcher<T, R> =
                any<T, R>().where { this == value }
        }
    }

    class GraphBuilder<STATE : Any, EVENT : Any, SIDE_EFFECT : Any>(
        graph: Graph<STATE, EVENT, SIDE_EFFECT>? = null
    ) {
        private var initialState = graph?.initialState
        private val stateDefinitions = LinkedHashMap(graph?.stateDefinitions ?: emptyMap())
        private val onTransitionListeners = ArrayList(graph?.onTransitionListeners ?: emptyList())

        fun initialState(initialState: STATE) {
            this.initialState = initialState
        }

        fun <S : STATE> state(
            stateMatcher: Matcher<STATE, S>,
            init: StateDefinitionBuilder<S>.() -> Unit
        ) {
            stateDefinitions[stateMatcher] = StateDefinitionBuilder<S>().apply(init).build()
        }

        inline fun <reified S : STATE> state(noinline init: StateDefinitionBuilder<S>.() -> Unit) {
            state(Matcher.any(), init)
        }

        inline fun <reified S : STATE> state(
            state: S,
            noinline init: StateDefinitionBuilder<S>.() -> Unit
        ) {
            state(Matcher.eq<STATE, S>(state), init)
        }

        fun onTransition(listener: (StateMachine.Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit) {
            onTransitionListeners.add(listener)
        }

        fun build(): Graph<STATE, EVENT, SIDE_EFFECT> {
            return Graph(
                requireNotNull(initialState),
                stateDefinitions.toMap(),
                onTransitionListeners.toList()
            )
        }

        inner class StateDefinitionBuilder<S : STATE> {

            private val stateDefinition = Graph.State<STATE, EVENT, SIDE_EFFECT>()

            inline fun <reified E : EVENT> any(): Matcher<EVENT, E> = Matcher.any()

            inline fun <reified R : EVENT> eq(value: R): Matcher<EVENT, R> = Matcher.eq(value)

            fun <E : EVENT> on(
                eventMatcher: Matcher<EVENT, E>,
                createTransitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>
            ) {
                stateDefinition.transitions[eventMatcher] = { state, event ->
                    @Suppress("UNCHECKED_CAST")
                    createTransitionTo((state as S), event as E)
                }
            }

            inline fun <reified E : EVENT> on(
                noinline createTransitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>
            ) {
                return on(any(), createTransitionTo)
            }

            inline fun <reified E : EVENT> on(
                event: E,
                noinline createTransitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>
            ) {
                return on(eq(event), createTransitionTo)
            }

            fun onEnter(listener: S.(EVENT) -> Unit) = with(stateDefinition) {
                onEnterListeners.add { state, cause ->
                    @Suppress("UNCHECKED_CAST")
                    listener(state as S, cause)
                }
            }

            fun onExit(listener: S.(EVENT) -> Unit) = with(stateDefinition) {
                onExitListeners.add { state, cause ->
                    @Suppress("UNCHECKED_CAST")
                    listener(state as S, cause)
                }
            }

            fun build() = stateDefinition

            @Suppress("UNUSED") // The unused warning is probably a compiler bug.
            fun S.transitionTo(state: STATE, sideEffect: SIDE_EFFECT? = null) =
                Graph.State.TransitionTo(state, sideEffect)

            @Suppress("UNUSED") // The unused warning is probably a compiler bug.
            fun S.dontTransition(sideEffect: SIDE_EFFECT? = null) = transitionTo(this, sideEffect)
        }
    }
}