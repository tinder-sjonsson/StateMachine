package com.tinder

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.then
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.Ignore
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

@RunWith(Enclosed::class)
internal class NativeStateMachineTest {

    class SimpleStateMachine {
        private fun createSateMachine(): StateMachine<State, Event, Unit> =
            StateMachine.createSimple(
                initialState = State.One
            ) { state, event ->
                when (state) {
                    State.One -> when (event) {
                        Event.Down -> state
                        Event.Up -> State.Two
                    }
                    State.Two -> when (event) {
                        Event.Down -> State.One
                        Event.Up -> State.Three
                    }
                    State.Three -> when (event) {
                        Event.Down -> State.Two
                        else -> state
                    }
                }
            }

        sealed class State {
            object One : State()
            object Two : State()
            object Three : State()
        }

        sealed class Event {
            object Down : Event()
            object Up : Event()
        }

        @Test
        fun `initialState should be One`() {
            // Given
            val stateMachine = createSateMachine()

            // Then
            assertThat(stateMachine.state).isEqualTo(State.One)
        }

        @Test
        fun `given One, on Up, should transition to Two`() {
            // Given
            val stateMachine = createSateMachine()

            // When
            stateMachine.transition(Event.Up)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Two)
        }

        @Test
        fun `given One, on Up twice, should transition to Three`() {
            // Given
            val stateMachine = createSateMachine()

            // When
            stateMachine.transition(Event.Up)
            stateMachine.transition(Event.Up)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Three)
        }

        @Test
        fun `given One, on Up three times, should stop at Three`() {
            // Given
            val stateMachine = createSateMachine()

            // When
            stateMachine.transition(Event.Up)
            stateMachine.transition(Event.Up)
            stateMachine.transition(Event.Up)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Three)
        }
    }

    class MatterStateMachine {

        private val logger = mock<Logger>()

        private fun createStateMachine(
            initialState: State = State.Solid
        ): StateMachine<State, Event, SideEffect> = StateMachine.create(
            initialState = initialState,
            onTransition = {
                val validTransition = it as? StateMachine.Transition.Valid ?: return@create
                when (validTransition.sideEffect) {
                    SideEffect.LogMelted -> logger.log(ON_MELTED_MESSAGE)
                    SideEffect.LogFrozen -> logger.log(ON_FROZEN_MESSAGE)
                    SideEffect.LogVaporized -> logger.log(ON_VAPORIZED_MESSAGE)
                    SideEffect.LogCondensed -> logger.log(ON_CONDENSED_MESSAGE)
                }
            },
            reducer = { state, event ->
                when (state) {
                    State.Solid -> when (event) {
                        Event.OnMelted -> State.Liquid to SideEffect.LogMelted
                        else -> null
                    }
                    State.Liquid -> when (event) {
                        Event.OnFrozen -> State.Solid to SideEffect.LogFrozen
                        Event.OnVaporized -> State.Gas to SideEffect.LogVaporized
                        else -> null
                    }
                    State.Gas -> when (event) {
                        Event.OnCondensed -> State.Liquid to SideEffect.LogCondensed
                        else -> null
                    }
                }
            }
        )

        @Test
        fun initialState_shouldBeSolid() {
            // Given
            val stateMachine = createStateMachine()

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Solid)
        }

        @Test
        fun givenStateIsSolid_onMelted_shouldTransitionToLiquidStateAndLog() {
            // Given
            val stateMachine = createStateMachine(State.Solid)

            // When
            val transition = stateMachine.transition(Event.OnMelted)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Liquid)
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Solid,
                    Event.OnMelted,
                    State.Liquid,
                    SideEffect.LogMelted
                )
            )
            then(logger).should().log(ON_MELTED_MESSAGE)
        }

        @Test
        fun givenStateIsLiquid_onFroze_shouldTransitionToSolidStateAndLog() {
            // Given
            val stateMachine = createStateMachine(State.Liquid)

            // When
            val transition = stateMachine.transition(Event.OnFrozen)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Solid)
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Liquid,
                    Event.OnFrozen,
                    State.Solid,
                    SideEffect.LogFrozen
                )
            )
            then(logger).should().log(ON_FROZEN_MESSAGE)
        }

        @Test
        fun givenStateIsLiquid_onVaporized_shouldTransitionToGasStateAndLog() {
            // Given
            val stateMachine = createStateMachine(State.Liquid)

            // When
            val transition = stateMachine.transition(Event.OnVaporized)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Gas)
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Liquid,
                    Event.OnVaporized,
                    State.Gas,
                    SideEffect.LogVaporized
                )
            )
            then(logger).should().log(ON_VAPORIZED_MESSAGE)
        }

        @Test
        fun givenStateIsGas_onCondensed_shouldTransitionToLiquidStateAndLog() {
            // Given
            val stateMachine = createStateMachine(State.Gas)

            // When
            val transition = stateMachine.transition(Event.OnCondensed)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Liquid)
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Gas,
                    Event.OnCondensed,
                    State.Liquid,
                    SideEffect.LogCondensed
                )
            )
            then(logger).should().log(ON_CONDENSED_MESSAGE)
        }

        companion object {
            const val ON_MELTED_MESSAGE = "I melted"
            const val ON_FROZEN_MESSAGE = "I froze"
            const val ON_VAPORIZED_MESSAGE = "I vaporized"
            const val ON_CONDENSED_MESSAGE = "I condensed"

            sealed class State {
                object Solid : State()
                object Liquid : State()
                object Gas : State()
            }

            sealed class Event {
                object OnMelted : Event()
                object OnFrozen : Event()
                object OnVaporized : Event()
                object OnCondensed : Event()
            }

            sealed class SideEffect {
                object LogMelted : SideEffect()
                object LogFrozen : SideEffect()
                object LogVaporized : SideEffect()
                object LogCondensed : SideEffect()
            }

            interface Logger {
                fun log(message: String)
            }
        }
    }

    class TurnstileStateMachine {

        private fun createStateMachine(
            initialState: State = State.Locked(credit = 0)
        ): StateMachine<State, Event, Command> = StateMachine.create(
            initialState = initialState,
            reducer = { state, event ->
                when (state) {
                    is State.Locked -> when (event) {
                        is Event.InsertCoin -> {
                            val newCredit = state.credit + event.value
                            if (newCredit >= FARE_PRICE) {
                                State.Unlocked to Command.OpenDoors
                            } else {
                                State.Locked(newCredit) to null
                            }
                        }
                        is Event.AdmitPerson -> {
                            state to Command.SoundAlarm
                        }
                        is Event.MachineDidFail -> {
                            State.Broken(state) to Command.OrderRepair
                        }
                        Event.MachineRepairDidComplete -> null
                    }
                    is State.Unlocked -> when (event) {
                        is Event.AdmitPerson -> {
                            State.Locked(credit = 0) to Command.CloseDoors
                        }
                        else -> null
                    }
                    is State.Broken -> when (event) {
                        Event.MachineRepairDidComplete -> {
                            state.oldState to null
                        }
                        else -> null
                    }
                }
            }
        )


        @Test
        fun initialState_shouldBeLocked() {
            // Given
            val stateMachine = createStateMachine()

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Locked(credit = 0))
        }

        @Test
        fun givenStateIsLocked_whenInsertCoin_andCreditLessThanFairPrice_shouldTransitionToLockedState() {
            // Given
            val stateMachine = createStateMachine()

            // When
            val transition = stateMachine.transition(Event.InsertCoin(10))

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Locked(credit = 10))
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Locked(credit = 0),
                    Event.InsertCoin(10),
                    State.Locked(credit = 10),
                    null
                )
            )
        }

        @Test
        fun givenStateIsLocked_whenInsertCoin_andCreditEqualsFairPrice_shouldTransitionToUnlockedStateAndOpenDoors() {
            // Given
            val stateMachine = createStateMachine(State.Locked(credit = 35))

            // When
            val transition = stateMachine.transition(Event.InsertCoin(15))

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Unlocked)
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Locked(credit = 35),
                    Event.InsertCoin(15),
                    State.Unlocked,
                    Command.OpenDoors
                )
            )
        }

        @Test
        fun givenStateIsLocked_whenInsertCoin_andCreditMoreThanFairPrice_shouldTransitionToUnlockedStateAndOpenDoors() {
            // Given
            val stateMachine = createStateMachine(State.Locked(credit = 35))

            // When
            val transition = stateMachine.transition(Event.InsertCoin(20))

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Unlocked)
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Locked(credit = 35),
                    Event.InsertCoin(20),
                    State.Unlocked,
                    Command.OpenDoors
                )
            )
        }

        @Test
        fun givenStateIsLocked_whenAdmitPerson_shouldTransitionToLockedStateAndSoundAlarm() {
            // Given
            val stateMachine = createStateMachine(State.Locked(credit = 35))

            // When
            val transition = stateMachine.transition(Event.AdmitPerson)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Locked(credit = 35))
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Locked(credit = 35),
                    Event.AdmitPerson,
                    State.Locked(credit = 35),
                    Command.SoundAlarm
                )
            )
        }

        @Test
        fun givenStateIsLocked_whenMachineDidFail_shouldTransitionToBrokenStateAndOrderRepair() {
            // Given
            val stateMachine = createStateMachine(State.Locked(credit = 15))

            // When
            val transitionToBroken = stateMachine.transition(Event.MachineDidFail)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Broken(oldState = State.Locked(credit = 15)))
            assertThat(transitionToBroken).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Locked(credit = 15),
                    Event.MachineDidFail,
                    State.Broken(oldState = State.Locked(credit = 15)),
                    Command.OrderRepair
                )
            )
        }

        @Test
        fun givenStateIsUnlocked_whenAdmitPerson_shouldTransitionToLockedStateAndCloseDoors() {
            // Given
            val stateMachine = createStateMachine(State.Unlocked)

            // When
            val transition = stateMachine.transition(Event.AdmitPerson)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Locked(credit = 0))
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Unlocked,
                    Event.AdmitPerson,
                    State.Locked(credit = 0),
                    Command.CloseDoors
                )
            )
        }

        @Test
        fun givenStateIsBroken_whenMachineRepairDidComplete_shouldTransitionToLockedState() {
            // Given
            val stateMachine =
                createStateMachine(State.Broken(oldState = State.Locked(credit = 15)))

            // When
            val transition = stateMachine.transition(Event.MachineRepairDidComplete)

            // Then
            assertThat(stateMachine.state).isEqualTo(State.Locked(credit = 15))
            assertThat(transition).isEqualTo(
                StateMachine.Transition.Valid(
                    State.Broken(oldState = State.Locked(credit = 15)),
                    Event.MachineRepairDidComplete,
                    State.Locked(credit = 15),
                    null
                )
            )
        }

        companion object {
            private const val FARE_PRICE = 50

            sealed class State {
                data class Locked(val credit: Int) : State()
                object Unlocked : State()
                data class Broken(val oldState: State) : State()
            }

            sealed class Event {
                data class InsertCoin(val value: Int) : Event()
                object AdmitPerson : Event()
                object MachineDidFail : Event()
                object MachineRepairDidComplete : Event()
            }

            sealed class Command {
                object SoundAlarm : Command()
                object CloseDoors : Command()
                object OpenDoors : Command()
                object OrderRepair : Command()
            }
        }
    }

    @RunWith(Enclosed::class)
    class ObjectStateMachine {

        class WithInitialState {

            private val onTransitionListener1 =
                mock<(StateMachine.Transition<State, Event, SideEffect>) -> Unit>()
            private val onTransitionListener2 =
                mock<(StateMachine.Transition<State, Event, SideEffect>) -> Unit>()
            private val onStateAExitListener1 = mock<State.(Event) -> Unit>()
            private val onStateAExitListener2 = mock<State.(Event) -> Unit>()
            private val onStateCEnterListener1 = mock<State.(Event) -> Unit>()
            private val onStateCEnterListener2 = mock<State.(Event) -> Unit>()
            private val stateMachine = StateMachine.create<State, Event, SideEffect>(
                initialState = State.A,
                onTransition = {
                    val transition = it as? StateMachine.Transition.Valid ?: return@create
                    if (transition.fromState == State.A) {
                        onStateAExitListener1(transition.fromState, transition.event)
                        onStateAExitListener2(transition.fromState, transition.event)
                    }

                    if (transition.toState == State.C) {
                        onStateCEnterListener1(transition.toState, transition.event)
                        onStateCEnterListener2(transition.toState, transition.event)
                    }
                    onTransitionListener1(transition)
                    onTransitionListener2(transition)
                },
                reducer = { state, event ->
                    when (state) {
                        State.A -> when (event) {
                            Event.E1 -> transitionTo(State.B)
                            Event.E2 -> transitionTo(State.C)
                            Event.E3 -> null
                            Event.E4 -> transitionTo(State.D)
                        }
                        State.B -> when (event) {
                            is Event.E3 -> transitionTo(State.C, SideEffect.SE1)
                            else -> null
                        }
                        State.C -> when (event) {
                            is Event.E4 -> transitionTo(state)
                            else -> invalidTransition()
                        }
                        State.D -> invalidTransition()
                    }
                }
            )

            @Test
            fun state_shouldReturnInitialState() {
                // When
                val state = stateMachine.state

                // Then
                assertThat(state).isEqualTo(State.A)
            }

            @Test
            fun transition_givenValidEvent_shouldReturnTransition() {
                // When
                val transitionFromStateAToStateB = stateMachine.transition(Event.E1)

                // Then
                assertThat(transitionFromStateAToStateB).isEqualTo(
                    StateMachine.Transition.Valid(State.A, Event.E1, State.B, null)
                )

                // When
                val transitionFromStateBToStateC = stateMachine.transition(Event.E3)

                // Then
                assertThat(transitionFromStateBToStateC).isEqualTo(
                    StateMachine.Transition.Valid(State.B, Event.E3, State.C, SideEffect.SE1)
                )
            }

            @Test
            fun transition_givenValidEvent_shouldCreateAndSetNewState() {
                // When
                stateMachine.transition(Event.E1)

                // Then
                assertThat(stateMachine.state).isEqualTo(State.B)

                // When
                stateMachine.transition(Event.E3)

                // Then
                assertThat(stateMachine.state).isEqualTo(State.C)
            }

            @Test
            fun transition_givenValidEvent_shouldTriggerOnStateChangeListener() {
                // When
                stateMachine.transition(Event.E1)

                // Then
                then(onTransitionListener1).should().invoke(
                    StateMachine.Transition.Valid(State.A, Event.E1, State.B, null)
                )

                // When
                stateMachine.transition(Event.E3)

                // Then
                then(onTransitionListener2).should()
                    .invoke(
                        StateMachine.Transition.Valid(
                            State.B,
                            Event.E3,
                            State.C,
                            SideEffect.SE1
                        )
                    )

                // When
                stateMachine.transition(Event.E4)

                // Then
                then(onTransitionListener2).should()
                    .invoke(StateMachine.Transition.Valid(State.C, Event.E4, State.C, null))
            }

            @Test
            fun transition_givenValidEvent_shouldTriggerOnEnterListeners() {
                // When
                stateMachine.transition(Event.E2)

                // Then
                then(onStateCEnterListener1).should().invoke(State.C, Event.E2)
                then(onStateCEnterListener2).should().invoke(State.C, Event.E2)
            }

            @Test
            fun transition_givenValidEvent_shouldTriggerOnExitListeners() {
                // When
                stateMachine.transition(Event.E2)

                // Then
                then(onStateAExitListener1).should().invoke(State.A, Event.E2)
                then(onStateAExitListener2).should().invoke(State.A, Event.E2)
            }

            @Test
            fun transition_givenInvalidEvent_shouldReturnInvalidTransition() {
                // When
                val fromState = stateMachine.state
                val transition = stateMachine.transition(Event.E3)

                // Then
                assertThat(transition).isEqualTo(
                    StateMachine.Transition.Invalid<State, Event, SideEffect>(State.A, Event.E3)
                )
                assertThat(stateMachine.state).isEqualTo(fromState)
            }

            @Test
            @Ignore("N/A when doing kotlin only")
            fun transition_givenUndeclaredState_shouldThrowIllegalStateException() {
                // Then
                assertThatIllegalStateException().isThrownBy {
                    stateMachine.transition(Event.E4)
                }
            }
        }

        private companion object {
            private sealed class State {
                object A : State()
                object B : State()
                object C : State()
                object D : State()
            }

            private sealed class Event {
                object E1 : Event()
                object E2 : Event()
                object E3 : Event()
                object E4 : Event()
            }

            private sealed class SideEffect {
                object SE1 : SideEffect()
            }
        }
    }

    @RunWith(Enclosed::class)
    class ConstantStateMachine {

        class WithInitialState {

            private val onTransitionListener1 =
                mock<(StateMachine.Transition<String, Int, String>) -> Unit>()
            private val onTransitionListener2 =
                mock<(StateMachine.Transition<String, Int, String>) -> Unit>()
            private val onStateCEnterListener1 = mock<String.(Int) -> Unit>()
            private val onStateCEnterListener2 = mock<String.(Int) -> Unit>()
            private val onStateAExitListener1 = mock<String.(Int) -> Unit>()
            private val onStateAExitListener2 = mock<String.(Int) -> Unit>()
            private val stateMachine = StateMachine.create<String, Int, String>(
                initialState = STATE_A,
                onTransition = { transition ->
                    (transition as? StateMachine.Transition.Valid)?.let {
                        if (it.fromState == STATE_A) {
                            onStateAExitListener1(it.fromState, it.event)
                            onStateAExitListener2(it.fromState, it.event)
                        }
                        if (it.toState == STATE_C) {
                            onStateCEnterListener1(it.toState, it.event)
                            onStateCEnterListener2(it.toState, it.event)
                        }
                        onTransitionListener1(it)
                        onTransitionListener2(it)
                    }
                },
                reducer = { state, event ->
                    when (state) {
                        STATE_A -> when (event) {
                            EVENT_1 -> transitionTo(STATE_B)
                            EVENT_2 -> transitionTo(STATE_C)
                            EVENT_4 -> transitionTo(STATE_D)
                            else -> null
                        }
                        STATE_B -> when (event) {
                            EVENT_3 -> transitionTo(STATE_C, SIDE_EFFECT_1)
                            else -> null
                        }
                        else -> null
                    }
                }
            )

            @Test
            fun state_shouldReturnInitialState() {
                // When
                val state = stateMachine.state

                // Then
                assertThat(state).isEqualTo(STATE_A)
            }

            @Test
            fun transition_givenValidEvent_shouldReturnTrue() {
                // When
                val transitionFromStateAToStateB = stateMachine.transition(EVENT_1)

                // Then
                assertThat(transitionFromStateAToStateB).isEqualTo(
                    StateMachine.Transition.Valid(STATE_A, EVENT_1, STATE_B, null)
                )

                // When
                val transitionFromStateBToStateC = stateMachine.transition(EVENT_3)

                // Then
                assertThat(transitionFromStateBToStateC).isEqualTo(
                    StateMachine.Transition.Valid(STATE_B, EVENT_3, STATE_C, SIDE_EFFECT_1)
                )
            }

            @Test
            fun transition_givenValidEvent_shouldCreateAndSetNewState() {
                // When
                stateMachine.transition(EVENT_1)

                // Then
                assertThat(stateMachine.state).isEqualTo(STATE_B)

                // When
                stateMachine.transition(EVENT_3)

                // Then
                assertThat(stateMachine.state).isEqualTo(STATE_C)
            }

            @Test
            fun transition_givenValidEvent_shouldTriggerOnStateChangeListener() {
                // When
                stateMachine.transition(EVENT_1)

                // Then
                then(onTransitionListener1).should().invoke(
                    StateMachine.Transition.Valid(STATE_A, EVENT_1, STATE_B, null)
                )

                // When
                stateMachine.transition(EVENT_3)

                // Then
                then(onTransitionListener2).should().invoke(
                    StateMachine.Transition.Valid(STATE_B, EVENT_3, STATE_C, SIDE_EFFECT_1)
                )
            }

            @Test
            fun transition_givenValidEvent_shouldTriggerOnEnterListeners() {
                // When
                stateMachine.transition(EVENT_2)

                // Then
                then(onStateCEnterListener1).should().invoke(STATE_C, EVENT_2)
                then(onStateCEnterListener2).should().invoke(STATE_C, EVENT_2)
            }

            @Test
            fun transition_givenValidEvent_shouldTriggerOnExitListeners() {
                // When
                stateMachine.transition(EVENT_2)

                // Then
                then(onStateAExitListener1).should().invoke(STATE_A, EVENT_2)
                then(onStateAExitListener2).should().invoke(STATE_A, EVENT_2)
            }

            @Test
            fun transition_givenInvalidEvent_shouldReturnInvalidTransition() {
                // When
                val fromState = stateMachine.state
                val transition = stateMachine.transition(EVENT_3)

                // Then
                assertThat(transition).isEqualTo(
                    StateMachine.Transition.Invalid<String, Int, String>(STATE_A, EVENT_3)
                )
                assertThat(stateMachine.state).isEqualTo(fromState)
            }

            @Test
            @Ignore("N/A with native. All states matching type are valid")
            fun transition_givenUndeclaredState_shouldThrowIllegalStateException() {
                // Then
                assertThatIllegalStateException()
                    .isThrownBy {
                        stateMachine.transition(EVENT_4)
                    }
            }
        }

        private companion object {
            private const val STATE_A = "a"
            private const val STATE_B = "b"
            private const val STATE_C = "c"
            private const val STATE_D = "d"

            private const val EVENT_1 = 1
            private const val EVENT_2 = 2
            private const val EVENT_3 = 3
            private const val EVENT_4 = 4

            private const val SIDE_EFFECT_1 = "alpha"
        }
    }

}
