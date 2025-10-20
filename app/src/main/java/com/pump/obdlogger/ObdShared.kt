package com.pump.obdlogger

/** Simple holder so RealtimeActivity can use the same ObdManager instance. */
object ObdShared {
    var obd: ObdManager? = null
}
