package com.airbnb.mvrx.minified

import androidx.activity.ComponentActivity
import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.Mavericks
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.MavericksViewModel
import com.airbnb.mvrx.MavericksViewModelConfigFactory
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.MavericksViewModelProvider
import com.airbnb.mvrx.PersistState
import com.airbnb.mvrx.ViewModelContext
import com.airbnb.mvrx.persistMavericksState
import com.airbnb.mvrx.restorePersistedMavericksState
import com.airbnb.mvrx.withState
import java.io.Serializable

/**
 * Fixtures and an entry point that [MinifiedConsumerRulesTest] shrinks with R8 together with Mavericks, then runs from the
 * shrunk output. Nothing here may be referenced by the test itself, so R8 sees only the code below.
 */
data class PersistedState(
    @PersistState val count: Int = 0,
    val label: String = "initial",
    @PersistState val query: String = "",
) : MavericksState

data class PlainState(val count: Int = 0, val label: String = "initial") : MavericksState

data class FixtureArgs(val id: String) : Serializable

data class ArgsState(val id: String) : MavericksState {
    constructor(args: FixtureArgs) : this("args:${args.id}")
}

class DefaultViewModel(state: PlainState) : MavericksViewModel<PlainState>(state)

class ArgsViewModel(state: ArgsState) : MavericksViewModel<ArgsState>(state)

class FactoryViewModel(state: PlainState, val dependency: String) : MavericksViewModel<PlainState>(state) {
    companion object : MavericksViewModelFactory<FactoryViewModel, PlainState> {
        override fun create(viewModelContext: ViewModelContext, state: PlainState) = FactoryViewModel(state, "factory")

        override fun initialState(viewModelContext: ViewModelContext) = PlainState(label = "factory initial state")
    }
}

class JvmStaticFactoryViewModel(state: PlainState, val dependency: String) : MavericksViewModel<PlainState>(state) {
    companion object : MavericksViewModelFactory<JvmStaticFactoryViewModel, PlainState> {
        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: PlainState) = JvmStaticFactoryViewModel(state, "jvm static factory")

        @JvmStatic
        override fun initialState(viewModelContext: ViewModelContext) = PlainState(label = "jvm static initial state")
    }
}

object MinifiedHarness {
    /** Runs each reflective Mavericks path and returns the result, or the error, of each step by name. */
    @JvmStatic
    fun run(activity: Any): Map<String, String> {
        Mavericks.viewModelConfigFactory = MavericksViewModelConfigFactory(debugMode = false)
        activity as ComponentActivity
        return linkedMapOf(
            "persist" to step {
                val bundle = persistMavericksState(PersistedState(count = 7, label = "changed", query = "beach"))
                restorePersistedMavericksState(bundle, PersistedState()).toString()
            },
            "persistNothing" to step { persistMavericksState(PlainState(count = 3)).size().toString() },
            "defaultViewModel" to step {
                val viewModel = MavericksViewModelProvider.get(DefaultViewModel::class.java, PlainState::class.java, ActivityViewModelContext(activity, null))
                withState(viewModel) { it.toString() }
            },
            "argsViewModel" to step {
                val context = ActivityViewModelContext(activity, FixtureArgs("42"))
                val viewModel = MavericksViewModelProvider.get(ArgsViewModel::class.java, ArgsState::class.java, context)
                withState(viewModel) { it.toString() }
            },
            "factoryViewModel" to step {
                val viewModel = MavericksViewModelProvider.get(FactoryViewModel::class.java, PlainState::class.java, ActivityViewModelContext(activity, null))
                withState(viewModel) { "${viewModel.dependency} $it" }
            },
            "jvmStaticFactoryViewModel" to step {
                val viewModel = MavericksViewModelProvider.get(
                    JvmStaticFactoryViewModel::class.java,
                    PlainState::class.java,
                    ActivityViewModelContext(activity, null),
                )
                withState(viewModel) { "${viewModel.dependency} $it" }
            },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun step(block: () -> String): String = try {
        block()
    } catch (e: Throwable) {
        "error: $e"
    }
}
