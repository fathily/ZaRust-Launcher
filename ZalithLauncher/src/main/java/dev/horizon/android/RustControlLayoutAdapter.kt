package dev.horizon.android

import android.content.Context
import android.util.Log
import com.movtery.zalithlauncher.game.control.ControlManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Converts the currently selected Zalith LayerController layout to Starling's touch-layout.json.
 *
 * Starling owns the native game's input surface, so Zalith's Compose overlay cannot be drawn
 * directly over its NativeActivity. This adapter keeps the selected Zalith button positions and
 * key bindings, then writes the equivalent layout format consumed by Starling's touch engine.
 */
object RustControlLayoutAdapter {
    private const val TAG = "ZaRustControlAdapter"

    private val controlIds = listOf(
        "jump", "sneak", "inventory", "chat", "pause", "attack", "use",
        "drop", "offhand", "camera", "command", "debug", "pick", "players"
    )

    @JvmStatic
    fun apply(context: Context) {
        val target = File(RustEditionBridge.storageRoot(context), "touch-layout.json")
        if (RustEditionBridge.getInputMode(context) != "zalith") {
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Could not remove ZaRust-generated touch layout")
            }
            return
        }

        try {
            val selectedFile = ControlManager.selectedLayout.value?.file
                ?.takeIf { it.isFile }
                ?: run {
                    Log.w(TAG, "No selected Zalith control layout; keeping Starling defaults")
                    return
                }
            val source = JSONObject(selectedFile.readText())
            val layers = source.optJSONArray("layers") ?: JSONArray()
            val display = context.resources.displayMetrics
            val minSide = min(display.widthPixels, display.heightPixels).coerceAtLeast(1)
            val resultButtons = JSONObject()
            val mapped = HashSet<String>()
            val movementButtons = ArrayList<JSONObject>()
            var joystick: JSONObject? = null

            for (index in 0 until layers.length()) {
                val layer = layers.optJSONObject(index) ?: continue
                if (layer.optBoolean("hide", false)) continue
                val layerName = layer.optString("name").lowercase()
                if (layerName == "gui") continue

                val joysticks = layer.optJSONArray("joystickButtons") ?: JSONArray()
                if (joystick == null && joysticks.length() > 0) {
                    joystick = joysticks.optJSONObject(0)
                }

                val buttons = layer.optJSONArray("normalButtons") ?: JSONArray()
                for (buttonIndex in 0 until buttons.length()) {
                    val button = buttons.optJSONObject(buttonIndex) ?: continue
                    val events = button.optJSONArray("clickEvents") ?: JSONArray()
                    val keys = (0 until events.length()).mapNotNull { eventIndex ->
                        events.optJSONObject(eventIndex)?.optString("key")?.uppercase()
                    }
                    if (keys.isEmpty()) continue

                    if (keys.any { it in MOVEMENT_KEYS }) {
                        movementButtons.add(button)
                    }

                    val targetId = actionFor(keys) ?: continue
                    if (targetId in mapped) continue
                    val position = button.optJSONObject("position") ?: continue
                    resultButtons.put(
                        targetId,
                        place(
                            x = position.optInt("x", 5000) / 10000.0,
                            y = position.optInt("y", 5000) / 10000.0,
                            size = buttonRadius(button.optJSONObject("buttonSize"), minSide, display.widthPixels, display.heightPixels, display.density),
                            show = "on"
                        )
                    )
                    mapped.add(targetId)
                }
            }

            var movement = "auto"
            val stick = when {
                joystick != null -> {
                    movement = "analog"
                    val position = joystick!!.optJSONObject("position") ?: JSONObject()
                    val sizePercent = joystick!!.optInt("sizePercentage", 2500)
                    place(
                        position.optInt("x", 1200) / 10000.0,
                        position.optInt("y", 7330) / 10000.0,
                        (4.5 * (sizePercent / 10000.0)).coerceIn(0.3, 2.5),
                        "on"
                    )
                }
                movementButtons.size >= 4 -> {
                    movement = "dpad"
                    val positions = movementButtons.mapNotNull { it.optJSONObject("position") }
                    val x = positions.map { it.optInt("x", 5000) }.average() / 10000.0
                    val y = positions.map { it.optInt("y", 5000) }.average() / 10000.0
                    val buttonSize = movementButtons.firstOrNull()?.optJSONObject("buttonSize")
                    place(
                        x, y,
                        (buttonRadius(buttonSize, minSide, display.widthPixels, display.heightPixels, display.density) * 1.8).coerceIn(0.5, 2.5),
                        "on"
                    )
                }
                else -> place(0.12, 0.733, 1.3, "on")
            }

            for (id in controlIds) {
                if (!resultButtons.has(id)) {
                    resultButtons.put(id, place(0.5, 0.5, 0.6, "off"))
                }
            }

            val output = JSONObject()
                .put("version", 2)
                .put("opacity", 1.0)
                .put("movement", movement)
                .put("sneak", "toggle")
                .put("interact", "tap")
                .put("anchor", "floating")
                .put("vibrate", true)
                .put("gyro", false)
                .put("stick", stick)
                .put("buttons", resultButtons)
                .put("mods", JSONObject())

            val temporary = File(target.parentFile, target.name + ".partial")
            temporary.writeText(output.toString(2))
            if (target.exists() && !target.delete()) {
                temporary.delete()
                error("Could not replace generated touch layout")
            }
            check(temporary.renameTo(target)) { "Could not commit generated touch layout" }
            Log.i(TAG, "Applied Zalith control layout ${selectedFile.name}; mapped ${mapped.size} buttons")
        } catch (error: Exception) {
            Log.e(TAG, "Could not convert Zalith control layout", error)
        }
    }

    private fun actionFor(keys: List<String>): String? {
        if (keys.any { it.contains("MOUSE_BUTTON_LEFT") }) return "attack"
        if (keys.any { it.contains("MOUSE_BUTTON_RIGHT") }) return "use"
        if (keys.any { it.contains("MOUSE_BUTTON_MIDDLE") }) return "pick"
        return when {
            keys.any { it.endsWith("KEY_SPACE") } -> "jump"
            keys.any { it.contains("LEFT_SHIFT") || it.contains("RIGHT_SHIFT") } -> "sneak"
            keys.any { it.endsWith("KEY_ESCAPE") } -> "pause"
            keys.any { it.endsWith("KEY_E") } -> "inventory"
            keys.any { it.endsWith("KEY_T") } -> "chat"
            keys.any { it.endsWith("KEY_F3") } -> "debug"
            keys.any { it.endsWith("KEY_F5") } -> "camera"
            keys.any { it.endsWith("KEY_Q") } -> "drop"
            keys.any { it.endsWith("KEY_F") } -> "offhand"
            keys.any { it.endsWith("KEY_SLASH") } -> "command"
            keys.any { it.endsWith("KEY_TAB") } -> "players"
            else -> null
        }
    }

    private fun buttonRadius(
        size: JSONObject?,
        minSide: Int,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ): Double {
        if (size == null) return 0.65
        val widthPercent = size.optInt("widthPercentage", 1200) / 10000.0
        val heightPercent = size.optInt("heightPercentage", 1200) / 10000.0
        val widthReference = if (size.optString("widthReference") == "screen_width") screenWidth else screenHeight
        val heightReference = if (size.optString("heightReference") == "screen_width") screenWidth else screenHeight
        val percentageDiameter = max(widthPercent * widthReference, heightPercent * heightReference)
        val diameterPx = if (size.optString("type") == "dp") {
            max(size.optDouble("widthDp", 50.0), size.optDouble("heightDp", 50.0)) * density
        } else {
            percentageDiameter
        }
        return (4.5 * diameterPx / minSide).coerceIn(0.3, 2.5)
    }

    private fun place(x: Double, y: Double, size: Double, show: String): JSONObject =
        JSONObject()
            .put("x", x.coerceIn(0.0, 1.0))
            .put("y", y.coerceIn(0.0, 1.0))
            .put("size", size.coerceIn(0.3, 2.5))
            .put("show", show)
            .put("opacity", 1.0)

    private val MOVEMENT_KEYS = setOf(
        "GLFW_KEY_W", "GLFW_KEY_A", "GLFW_KEY_S", "GLFW_KEY_D"
    )
}
