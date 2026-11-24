package com.obddash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obddash.ConnState
import com.obddash.ObdApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Trouble codes on the car screen (read-only; clearing is left to the phone app so it
 * can't be triggered by an accidental tap while driving).
 */
class CodesCarScreen(carContext: CarContext) : Screen(carContext) {

    private val controller = (carContext.applicationContext as ObdApp).controller

    init {
        controller.readCodes()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.map { it.codes to it.conn }.distinctUntilChanged().collect { invalidate() }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val s = controller.state.value
        val c = s.codes
        val builder = ListTemplate.Builder().setTitle("Trouble codes").setHeaderAction(Action.BACK)

        if (c.loading) return builder.setLoading(true).build()

        val list = ItemList.Builder()
        if (s.conn != ConnState.Connected) {
            list.setNoItemsMessage("Not connected to the car")
        } else {
            val mil = when (c.milOn) { true -> "ON"; false -> "OFF"; null -> "unknown" }
            list.addItem(
                Row.Builder()
                    .setTitle("Check engine light: $mil")
                    .addText("${c.storedCount ?: c.stored.size} stored · ${c.pending.size} pending")
                    .build()
            )
            c.error?.let { list.addItem(Row.Builder().setTitle("Error").addText(it).build()) }
            if (c.read && c.stored.isEmpty() && c.pending.isEmpty()) {
                list.addItem(Row.Builder().setTitle("No trouble codes").addText("No stored or pending faults").build())
            }
            c.stored.forEach { list.addItem(Row.Builder().setTitle("${it.code} · stored").addText(it.description).build()) }
            c.pending.forEach { list.addItem(Row.Builder().setTitle("${it.code} · pending").addText(it.description).build()) }
        }

        return builder
            .setSingleList(list.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("Read again").setOnClickListener { controller.readCodes() }.build())
                    .build()
            )
            .build()
    }
}
